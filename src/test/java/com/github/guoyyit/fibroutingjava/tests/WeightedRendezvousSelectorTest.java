package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.fib.WeightedRendezvousSelector;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedRendezvousSelectorTest {

    private final WeightedRendezvousSelector selector = new WeightedRendezvousSelector();

    private NextHop hop(String id, int weight) {
        return new NextHop(id, "10.0.0." + id, weight, true);
    }

    @Test
    void deterministicAndReproducible() {
        List<NextHop> hops = List.of(hop("1", 1), hop("2", 1), hop("3", 1));
        NextHop first = selector.select(hops, "flow-42");
        for (int i = 0; i < 100; i++) {
            assertEquals(first, selector.select(hops, "flow-42"));
        }
        // 候选输入顺序不影响结果
        List<NextHop> shuffled = List.of(hop("3", 1), hop("1", 1), hop("2", 1));
        assertEquals(first, selector.select(shuffled, "flow-42"));
    }

    @Test
    void emptyCandidatesYieldsNull() {
        assertNull(selector.select(List.of(), "flow"));
    }

    @Test
    void equalWeightSpreadsEvenlyAsEcmp() {
        List<NextHop> hops = List.of(hop("1", 1), hop("2", 1), hop("3", 1), hop("4", 1));
        Map<String, Integer> counts = count(hops, 20_000);
        for (int v : counts.values()) {
            // 每路期望 5000，允许较大统计容差
            assertTrue(v > 4_000 && v < 6_000, "ecmp counts=" + counts);
        }
    }

    @Test
    void weightedDistributionMatchesWeights() {
        List<NextHop> hops = List.of(hop("1", 1), hop("2", 3), hop("3", 6));
        Map<String, Integer> counts = count(hops, 50_000);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        double p1 = counts.get("1") / (double) total;
        double p2 = counts.get("2") / (double) total;
        double p3 = counts.get("3") / (double) total;
        // 期望 0.1 / 0.3 / 0.6，容差 ±0.025
        assertTrue(Math.abs(p1 - 0.10) < 0.025, "p1=" + p1);
        assertTrue(Math.abs(p2 - 0.30) < 0.025, "p2=" + p2);
        assertTrue(Math.abs(p3 - 0.60) < 0.025, "p3=" + p3);
    }

    @Test
    void removingOneHopOnlyMovesItsFlows() {
        List<NextHop> full = List.of(hop("1", 1), hop("2", 1), hop("3", 1), hop("4", 1));
        List<NextHop> removed = List.of(hop("1", 1), hop("2", 1), hop("4", 1));
        int moved = 0;
        int unchangedTotal = 0;
        for (int i = 0; i < 10_000; i++) {
            String flow = "f" + i;
            NextHop a = selector.select(full, flow);
            NextHop b = selector.select(removed, flow);
            if (!a.id().equals("3")) {
                // 原本不走被删节点的流，删除后也不应改投到它原来之外的“额外”变动：
                // 关键断言——没有流会同时映射到两个存活节点（无重复流量）
                unchangedTotal++;
                // 若原命中是存活节点，结果应保持
                if (!a.id().equals("3") && !a.id().equals(b.id())) {
                    // 仅当原本命中的就是被删除节点才允许变化，这里 a 存活 => 必须不变
                    // a 为存活(1/2/4) 时必须保持
                    moved++;
                }
            }
        }
        // a 存活的所有流结果保持 => moved 必须为 0
        assertEquals(0, moved, "surviving hops' flows must not move");
        assertTrue(unchangedTotal > 0);
    }

    private Map<String, Integer> count(List<NextHop> hops, int flows) {
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < flows; i++) {
            NextHop picked = selector.select(hops, "flow-" + i);
            counts.merge(picked.id(), 1, Integer::sum);
        }
        return counts;
    }
}
