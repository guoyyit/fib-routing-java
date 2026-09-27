package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.fib.FibService;
import com.github.guoyyit.fibroutingjava.fib.FibSnapshot;
import com.github.guoyyit.fibroutingjava.fib.RouteValidator;
import com.github.guoyyit.fibroutingjava.fib.WeightedRendezvousSelector;
import com.github.guoyyit.fibroutingjava.health.HealthRegistry;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.persistence.FibPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailoverAndConcurrencyTest {

    private Path tmpFile;
    private FibService fib;

    @BeforeEach
    void setUp() throws Exception {
        tmpFile = Files.createTempFile("fib-fo-", ".json");
        Files.delete(tmpFile);
        FibProperties p = new FibProperties();
        p.setPersistenceFile(tmpFile.toString());
        fib = new FibService(p, new WeightedRendezvousSelector(), new RouteValidator(),
                new HealthRegistry(), new FibPersistenceService(p));
        var m = FibService.class.getDeclaredMethod("restore");
        m.setAccessible(true);
        m.invoke(fib);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tmpFile);
    }

    private NextHop nh(String id) {
        return new NextHop(id, "192.0.2." + id, 1, true);
    }

    @Test
    void failoverImmediatelyMovesAwayFromDeadHop() {
        fib.upsertAndCommit(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0,
                List.of(nh("1"), nh("2"), nh("3"))));

        Set<String> before = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            before.add(fib.lookup("10.1.1.1", "flow-" + i).selectedNextHop().id());
        }
        assertEquals(3, before.size());

        fib.health().setHealthy("1", false);
        for (int i = 0; i < 500; i++) {
            String picked = fib.lookup("10.1.1.1", "flow-" + i).selectedNextHop().id();
            assertTrue(!picked.equals("1"), "dead hop 1 must never be selected after failover");
        }

        // 恢复后可以再次被选中
        fib.health().setHealthy("1", true);
        boolean seenAgain = false;
        for (int i = 0; i < 2000; i++) {
            if (fib.lookup("10.1.1.1", "flow-r" + i).selectedNextHop().id().equals("1")) {
                seenAgain = true;
                break;
            }
        }
        assertTrue(seenAgain, "recovered hop should be selectable again");
    }

    @Test
    void allHopsDeadMeansUnreachableNotStalePath() {
        fib.upsertAndCommit(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0,
                List.of(nh("1"), nh("2"))));
        fib.health().setHealthy("1", false);
        fib.health().setHealthy("2", false);
        // 同前缀无可用 => 退化到更短或不可达，这里无更短前缀
        var r = fib.lookup("10.1.1.1", "f");
        assertEquals(com.github.guoyyit.fibroutingjava.model.LookupOutcome.UNREACHABLE, r.outcome());
    }

    @Test
    void concurrentWritersProduceLinearAtomicVersions() throws Exception {
        int writers = 8;
        int perWriter = 50;
        // 每个 writer 使用独立 /16 前缀，下一跳放在另一个 writer 不会触及的 192.0.x 地址，
        // 从而避免跨条目的下一跳解析环路（环路拒绝由 RouteValidationTest 单独覆盖）。
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(true);
        for (int w = 0; w < writers; w++) {
            final int wid = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        String cidr = "10." + wid + "." + i + ".0/24";
                        NextHop hop = new NextHop("w" + wid + "-" + i,
                                "192.0." + wid + "." + (i + 1), 1, true);
                        fib.upsertAndCommit(
                                new RouteEntry(IpPrefix.parse(cidr), 0, List.of(hop)));
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    ok.set(false);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(ok.get());
        assertEquals(writers * perWriter, fib.currentSnapshot().routeCount());
        assertEquals(writers * perWriter, fib.currentSnapshot().version());
    }

    @Test
    void readersAlwaysObserveOneCompleteVersion() throws Exception {
        // 初始一个共享前缀
        fib.upsertAndCommit(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0,
                List.of(new NextHop("base", "192.0.2.10", 1, true))));
        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> violations = ConcurrentHashMap.newKeySet();
        for (int r = 0; r < readers; r++) {
            final int rid = r;
            pool.submit(() -> {
                try {
                    start.await();
                    for (long i = 0; i < 50_000; i++) {
                        String flow = "r" + rid + "-" + i;
                        LookupResult res = fib.lookup("10.250.0.1", flow);
                        FibSnapshot snap = fib.currentSnapshot();
                        // 结果声明的版本要么 <= 当前版本；且该版本上结果必须自洽：
                        if (res.version() > snap.version()) {
                            violations.add("future version observed");
                        }
                        if (res.selectedNextHop() == null) {
                            violations.add("unreachable while 10.0.0.0/8 always present");
                        }
                    }
                } catch (Throwable t) {
                    violations.add(t.toString());
                }
            });
        }
        // 一个持续更新者：不断替换同一路由的下一跳集合
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 2000; i++) {
                    fib.upsertAndCommit(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0,
                            List.of(new NextHop("v" + (i % 4), "192.0.2." + (20 + i % 4), 1, true))));
                }
            } catch (Throwable ignored) {
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "timeout");
        assertTrue(violations.isEmpty(), "consistency violations: " + violations);
    }
}
