package com.github.guoyyit.fibroutingjava.selection;

import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.NextHop;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic equal-cost and weighted-cost multipath selection.
 *
 * <p>Each eligible hop is independently scored with a 64-bit FNV-1a hash over
 * the destination, a route salt and that hop's own identity, then passed
 * through the classical highest-random-weight transform
 * {@code -weight / ln(score)}. The hop with the greatest value wins; id order
 * breaks the vanishingly unlikely ties. Because one hop's score never depends
 * on the other hops, removing a disabled/failed hop changes the choice only for
 * destinations that were mapped to that hop: surviving flows stay put, so a
 * failover neither duplicates nor reroutes unaffected traffic.
 *
 * <p>Equal weights give plain ECMP; higher weights raise selection probability
 * proportionally, giving reproducible weighted ECMP.
 */
public final class MultipathSelector {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long FNV_MASK = 0x7fffffffffffffffL;

    private MultipathSelector() {
    }

    /**
     * Selects one eligible hop for the destination. Callers must pass only
     * administratively enabled, currently healthy hops with positive weights.
     *
     * @param salt version/prefix-derived value keeping different routes independent
     */
    public static Optional<NextHop> select(List<NextHop> eligibleHops, IpAddress destination, long salt) {
        if (eligibleHops == null || eligibleHops.isEmpty()) {
            return Optional.empty();
        }
        NextHop best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (NextHop hop : eligibleHops) {
            double score = weightedScore(hop, destination, salt);
            if (score > bestScore || (score == bestScore && best != null
                    && hop.id().compareTo(best.id()) < 0)) {
                bestScore = score;
                best = hop;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Highest-random-weight score: {@code -w / ln(u)} with {@code u} in (0,1).
     * The selected-hit probability for a hop is proportional to its weight.
     */
    private static double weightedScore(NextHop hop, IpAddress destination, long salt) {
        long h = rawScore(hop, destination, salt);
        double u = (h + 1.0d) / (FNV_MASK + 2.0d);
        return -hop.weight() / Math.log(u);
    }

    private static long rawScore(NextHop hop, IpAddress destination, long salt) {
        long h = FNV_OFFSET_BASIS;
        h = update(h, longBytes(salt));
        h = update(h, hop.id().getBytes(StandardCharsets.UTF_8));
        h = update(h, intBytes(hop.weight()));
        h = update(h, destination.copyBytes());
        // FNV-1a has weak avalanche on its final input bytes; a splitmix64
        // finalizer spreads any one-bit change across all 64 score bits.
        h ^= h >>> 30;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27;
        h *= 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return h & FNV_MASK;
    }

    private static long update(long h, byte[] bytes) {
        for (byte b : bytes) {
            h ^= b & 0xFFL;
            h *= FNV_PRIME;
        }
        return h;
    }

    private static byte[] longBytes(long v) {
        return new byte[] {
                (byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v
        };
    }

    private static byte[] intBytes(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    // Comparator retained for callers/tests wanting a canonical ordering of hops.
    static final Comparator<NextHop> ID_ORDER = Comparator.comparing(NextHop::id);
}
