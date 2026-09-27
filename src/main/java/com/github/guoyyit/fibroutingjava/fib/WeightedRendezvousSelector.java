package com.github.guoyyit.fibroutingjava.fib;

import com.github.guoyyit.fibroutingjava.model.NextHop;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 加权 Rendezvous（HRW）选择器。
 *
 * <p>对每个候选下一跳 h 与流标识 f：
 * <pre>score(h, f) = -log(r) / weight(h)</pre>
 * 其中 r ∈ (0,1) 由 (f, h.id) 经确定性 64 位哈希（SplitMix64 风格）派生，
 * 取得分最高（最小）者。这是加权 Rendezvous 的经典“最小密钥”形式：
 * -log(r) 服从速率为 1 的指数分布，除以权重后 P(被选) 正比于权重。</p>
 * <ul>
 *   <li>等权候选被选概率相等（ECMP）；</li>
 *   <li>不等权时被选比例严格正比于权重；</li>
 *   <li>同一 (候选集合, flowKey) 永远得到同一结果（确定性、可复现）；</li>
 *   <li>某下一跳失效时，仅原本映射到它的流改投，其余流不变，
 *       因此切换不产生重复流量；</li>
 *   <li>候选集合以 id 排序后逐个比较，得分完全相同时也有稳定平局次序。</li>
 * </ul>
 * 调用方负责保证候选都已 enabled 且当前健康，禁用/失效下一跳不得传入。</p>
 */
@Component
public class WeightedRendezvousSelector implements NextHopSelector {

    @Override
    public NextHop select(List<NextHop> candidates, String flowKey) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String key = flowKey == null ? "" : flowKey;
        NextHop best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (NextHop hop : candidates) {
            long h = hash(key, hop.id());
            double r = toUnitInterval(h);
            // -log(r)/weight：权重越大得分越小越容易胜出；用 StrictMath 保证跨 JVM 可复现
            double score = -StrictMath.log(r) / hop.weight();
            if (score < bestScore
                    || (score == bestScore && best != null && hop.id().compareTo(best.id()) < 0)) {
                bestScore = score;
                best = hop;
            }
        }
        return best;
    }

    /** 将 64 位哈希均匀映射到 (0,1) 开区间，避免 log(0)。 */
    private static double toUnitInterval(long h) {
        // 取无符号值 [0, 2^64-1]，偏移 0.5 并除以 2^64，再钳制远离端点
        double unsigned = (double) (h >>> 1) * 2.0 + (h & 1L);
        double v = (unsigned + 0.5d) / 18446744073709551616.0d;
        if (v <= 0.0d) {
            return Double.MIN_VALUE;
        }
        if (v >= 1.0d) {
            return Math.nextDown(1.0d);
        }
        return v;
    }

    /**
     * 对 "flowKey + NUL + nextHopId" 字节序列做确定性 64 位混合。
     * 先以 FNV-1a 折叠为种子，再经 SplitMix64 finalizer 充分雪崩。
     */
    private static long hash(String flowKey, String nextHopId) {
        byte[] a = flowKey.getBytes(StandardCharsets.UTF_8);
        byte[] b = nextHopId.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte x : a) {
            hash = fnvStep(hash, x);
        }
        hash = fnvStep(hash, (byte) 0);
        for (byte x : b) {
            hash = fnvStep(hash, x);
        }
        return splitMix64(hash);
    }

    private static long fnvStep(long hash, byte x) {
        hash ^= x & 0xffL;
        hash *= 0x100000001b3L;
        return hash;
    }

    private static long splitMix64(long z) {
        z += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
