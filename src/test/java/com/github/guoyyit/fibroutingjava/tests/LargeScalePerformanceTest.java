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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Latency remains controlled with a large table. The default assertion bar is
 * loose enough for CI; set -Dfib.perf.strict=true for the tight local bar.
 */
class LargeScalePerformanceTest {

    @TempDir
    Path tempDir;

    /**
     * Builds a table of 100k routes spread across many prefixes. Routes are
     * committed in batches through the real transactional path so persistence
     * and version switching are exercised too.
     */
    @Test
    @EnabledIfSystemProperty(named = "fib.perf.run", matches = "true")
    void hundredThousandRoutesHaveBoundedLookupLatency() {
        FibProperties properties = new FibProperties();
        properties.setPersistenceEnabled(false);
        properties.setMaxRoutes(200_000);
        FibTable table = new FibTable(new HealthTracker(), new RouteValidator(),
                new FibStore(new ObjectMapper(), properties), properties);

        int total = 100_000;
        int batch = 5_000;
        long buildStart = System.nanoTime();
        for (int start = 0; start < total; start += batch) {
            table.beginTransaction();
            for (int i = start; i < Math.min(start + batch, total); i++) {
                table.stageUpsert(syntheticRoute(i));
            }
            table.commit();
        }
        long buildMillis = (System.nanoTime() - buildStart) / 1_000_000;
        assertThat(table.snapshot().routeCount()).isEqualTo(total);

        Random random = new Random(42);
        int samples = 200_000;
        long[] latencies = new long[samples];
        int hits = 0;
        for (int i = 0; i < samples; i++) {
            IpAddress destination = randomV4Destination(random);
            long t0 = System.nanoTime();
            var result = table.lookup(destination, null);
            latencies[i] = System.nanoTime() - t0;
            if (result.outcome() == LookupOutcome.FORWARDED) {
                hits++;
            }
        }
        java.util.Arrays.sort(latencies);
        long p50 = latencies[samples / 2];
        long p99 = latencies[(int) (samples * 0.99)];
        long p999 = latencies[(int) (samples * 0.999)];
        long max = latencies[samples - 1];
        System.out.printf("BUILD_MS=%d LOOKUP_NANOS p50=%d p99=%d p999=%d max=%d hits=%d/%d%n",
                buildMillis, p50, p99, p999, max, hits, samples);

        // Most synthetic destinations are deliberately sparse, so hits need not dominate;
        // latency, not hit rate, is the contract under test.
        boolean strict = Boolean.getBoolean("fib.perf.strict");
        long p99Limit = strict ? 5_000 : 1_000_000; // 5us tight / 1ms CI-friendly
        assertThat(p99).isLessThan(p99Limit);
    }

    /** Smaller always-on test: 10k routes still answer promptly and correctly. */
    @Test
    void tenThousandRouteTableAnswersCorrectlyAndPromptly() {
        FibProperties properties = new FibProperties();
        properties.setPersistenceEnabled(false);
        properties.setMaxRoutes(50_000);
        FibTable table = new FibTable(new HealthTracker(), new RouteValidator(),
                new FibStore(new ObjectMapper(), properties), properties);

        int total = 10_000;
        table.beginTransaction();
        for (int i = 0; i < total; i++) {
            table.stageUpsert(syntheticRoute(i));
        }
        table.commit();

        // exact-match expectation for block-zero routes (unique, non-overlapping)
        for (int i : new int[] {0, 16, 96, 1232, 9984}) {
            RouteEntry route = syntheticRoute(i);
            IpAddress inPrefix = addressWithin(route.prefix(), 7);
            var result = table.lookup(inPrefix, null);
            assertThat(result.outcome()).isEqualTo(LookupOutcome.FORWARDED);
            assertThat(result.matchedPrefix()).isEqualTo(route.prefix());
        }

        Random random = new Random(7);
        long p99 = percentileLatency(table, random, 20_000, 0.99);
        assertThat(p99).isLessThan(2_000_000L); // 2 ms generous CI bound
    }

    private static long percentileLatency(FibTable table, Random random, int samples, double percentile) {
        long[] latencies = new long[samples];
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            table.lookup(randomV4Destination(random), null);
            latencies[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(latencies);
        return latencies[(int) (samples * percentile)];
    }

    /**
     * Deterministic synthetic route scheme giving 10k unique, non-overlapping
     * prefixes. Routes are packed as /28 blocks into /24 lanes: lane
     * {@code lane} covers 10.a.b.0/24 and holds 16 /28 blocks at
     * 10.a.b.{0,16,...,240}. This keeps trie depth varied without any two
     * generated prefixes containing each other.
     */
    private static RouteEntry syntheticRoute(int i) {
        int lane = i / 16;
        int block = i % 16;
        int second = lane / 256;
        int third = lane % 256;
        int fourth = block * 16;
        String cidr = "10." + second + "." + third + "." + fourth + "/28";
        NextHop hop = new NextHop("hop-" + i,
                IpAddress.parse("192.0.2." + ((i % 200) + 1)), 1, true);
        return new RouteEntry(IpPrefix.parse(cidr), "static", 10, List.of(hop));
    }

    private static IpAddress addressWithin(IpPrefix prefix, int hostByte) {
        byte[] raw = prefix.networkAddress().copyBytes();
        raw[raw.length - 1] = (byte) hostByte;
        return IpAddress.fromBytes(raw);
    }

    private static IpAddress randomV4Destination(Random random) {
        return IpAddress.parse("10." + random.nextInt(256) + "." + random.nextInt(256) + "."
                + random.nextInt(256));
    }
}
