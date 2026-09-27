package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.fib.FibService;
import com.github.guoyyit.fibroutingjava.fib.LookupControl;
import com.github.guoyyit.fibroutingjava.fib.RouteValidator;
import com.github.guoyyit.fibroutingjava.fib.WeightedRendezvousSelector;
import com.github.guoyyit.fibroutingjava.health.HealthRegistry;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupOutcome;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.persistence.FibPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大规模表性能测试：
 * 插入 5 万条 /24 路由（批量暂存 + 一次原子提交），验证：
 * 1) 提交为一次原子版本切换；2) 查找复杂度只与地址位宽（32/128）有关，
 * 与表规模基本无关，p99 延迟受控（阈值设得宽松以适配 CI 抖动）。
 */
class LargeScalePerformanceTest {

    private static final int ROUTES = 50_000;

    private Path tmpFile;
    private FibService fib;

    @BeforeEach
    void setUp() throws Exception {
        tmpFile = Files.createTempFile("fib-perf-", ".json");
        Files.delete(tmpFile);
        FibProperties p = new FibProperties();
        p.setMaxRoutes(200_000);
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

    @Test
    void bulkCommitAndLatencyStaysBounded() {
        // ---- 批量原子插入：ROUTES 条路由全部走一次 commit ----
        long t0 = System.nanoTime();
        for (int i = 0; i < ROUTES; i++) {
            String cidr = String.format("10.%d.%d.0/24", (i >> 8) & 0xff, i & 0xff);
            fib.stageUpsert(new RouteEntry(IpPrefix.parse(cidr), 0,
                    List.of(new NextHop("h" + i, "192.0." + ((i >> 8) & 0xff) + "." + (i & 0xff),
                            1 + (i % 5), true))));
        }
        fib.commit();
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(ROUTES, fib.currentSnapshot().routeCount());
        // 版本号只跳一次 => 5 万条更新对外是一个完整版本
        assertEquals(1, fib.currentSnapshot().version());
        assertTrue(buildMs < 20_000, "bulk build took too long: " + buildMs + "ms");

        // ---- 查找延迟受控：应只与前缀深度（<=32）相关 ----
        // 先预热触发 JIT 编译，再测量稳态延迟
        for (int i = 0; i < 20_000; i++) {
            int idx = (i * 7919 + 17) % ROUTES;
            String ip = String.format("10.%d.%d.55", (idx >> 8) & 0xff, idx & 0xff);
            fib.lookup(ip, "warm-" + i);
        }
        int probes = 200_000;
        long[] costs = new long[probes];
        long sum = 0;
        for (int i = 0; i < probes; i++) {
            int idx = (i * 7919 + 17) % ROUTES; // 确定性伪随机分散探测
            String ip = String.format("10.%d.%d.55", (idx >> 8) & 0xff, idx & 0xff);
            // 每次查找独立 50ms 预算，验证单次查找延迟受控
            LookupControl control = LookupControl.withBudget(50_000_000L);
            long s = System.nanoTime();
            var r = fib.lookup(ip, "flow-" + i, control);
            long cost = System.nanoTime() - s;
            costs[i] = cost;
            sum += cost;
            assertEquals(LookupOutcome.MATCHED, r.outcome(), "all probes must hit: " + ip);
        }
        java.util.Arrays.sort(costs);
        long p50 = costs[probes / 2];
        long p99 = costs[(int) (probes * 0.99)];
        long p999 = costs[(int) (probes * 0.999)];
        long avgNanos = sum / probes;
        long outliers = 0;
        for (long c : costs) {
            if (c > 1_000_000L) {
                outliers++;
            }
        }
        // LPM 深度 24 位：稳态延迟应在微秒级以内。
        // 关注中位数/p99/p999 等统计量；偶发 >1ms 的极端值只允许是极个别
        // 操作系统/GC/safepoint 抖动（20 万样本中阈值取 0.01%）。
        assertTrue(avgNanos < 20_000, "avg lookup too slow: " + avgNanos + "ns");
        assertTrue(p50 < 10_000, "p50 lookup too slow: " + p50 + "ns");
        assertTrue(p99 < 50_000, "p99 lookup too slow: " + p99 + "ns");
        assertTrue(p999 < 200_000, "p999 lookup too slow: " + p999 + "ns");
        assertTrue(outliers <= probes / 10_000,
                "too many >1ms outliers: " + outliers + "/" + probes
                        + " (p999=" + p999 + "ns)");
    }
}
