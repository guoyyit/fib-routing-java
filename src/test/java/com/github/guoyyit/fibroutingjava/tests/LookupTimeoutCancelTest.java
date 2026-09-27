package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.fib.FibService;
import com.github.guoyyit.fibroutingjava.fib.LookupCancelledException;
import com.github.guoyyit.fibroutingjava.fib.LookupControl;
import com.github.guoyyit.fibroutingjava.fib.LookupTimeoutException;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupTimeoutCancelTest {

    private Path tmpFile;
    private FibService fib;

    @BeforeEach
    void setUp() throws Exception {
        tmpFile = Files.createTempFile("fib-to-", ".json");
        Files.delete(tmpFile);
        FibProperties p = new FibProperties();
        p.setPersistenceFile(tmpFile.toString());
        fib = new FibService(p, new WeightedRendezvousSelector(), new RouteValidator(),
                new HealthRegistry(), new FibPersistenceService(p));
        var m = FibService.class.getDeclaredMethod("restore");
        m.setAccessible(true);
        m.invoke(fib);
        fib.upsertAndCommit(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0,
                List.of(new NextHop("a", "10.0.0.1", 1, true))));
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tmpFile);
    }

    @Test
    void expiredDeadlineThrowsTimeoutWithoutPartialResult() {
        // 截止时间已在过去 => 首个 checkpoint 立即终止；API 要么返回完整结果要么抛异常
        LookupControl expired = new LookupControl(System.nanoTime() - 1_000_000L, null);
        assertThrows(LookupTimeoutException.class,
                () -> fib.lookup("10.1.1.1", "f", expired));
    }

    @Test
    void cancellationSignalThrowsCancelled() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        LookupControl control = new LookupControl(System.nanoTime() + 1_000_000_000L,
                cancelled::get);
        assertThrows(LookupCancelledException.class,
                () -> fib.lookup("10.1.1.1", "f", control));
    }

    @Test
    void threadInterruptIsTreatedAsCancel() {
        AtomicBoolean entered = new AtomicBoolean(false);
        LookupControl control = new LookupControl(Long.MAX_VALUE,
                () -> {
                    // 模拟中断：第一次被询问时置中断位
                    entered.set(true);
                    return Thread.currentThread().isInterrupted();
                });
        // 通过直接构造一个中断的线程来验证语义：这里简化为预设置中断位
        Thread.currentThread().interrupt();
        try {
            assertThrows(LookupCancelledException.class,
                    () -> fib.lookup("10.1.1.1", "f", control));
        } finally {
            assertTrue(Thread.interrupted(), "interrupt flag should be consumed by test");
        }
    }

    @Test
    void healthyLookupWithinBudgetReturnsCompleteResult() {
        LookupControl control = LookupControl.withBudget(1_000_000_000L);
        LookupResult r = fib.lookup("10.1.1.1", "flow-1", control);
        assertEquals("a", r.selectedNextHop().id());
        assertTrue(control.remainingNanos() > 0);
    }

    @Test
    void defaultServiceConfigBudgetIsGenerous() {
        // 走 FibService 默认预算（application properties 1s，单元构造默认 1s）
        LookupResult r = fib.lookup("10.1.1.1", "flow");
        assertEquals("a", r.selectedNextHop().id());
    }
}
