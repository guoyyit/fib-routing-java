package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.aggregate.RouteAggregator;
import com.github.guoyyit.fibroutingjava.model.AggregationRequest;
import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteErrorCode;
import com.github.guoyyit.fibroutingjava.model.RouteValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Summarization math and distinguishable rejection reasons. */
class AggregationValidationTest {

    private final RouteAggregator aggregator = new RouteAggregator();

    private static NextHop hop(String id, String address) {
        return new NextHop(id, IpAddress.parse(address), 1, true);
    }

    private AggregationRequest request(List<String> components, NextHop... hops) {
        return new AggregationRequest(components, List.of(hops), "static", 10);
    }

    @Test
    void summarizesTightestCidrV4() {
        assertThat(aggregator.summarize(request(
                List.of("10.0.0.0/24", "10.0.1.0/24"), hop("h", "192.0.2.1"))))
                .hasToString("10.0.0.0/23");
        assertThat(aggregator.summarize(request(
                List.of("10.0.0.0/24", "10.0.3.0/24"), hop("h", "192.0.2.1"))))
                .hasToString("10.0.0.0/22");
        // host bits get normalized, single component stays itself
        assertThat(aggregator.summarize(request(
                List.of("10.0.0.128/25"), hop("h", "192.0.2.1"))))
                .hasToString("10.0.0.128/25");
    }

    @Test
    void summarizesTightestCidrV6() {
        assertThat(aggregator.summarize(request(
                List.of("2001:db8::/48", "2001:db8:1::/48"), hop("h", "2001:db9::1"))))
                .hasToString("2001:db8::/47");
    }

    @Test
    void duplicateComponentsAreRejected() {
        assertThatThrownBy(() -> aggregator.summarize(request(
                List.of("10.0.0.0/24", "10.0.0.0/24"), hop("h", "192.0.2.1"))))
                .isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.DUPLICATE_COMPONENT);
    }

    @Test
    void overlappingComponentsAreRejected() {
        assertThatThrownBy(() -> aggregator.summarize(request(
                List.of("10.0.0.0/24", "10.0.0.128/25"), hop("h", "192.0.2.1"))))
                .isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.OVERLAP_DETECTED);
    }

    @Test
    void mixedFamiliesAreRejected() {
        assertThatThrownBy(() -> aggregator.summarize(request(
                List.of("10.0.0.0/24", "2001:db8::/48"), hop("h", "192.0.2.1"))))
                .isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.PREFIX_FAMILY_CONFLICT);
    }

    @Test
    void nextHopInsideAggregateIsALoop() {
        assertThatThrownBy(() -> aggregator.summarize(request(
                List.of("10.0.0.0/24", "10.0.1.0/24"), hop("h", "10.0.0.7"))))
                .isInstanceOf(RouteValidationException.class)
                .extracting(ex -> ((RouteValidationException) ex).errorCode())
                .isEqualTo(RouteErrorCode.LOOP_DETECTED);
    }

    @Test
    void overlapHelperOnlyFlagsPartialOverlap() {
        // In CIDR, aligned blocks are either disjoint or nested; there is no
        // "partial" overlap for a single pair. The helper therefore stays false
        // for both containment (nested) and the sibling/disjoint cases.
        assertThat(RouteAggregator.overlap(IpPrefix.parse("10.0.0.0/24"),
                IpPrefix.parse("10.0.0.128/25"))).isFalse();
        assertThat(RouteAggregator.overlap(IpPrefix.parse("10.0.0.0/25"),
                IpPrefix.parse("10.0.0.128/25"))).isFalse();
        assertThat(RouteAggregator.overlap(IpPrefix.parse("10.0.0.0/24"),
                IpPrefix.parse("10.0.1.0/24"))).isFalse();
    }
}
