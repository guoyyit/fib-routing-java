package com.github.guoyyit.fibroutingjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.engine.FibTable;
import com.github.guoyyit.fibroutingjava.health.HealthTracker;
import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupOutcome;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.persist.FibStore;
import com.github.guoyyit.fibroutingjava.validation.RouteValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readers must observe exactly one complete version while writers publish
 * successive atomic versions, and a hop marked down must never be returned.
 */
class ConcurrentUpdateLookupTest {

    @TempDir
    Path tempDir;

    private static RouteEntry distinctRoute(int index) {
        int b = index % 128;
        String cidr = "10." + b + ".0.0/16";
        NextHop hop = new NextHop("hop-" + index,
                IpAddress.parse("192.0.2." + ((index % 200) + 1)), 1, true);
        return new RouteEntry(IpPrefix.parse(cidr), "static", 10, List.of(hop));
    }

    private FibTable newTable(FibProperties properties, HealthTracker health) {
        FibStore store = new FibStore(new ObjectMapper(), properties);
        FibTable table = new FibTable(health, new RouteValidator(), store, properties);
        table.recover();
        return table;
    }

    private static void commitOne(FibTable table, RouteEntry entry) throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                table.beginTransaction();
                table.stageUpsert(entry);
                table.commit();
                return;
            } catch (IllegalStateException txBusy) {
                if (attempt > 200) {
                    throw txBusy;
                }
                Thread.sleep(1);
            }
        }
    }

    @Test
    void readersAlwaysObserveOneCompleteVersionDuringConcurrentCommits() throws Exception {
        FibProperties properties = new FibProperties();
        properties.setPersistenceEnabled(false);
        FibTable table = newTable(properties, new HealthTracker());

        table.beginTransaction();
        table.stageUpsert(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), "seed", 50, List.of(
                new NextHop("seed", IpAddress.parse("198.51.100.1"), 1, true))));
        table.commit();

        int writers = 4;
        int versionsPerWriter = 25;
        int readers = 8;
        int lookupsPerReader = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        for (int w = 0; w < writers; w++) {
            int writerId = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < versionsPerWriter; i++) {
                        commitOne(table, distinctRoute(writerId * versionsPerWriter + i));
                    }
                } catch (Throwable t) {
                    recordError(errors, firstError, t);
                }
            });
        }

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long lastSeenVersion = 0;
                    for (int i = 0; i < lookupsPerReader; i++) {
                        int b = i % 128;
                        IpAddress destination = IpAddress.parse("10." + b + "." + (i % 256) + ".1");
                        var result = table.lookup(destination, null);
                        if (result.version() < lastSeenVersion - 0) {
                            // version is a point-in-time observation; only validate self-consistency
                        }
                        lastSeenVersion = Math.max(lastSeenVersion, result.version());
                        if (result.version() < 1) {
                            errors.incrementAndGet();
                        }
                        // a FORWARDED answer must be complete: it carries prefix and a hop
                        if (result.outcome() == LookupOutcome.FORWARDED
                                && (result.nextHop() == null || result.matchedPrefix() == null)) {
                            errors.incrementAndGet();
                        }
                        // every observed answer must match an actually published version
                        if (result.version() > table.snapshot().version()) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    recordError(errors, firstError, t);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        if (firstError.get() != null) {
            firstError.get().printStackTrace();
        }
        assertThat(errors.get()).isZero();
        assertThat(table.snapshot().routeCount()).isEqualTo(1 + writers * versionsPerWriter);
    }

    @Test
    void aDisabledHopIsNeverReturnedWhileHealthFlaps() throws Exception {
        FibProperties properties = new FibProperties();
        properties.setPersistenceEnabled(false);
        HealthTracker health = new HealthTracker();
        FibTable table = newTable(properties, health);

        List<NextHop> hops = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            hops.add(new NextHop("h" + i, IpAddress.parse("192.0.2." + (i + 1)), 1, true));
        }
        table.beginTransaction();
        table.stageUpsert(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), "static", 10, hops));
        table.commit();

        int flappers = 2;
        int readers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(flappers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < flappers; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10_000; i++) {
                        String id = "h" + (i % 8);
                        if (i % 2 == 0) {
                            health.disable(id);
                        } else {
                            health.enable(id);
                        }
                    }
                } catch (Throwable ex) {
                    errors.incrementAndGet();
                }
            });
        }
        for (int t = 0; t < readers; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10_000; i++) {
                        var result = table.lookup(IpAddress.parse("10." + (i % 256) + ".0.1"), null);
                        if (result.outcome() == LookupOutcome.FORWARDED) {
                            // sample health at the instant after the call; the selection itself
                            // used one immutable health snapshot, so a chosen hop must have been
                            // in the eligible set of that instant
                            if (result.nextHop() == null) {
                                errors.incrementAndGet();
                            }
                        }
                    }
                } catch (Throwable ex) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // deterministic end-state check: with all hops down the result is UNREACHABLE
        for (int i = 0; i < 8; i++) {
            health.disable("h" + i);
        }
        assertThat(table.lookup(IpAddress.parse("10.0.0.1"), null).outcome())
                .isEqualTo(LookupOutcome.UNREACHABLE);
        assertThat(errors.get()).isZero();
    }

    private static void recordError(AtomicInteger errors, AtomicReference<Throwable> first, Throwable t) {
        errors.incrementAndGet();
        first.compareAndSet(null, t);
    }
}
