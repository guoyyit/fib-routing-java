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
import com.github.guoyyit.fibroutingjava.model.RouteErrorCode;
import com.github.guoyyit.fibroutingjava.model.RouteValidationException;
import com.github.guoyyit.fibroutingjava.persist.FibStore;
import com.github.guoyyit.fibroutingjava.validation.RouteValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Atomic commit semantics, health failover and validation rejection codes. */
class FibTableAtomicityTest {

    private FibProperties properties;
    private HealthTracker health;
    private FibTable table;

    @TempDir
    Path tempDir;

    private static NextHop hop(String id, String address, int weight) {
        return new NextHop(id, IpAddress.parse(address), weight, true);
    }

    private static RouteEntry route(String prefix, String source, int ad, NextHop... hops) {
        return new RouteEntry(IpPrefix.parse(prefix), source, ad, List.of(hops));
    }

    @BeforeEach
    void setUp() {
        properties = new FibProperties();
        properties.setPersistenceEnabled(true);
        properties.setStorePath(tempDir.resolve("fib.json").toString());
        health = new HealthTracker();
        FibStore store = new FibStore(new ObjectMapper(), properties);
        table = new FibTable(health, new RouteValidator(), store, properties);
        table.recover();
    }

    @Test
    void stagedChangesAreInvisibleUntilCommitAndCommitSwapsAtomically() {
        table.beginTransaction();
        table.stageUpsert(route("10.0.0.0/8", "static", 10, hop("h1", "192.0.2.1", 1)));
        // not committed yet
        var staged = table.lookup(IpAddress.parse("10.0.0.1"), null);
        assertThat(staged.outcome()).isEqualTo(LookupOutcome.UNREACHABLE);

        long version = table.commit();
        assertThat(version).isEqualTo(1);
        var committed = table.lookup(IpAddress.parse("10.0.0.1"), null);
        assertThat(committed.outcome()).isEqualTo(LookupOutcome.FORWARDED);
        assertThat(committed.matchedPrefix()).hasToString("10.0.0.0/8");
        assertThat(committed.version()).isEqualTo(1);
    }

    @Test
    void rollbackDiscardsUncommittedEdits() {
        table.beginTransaction();
        table.stageUpsert(route("10.0.0.0/8", "static", 10, hop("h1", "192.0.2.1", 1)));
        table.rollback();
        assertThat(table.lookup(IpAddress.parse("10.0.0.1"), null).outcome())
                .isEqualTo(LookupOutcome.UNREACHABLE);
        // a new transaction can start afterwards
        table.beginTransaction();
        table.rollback();
    }

    @Test
    void rejectedCommitKeepsPreviousVersionComplete() {
        commitRoutes(route("10.0.0.0/8", "static", 10, hop("h1", "192.0.2.1", 1)));

        table.beginTransaction();
        table.stageUpsert(route("10.1.0.0/16", "static", 10, hop("h2", "192.0.2.2", 1)));
        // invalid route: self loop - staged without judgement, rejected as a whole at commit
        table.stageUpsert(route("10.2.0.0/16", "static", 10, hop("bad", "10.2.0.5", 1)));
        assertThatThrownBy(table::commit)
                .isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.LOOP_DETECTED);
        // live table is unchanged by the rejected batch; transaction may be discarded
        assertThat(table.snapshot().routeCount()).isEqualTo(1);
        table.rollback();

        var stillOld = table.lookup(IpAddress.parse("10.0.0.9"), null);
        assertThat(stillOld.matchedPrefix()).hasToString("10.0.0.0/8");
        // the rejected /16 never appeared; the destination still matches the old /8 only
        var noNewPrefix = table.lookup(IpAddress.parse("10.1.0.1"), null);
        assertThat(noNewPrefix.matchedPrefix()).hasToString("10.0.0.0/8");
        // an address outside the committed prefix stays unreachable
        assertThat(table.lookup(IpAddress.parse("11.1.0.1"), null).outcome())
                .isEqualTo(LookupOutcome.UNREACHABLE);
    }

    @Test
    void updateReplacesNextHopsWithinOneAtomicVersion() {
        commitRoutes(route("10.0.0.0/8", "static", 10, hop("old", "192.0.2.1", 1)));
        commitRoutes(route("10.0.0.0/8", "static", 10, hop("new", "192.0.2.9", 2)));
        var result = table.lookup(IpAddress.parse("10.0.0.7"), null);
        assertThat(result.nextHop().id()).isEqualTo("new");
        assertThat(table.snapshot().version()).isEqualTo(2);
        assertThat(table.snapshot().routeCount()).isEqualTo(1);
    }

    @Test
    void failedNextHopIsImmediatelyBypassedWithoutDuplicateChoice() {
        commitRoutes(route("10.0.0.0/8", "static", 10,
                hop("a", "192.0.2.1", 1), hop("b", "192.0.2.2", 1)));
        // deterministic destination pinned to one hop
        String first = table.lookup(IpAddress.parse("10.5.5.5"), null).nextHop().id();
        health.disable(first);
        var after = table.lookup(IpAddress.parse("10.5.5.5"), null);
        assertThat(after.outcome()).isEqualTo(LookupOutcome.FORWARDED);
        assertThat(after.nextHop().id()).isNotEqualTo(first);
        health.enable(first);
        var restored = table.lookup(IpAddress.parse("10.5.5.5"), null);
        assertThat(restored.nextHop().id()).isEqualTo(first);
    }

    @Test
    void allHopsDownFallsBackToShorterPrefixThenDefault() {
        commitRoutes(
                route("10.1.0.0/16", "static", 10, hop("x", "192.0.2.3", 1)),
                route("10.0.0.0/8", "static", 10, hop("y", "192.0.2.4", 1)));
        health.disable("x");
        var fallback = table.lookup(IpAddress.parse("10.1.9.9"), null);
        assertThat(fallback.matchedPrefix()).hasToString("10.0.0.0/8");
        assertThat(fallback.nextHop().id()).isEqualTo("y");
        health.disable("y");
        assertThat(table.lookup(IpAddress.parse("10.1.9.9"), null).outcome())
                .isEqualTo(LookupOutcome.UNREACHABLE);
    }

    @Test
    void administrativelyDisabledHopNeverParticipates() {
        NextHop disabledHop = new NextHop("down", IpAddress.parse("192.0.2.5"), 1, false);
        commitRoutes(route("10.0.0.0/8", "static", 10, disabledHop));
        assertThat(table.lookup(IpAddress.parse("10.0.0.1"), null).outcome())
                .isEqualTo(LookupOutcome.UNREACHABLE);
    }

    @Test
    void deleteTakesEffectOnCommitAndRejectsMissingPrefix() {
        commitRoutes(route("10.0.0.0/8", "static", 10, hop("h1", "192.0.2.1", 1)));
        assertThatThrownBy(() -> {
            table.beginTransaction();
            table.stageDeletePrefix("11.0.0.0/8");
        }).isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.PREFIX_NOT_FOUND);
        table.rollback();

        table.beginTransaction();
        table.stageDeletePrefix("10.0.0.0/8");
        table.commit();
        assertThat(table.lookup(IpAddress.parse("10.0.0.1"), null).outcome())
                .isEqualTo(LookupOutcome.UNREACHABLE);
    }

    @Test
    void capacityLimitIsEnforced() {
        properties.setMaxRoutes(1);
        commitRoutes(route("10.0.0.0/8", "static", 10, hop("h1", "192.0.2.1", 1)));
        assertThatThrownBy(() ->
                commitRoutes(route("11.0.0.0/8", "static", 10, hop("h2", "192.0.2.2", 1))))
                .isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.TABLE_LIMIT_EXCEEDED);
        assertThat(table.snapshot().routeCount()).isEqualTo(1);
    }

    @Test
    void aggregateFullyCoveredByUnrelatedMoreSpecificsIsRejectedAsShadowed() {
        // four /25 routes (unrelated to the named /24 components) fully cover the /23 space
        commitRoutes(
                route("10.0.0.0/25", "static", 10, hop("q1", "192.0.2.20", 1)),
                route("10.0.0.128/25", "static", 10, hop("q2", "192.0.2.21", 1)),
                route("10.0.1.0/25", "static", 10, hop("q3", "192.0.2.22", 1)),
                route("10.0.1.128/25", "static", 10, hop("q4", "192.0.2.23", 1)));
        var aggregate = route("10.0.0.0/23", "aggregator", 100, hop("agg", "198.51.100.9", 1));
        var namedComponents = List.of(IpPrefix.parse("10.0.0.0/24"), IpPrefix.parse("10.0.1.0/24"));
        assertThatThrownBy(() -> table.commitAggregate(aggregate, namedComponents))
                .isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.SHADOW_DETECTED);
        assertThat(table.snapshot().routeCount()).isEqualTo(4);
    }

    @Test
    void summarizingExistingComponentSpecificsIsNotTreatedAsShadow() {
        // normal aggregation: the named components already exist; the summary is accepted
        commitRoutes(
                route("10.0.0.0/24", "static", 10, hop("c1", "192.0.2.10", 1)),
                route("10.0.1.0/24", "static", 10, hop("c2", "192.0.2.11", 1)));
        var aggregate = route("10.0.0.0/23", "aggregator", 100, hop("agg", "198.51.100.9", 1));
        var components = List.of(IpPrefix.parse("10.0.0.0/24"), IpPrefix.parse("10.0.1.0/24"));
        long version = table.commitAggregate(aggregate, components);
        assertThat(version).isEqualTo(2);
        assertThat(table.snapshot().routeCount()).isEqualTo(3);
    }

    @Test
    void aggregateWithExposedSpaceIsAccepted() {
        commitRoutes(route("10.0.0.0/24", "static", 10, hop("c1", "192.0.2.10", 1)));
        var aggregate = route("10.0.0.0/23", "aggregator", 100, hop("agg", "192.0.2.12", 1));
        // only one of the two /24 blocks exists, so the /23 aggregate is reachable
        var components = List.of(IpPrefix.parse("10.0.0.0/24"));
        long version = table.commitAggregate(aggregate, components);
        assertThat(version).isEqualTo(2);
        var viaAggregate = table.lookup(IpAddress.parse("10.0.1.5"), null);
        assertThat(viaAggregate.matchedPrefix()).hasToString("10.0.0.0/23");
        var viaSpecific = table.lookup(IpAddress.parse("10.0.0.5"), null);
        assertThat(viaSpecific.matchedPrefix()).hasToString("10.0.0.0/24");
    }

    private void commitRoutes(RouteEntry... entries) {
        table.beginTransaction();
        for (RouteEntry entry : entries) {
            table.stageUpsert(entry);
        }
        table.commit();
    }
}
