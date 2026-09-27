package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.fib.FibService;
import com.github.guoyyit.fibroutingjava.fib.RouteValidator;
import com.github.guoyyit.fibroutingjava.fib.WeightedRendezvousSelector;
import com.github.guoyyit.fibroutingjava.health.HealthRegistry;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupOutcome;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.persistence.FibPersistenceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceRecoveryTest {

    @TempDir
    Path dir;

    private FibProperties props(String file) {
        FibProperties p = new FibProperties();
        p.setPersistenceFile(dir.resolve(file).toString());
        p.setRestoreOnStart(true);
        return p;
    }

    private FibService newService(FibProperties p) {
        FibService fib = new FibService(p, new WeightedRendezvousSelector(),
                new RouteValidator(), new HealthRegistry(), new FibPersistenceService(p));
        try {
            var m = FibService.class.getDeclaredMethod("restore");
            m.setAccessible(true);
            m.invoke(fib);
        } catch (Exception e) {
            throw new RuntimeException(e.getCause() == null ? e : e.getCause());
        }
        return fib;
    }

    private NextHop nh(String id, String addr) {
        return new NextHop(id, addr, 1, true);
    }

    @Test
    void committedRoutesSurviveRestart() {
        FibProperties p = props("fib.json");
        FibService first = newService(p);
        first.upsertAndCommit(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0,
                List.of(nh("a", "10.0.0.1"))));
        first.upsertAndCommit(new RouteEntry(IpPrefix.parse("2001:db8::/32"), 0,
                List.of(nh("v6", "2001:db8::1"))));
        long version = first.currentSnapshot().version();

        FibService second = newService(p);
        assertEquals(version, second.currentSnapshot().version());
        assertEquals(2, second.currentSnapshot().routeCount());
        assertEquals("a", second.lookup("10.9.9.9", "f").selectedNextHop().id());
        assertEquals("v6", second.lookup("2001:db8:9::1", "f").selectedNextHop().id());
    }

    @Test
    void uncommittedStagedChangesDoNotSurvive() {
        FibProperties p = props("fib2.json");
        FibService first = newService(p);
        first.upsertAndCommit(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0,
                List.of(nh("committed", "10.0.0.1"))));
        first.stageUpsert(new RouteEntry(IpPrefix.parse("11.0.0.0/8"), 0,
                List.of(nh("staged", "11.0.0.1"))));
        // 不 commit，直接重启
        FibService second = newService(p);
        assertEquals(1, second.currentSnapshot().routeCount());
        assertNotNull(second.currentSnapshot().getRoute(IpPrefix.parse("10.0.0.0/8"), 0));
        assertEquals(LookupOutcome.UNREACHABLE, second.lookup("11.1.1.1", "f").outcome());
    }

    @Test
    void corruptFileIsRejectedAndDoesNotPolluteCommittedState() throws Exception {
        FibProperties p = props("fib3.json");
        FibService first = newService(p);
        first.upsertAndCommit(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0,
                List.of(nh("a", "10.0.0.1"))));
        Path file = Path.of(p.getPersistenceFile());
        byte[] good = Files.readAllBytes(file);

        // 模拟部分写入：截断一半
        Files.write(file, java.util.Arrays.copyOf(good, good.length / 2));
        assertThrows(FibPersistenceService.CorruptFibFileException.class,
                () -> new FibPersistenceService(p).loadCommitted());

        // 模拟字节损坏（翻转 JSON 中间字节但保留 CRC 行）=> CRC 不匹配
        byte[] damaged = good.clone();
        damaged[5] ^= 0x01;
        Files.write(file, damaged);
        assertThrows(FibPersistenceService.CorruptFibFileException.class,
                () -> new FibPersistenceService(p).loadCommitted());

        // 无 CRC 尾行
        Files.write(file, "{\"version\":1}".getBytes(StandardCharsets.UTF_8));
        assertThrows(FibPersistenceService.CorruptFibFileException.class,
                () -> new FibPersistenceService(p).loadCommitted());

        // 服务层遇到损坏文件：以空表启动（已提交状态不被污染），但不影响之后重新提交
        FibService recovered = newService(p);
        assertEquals(0, recovered.currentSnapshot().routeCount());
        recovered.upsertAndCommit(new RouteEntry(IpPrefix.parse("12.0.0.0/8"), 0,
                List.of(nh("new", "12.0.0.1"))));
        assertEquals("new", recovered.lookup("12.1.1.1", "f").selectedNextHop().id());
    }

    @Test
    void saveIsAtomicViaTempFile() throws Exception {
        FibProperties p = props("fib4.json");
        FibService first = newService(p);
        for (int i = 0; i < 10; i++) {
            first.upsertAndCommit(new RouteEntry(IpPrefix.parse("10." + i + ".0.0/16"), 0,
                    List.of(nh("h" + i, "10." + i + ".0.1"))));
        }
        // 目标文件始终是完整可读的一份；无遗留临时文件
        Path target = Path.of(p.getPersistenceFile());
        assertTrue(Files.exists(target));
        assertNotNull(new FibPersistenceService(p).loadCommitted());
        try (var listing = Files.list(dir)) {
            listing.forEach(f -> assertTrue(!f.getFileName().toString().contains(".tmp."),
                    "temp file left behind: " + f));
        }
    }
}
