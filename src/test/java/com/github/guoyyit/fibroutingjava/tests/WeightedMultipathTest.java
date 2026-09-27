package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.selection.MultipathSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic ECMP/WCMP selection and failover stability. */
class WeightedMultipathTest {

    private static final IpAddress GATEWAY_NET = IpAddress.parse("192.0.2.1");

    private static NextHop hop(String id, int weight) {
        return new NextHop(id, GATEWAY_NET, weight, true);
    }

    private static IpAddress destination(long i) {
        return IpAddress.parse("10." + ((i >> 16) & 0xff) + "." + ((i >> 8) & 0xff) + "." + (i & 0xff));
    }

    private Map<String, Integer> distribution(List<NextHop> hops, int samples) {
        Map<String, Integer> counts = new TreeMap<>();
        for (long i = 0; i < samples; i++) {
            String id = MultipathSelector.select(hops, destination(i), 77L).orElseThrow().id();
            counts.merge(id, 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void selectionIsReproducibleRegardlessOfInputOrder() {
        var hopsA = List.of(hop("a", 1), hop("b", 1), hop("c", 1));
        var hopsB = List.of(hop("c", 1), hop("a", 1), hop("b", 1));
        for (long i = 0; i < 500; i++) {
            String first = MultipathSelector.select(hopsA, destination(i), 9L).orElseThrow().id();
            String second = MultipathSelector.select(hopsB, destination(i), 9L).orElseThrow().id();
            assertThat(second).isEqualTo(first);
        }
    }

    @Test
    void equalCostMultipathSplitsRoughlyEvenly() {
        Map<String, Integer> counts = distribution(List.of(hop("a", 1), hop("b", 1)), 20_000);
        double shareA = counts.get("a") / 20_000.0;
        assertThat(shareA).isBetween(0.47, 0.53);
    }

    @Test
    void weightsAreProportional() {
        Map<String, Integer> counts = distribution(
                List.of(hop("a", 1), hop("b", 1), hop("c", 2)), 40_000);
        double a = counts.get("a") / 40_000.0;
        double b = counts.get("b") / 40_000.0;
        double c = counts.get("c") / 40_000.0;
        assertThat(a).isBetween(0.20, 0.30);
        assertThat(b).isBetween(0.20, 0.30);
        assertThat(c).isBetween(0.44, 0.56);
    }

    @Test
    void removingOneHopMovesOnlyThatHopsFlows() {
        var all = List.of(hop("a", 1), hop("b", 1), hop("c", 1));
        var survivors = List.of(hop("a", 1), hop("b", 1));
        int moved = 0;
        int survivingFlows = 0;
        for (long i = 0; i < 20_000; i++) {
            String before = MultipathSelector.select(all, destination(i), 3L).orElseThrow().id();
            if (before.equals("c")) {
                continue;
            }
            survivingFlows++;
            String after = MultipathSelector.select(survivors, destination(i), 3L).orElseThrow().id();
            if (!after.equals(before)) {
                moved++;
            }
        }
        assertThat(survivingFlows).isGreaterThan(0);
        assertThat(moved).isZero();
    }

    @Test
    void emptyEligibleSetSelectsNothing() {
        assertThat(MultipathSelector.select(List.of(), destination(1), 1L)).isEmpty();
    }
}
