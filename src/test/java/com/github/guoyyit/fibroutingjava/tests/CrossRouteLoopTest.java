package com.github.guoyyit.fibroutingjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.engine.FibTable;
import com.github.guoyyit.fibroutingjava.health.HealthTracker;
import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
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

/** Two-node and self-contained forwarding loops are rejected with a clear code. */
class CrossRouteLoopTest {

    @TempDir
    Path tempDir;

    private FibTable table;

    private static NextHop hop(String id, String address) {
        return new NextHop(id, IpAddress.parse(address), 1, true);
    }

    private static RouteEntry route(String prefix, NextHop... hops) {
        return new RouteEntry(IpPrefix.parse(prefix), "static", 10, List.of(hops));
    }

    @BeforeEach
    void setUp() {
        FibProperties properties = new FibProperties();
        properties.setPersistenceEnabled(false);
        table = new FibTable(new HealthTracker(), new RouteValidator(),
                new FibStore(new ObjectMapper(), properties), properties);
    }

    @Test
    void selfContainedLoopIsRejected() {
        assertThatThrownBy(() -> {
            table.beginTransaction();
            table.stageUpsert(route("10.0.0.0/8", hop("bad", "10.1.2.3")));
            table.commit();
        }).isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.LOOP_DETECTED);
        assertThat(table.snapshot().routeCount()).isZero();
    }

    @Test
    void twoNodeCrossRouteLoopIsRejected() {
        // 10.0.0.0/8 forwards via an address inside 11.0.0.0/8 and vice versa.
        assertThatThrownBy(() -> {
            table.beginTransaction();
            table.stageUpsert(route("10.0.0.0/8", hop("to11", "11.0.0.1")));
            table.stageUpsert(route("11.0.0.0/8", hop("to10", "10.0.0.1")));
            table.commit();
        }).isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.LOOP_DETECTED);
        // atomic rejection: neither route became visible
        assertThat(table.snapshot().routeCount()).isZero();
    }

    @Test
    void legitimateGatewayOutsideAllPrefixesIsAccepted() {
        table.beginTransaction();
        table.stageUpsert(route("10.0.0.0/8", hop("gw", "192.0.2.1")));
        table.stageUpsert(route("11.0.0.0/8", hop("gw2", "192.0.2.2")));
        long version = table.commit();
        assertThat(version).isEqualTo(1);
    }

    @Test
    void threeNodeCycleIsRejectedButAChainIsAccepted() {
        // chain 10 -> 11 -> 12 -> external gateway: no cycle, accepted
        table.beginTransaction();
        table.stageUpsert(route("10.0.0.0/8", hop("h", "11.0.0.1")));
        table.stageUpsert(route("11.0.0.0/8", hop("h", "12.0.0.1")));
        table.stageUpsert(route("12.0.0.0/8", hop("h", "192.0.2.1")));
        assertThat(table.commit()).isEqualTo(1);

        // now close the chain back to 10/8: 12 -> 10, forming a 3-node cycle
        table.beginTransaction();
        table.stageUpsert(route("12.0.0.0/8", hop("h", "10.9.9.9")));
        assertThatThrownBy(table::commit)
                .isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.LOOP_DETECTED);
        // previous good version is retained
        assertThat(table.snapshot().version()).isEqualTo(1);
        assertThat(table.snapshot().routeCount()).isEqualTo(3);
    }
}
