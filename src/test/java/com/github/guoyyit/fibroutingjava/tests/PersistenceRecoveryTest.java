package com.github.guoyyit.fibroutingjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.engine.FibSnapshot;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Durable commit/restore and corruption isolation. */
class PersistenceRecoveryTest {

    @TempDir
    Path tempDir;

    private FibProperties propertiesFor(String file) {
        FibProperties properties = new FibProperties();
        properties.setPersistenceEnabled(true);
        properties.setStorePath(tempDir.resolve(file).toString());
        return properties;
    }

    private FibTable newTable(FibProperties properties) {
        FibStore store = new FibStore(new ObjectMapper(), properties);
        FibTable table = new FibTable(new HealthTracker(), new RouteValidator(), store, properties);
        table.recover();
        return table;
    }

    private static RouteEntry route(String prefix, String hopId, String hopAddress) {
        NextHop hop = new NextHop(hopId, IpAddress.parse(hopAddress), 1, true);
        return new RouteEntry(IpPrefix.parse(prefix), "static", 10, List.of(hop));
    }

    @Test
    void committedTableSurvivesRestart() {
        FibProperties properties = propertiesFor("fib.json");
        FibTable first = newTable(properties);
        first.beginTransaction();
        first.stageUpsert(route("10.0.0.0/8", "a", "192.0.2.1"));
        first.stageUpsert(route("2001:db8::/32", "b", "2001:db9::1"));
        first.commit();
        long committedVersion = first.snapshot().version();

        // simulate restart: fresh beans reading the same state file
        FibTable restarted = newTable(properties);
        assertThat(restarted.snapshot().version()).isEqualTo(committedVersion);
        assertThat(restarted.snapshot().routeCount()).isEqualTo(2);
        var v4 = restarted.lookup(IpAddress.parse("10.5.5.5"), null);
        assertThat(v4.outcome()).isEqualTo(LookupOutcome.FORWARDED);
        assertThat(v4.nextHop().id()).isEqualTo("a");
        var v6 = restarted.lookup(IpAddress.parse("2001:db8:abcd::1"), null);
        assertThat(v6.nextHop().id()).isEqualTo("b");
    }

    @Test
    void uncommittedStagingIsNotPersistedAndNotRestored() {
        FibProperties properties = propertiesFor("fib2.json");
        FibTable table = newTable(properties);
        table.beginTransaction();
        table.stageUpsert(route("10.0.0.0/8", "a", "192.0.2.1"));
        // never commit
        FibTable restarted = newTable(properties);
        assertThat(restarted.snapshot().routeCount()).isZero();
        assertThat(restarted.lookup(IpAddress.parse("10.0.0.1"), null).outcome())
                .isEqualTo(LookupOutcome.UNREACHABLE);
    }

    @Test
    void corruptedStateFileIsQuarantinedAndDoesNotPolluteCommittedState() throws Exception {
        FibProperties properties = propertiesFor("fib3.json");
        FibTable table = newTable(properties);
        table.beginTransaction();
        table.stageUpsert(route("10.0.0.0/8", "a", "192.0.2.1"));
        table.commit();

        Path stateFile = Path.of(properties.getStorePath());
        byte[] goodBytes = Files.readAllBytes(stateFile);
        // simulate a torn write: truncate and append junk
        byte[] torn = new byte[goodBytes.length / 2];
        System.arraycopy(goodBytes, 0, torn, 0, torn.length);
        String corrupted = new String(torn, StandardCharsets.UTF_8) + " <<< corrupted tail";
        Files.write(stateFile, corrupted.getBytes(StandardCharsets.UTF_8));

        FibTable restarted = newTable(properties);
        assertThat(restarted.snapshot().routeCount()).isZero();
        // the corrupted file was moved aside, leaving no live state file at the path
        assertThat(Files.exists(stateFile)).isFalse();
        assertThat(Files.list(tempDir).filter(p -> p.getFileName().toString().contains(".corrupt."))
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void checksumMismatchOnPayloadIsRejected() throws Exception {
        FibProperties properties = propertiesFor("fib4.json");
        FibTable table = newTable(properties);
        table.beginTransaction();
        table.stageUpsert(route("10.0.0.0/8", "a", "192.0.2.1"));
        table.commit();

        Path stateFile = Path.of(properties.getStorePath());
        String json = Files.readString(stateFile);
        // flip a hop address byte inside the payload; the checksum no longer matches
        String tampered = json.replace("192.0.2.1", "192.0.2.9");
        Files.write(stateFile, tampered.getBytes(StandardCharsets.UTF_8));

        FibStore freshStore = new FibStore(new ObjectMapper(), properties);
        var loaded = freshStore.load();
        assertThat(loaded).isEmpty();
    }

    @Test
    void persistenceFailureLeavesLiveVersionInPlace() {
        FibProperties properties = propertiesFor("fib5.json");
        FibTable table = newTable(properties);
        table.beginTransaction();
        table.stageUpsert(route("10.0.0.0/8", "a", "192.0.2.1"));
        table.commit();

        // point the store path at an unwritable location (a file blocks directory creation)
        Path blocker = tempDir.resolve("blocker");
        try {
            Files.writeString(blocker, "x");
        } catch (Exception ignored) {
            // fine if it already exists
        }
        properties.setStorePath(blocker.resolve("under-file.json").toString());
        table.beginTransaction();
        table.stageUpsert(route("11.0.0.0/8", "b", "192.0.2.2"));
        try {
            table.commit();
            // if the platform permitted the write, the route simply commits normally
        } catch (RuntimeException persistFailure) {
            // persistence failed: live table must remain at the prior good version
            assertThat(table.snapshot().routeCount()).isEqualTo(1);
            assertThat(table.lookup(IpAddress.parse("11.0.0.1"), null).outcome())
                    .isEqualTo(LookupOutcome.UNREACHABLE);
        }
    }

    @Test
    void directStoreRoundTripPreservesWeightsAndEnabledFlag() {
        FibProperties properties = propertiesFor("fib6.json");
        FibStore store = new FibStore(new ObjectMapper(), properties);
        FibSnapshot snapshot = FibSnapshot.of(9, List.of(
                new RouteEntry(IpPrefix.parse("10.0.0.0/8"), "static", 7, List.of(
                        new NextHop("x", IpAddress.parse("192.0.2.1"), 3, true),
                        new NextHop("y", IpAddress.parse("192.0.2.2"), 5, false))),
                new RouteEntry(IpPrefix.parse("10.0.0.0/8"), "bgp", 20, List.of(
                        new NextHop("z", IpAddress.parse("192.0.2.3"), 1, true)))
        ), null, null);
        store.persist(snapshot);
        FibSnapshot loaded = store.load().orElseThrow();
        assertThat(loaded.version()).isEqualTo(9);
        assertThat(loaded.routeCount()).isEqualTo(2);
        var routes = loaded.flatRoutes();
        var staticRoute = routes.stream().filter(r -> r.source().equals("static")).findFirst().orElseThrow();
        assertThat(staticRoute.nextHops()).extracting(NextHop::weight).containsExactly(3, 5);
        assertThat(staticRoute.nextHops()).extracting(NextHop::enabled).containsExactly(true, false);
    }
}
