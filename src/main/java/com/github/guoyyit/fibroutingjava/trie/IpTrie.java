package com.github.guoyyit.fibroutingjava.trie;

import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistent binary radix trie keyed by prefix bits, with separate subtrees per
 * address family.
 *
 * <p>Splitting the root by family matters because a /0 prefix carries no bits
 * and would otherwise match both IPv4 and IPv6 destinations. Below the family
 * root, walking the prefix bits guarantees every collected prefix contains the
 * destination, so no further family filtering is needed.
 *
 * <p>Each {@link #insert} or {@link #remove} copies only the spine it touches
 * and returns a new trie; a {@link com.github.guoyyit.fibroutingjava.engine.FibSnapshot}
 * holding one instance therefore observes an immutable table for its life.
 */
public final class IpTrie {

    /** Prefixes collected on a destination's bit path, shortest first, longest last. */
    public record TrieMatch(List<IpPrefix> prefixes) {
    }

    private record Node(IpPrefix prefix, Node zero, Node one) {
        boolean terminal() {
            return prefix != null;
        }
    }

    private final Node v4Child;
    private final Node v6Child;
    private final IpPrefix v4Default;
    private final IpPrefix v6Default;
    private final int size;

    private IpTrie(Node v4Child, Node v6Child, IpPrefix v4Default, IpPrefix v6Default, int size) {
        this.v4Child = v4Child;
        this.v6Child = v6Child;
        this.v4Default = v4Default;
        this.v6Default = v6Default;
        this.size = size;
    }

    public static IpTrie empty() {
        return new IpTrie(null, null, null, null, 0);
    }

    /** Returns a new trie containing the prefix; inserting an existing prefix is a no-op. */
    public IpTrie insert(IpPrefix prefix) {
        if (prefix.prefixLength() == 0) {
            if (prefix.isIpv4()) {
                if (v4Default != null) {
                    return this;
                }
                return new IpTrie(v4Child, v6Child, prefix, v6Default, size + 1);
            }
            if (v6Default != null) {
                return this;
            }
            return new IpTrie(v4Child, v6Child, v4Default, prefix, size + 1);
        }
        boolean[] added = {false};
        byte[] raw = prefix.networkAddress().copyBytes();
        if (prefix.isIpv4()) {
            Node rebuilt = insertNode(v4Child, raw, prefix.prefixLength(), prefix, 1, added);
            return new IpTrie(rebuilt, v6Child, v4Default, v6Default, size + (added[0] ? 1 : 0));
        }
        Node rebuilt = insertNode(v6Child, raw, prefix.prefixLength(), prefix, 1, added);
        return new IpTrie(v4Child, rebuilt, v4Default, v6Default, size + (added[0] ? 1 : 0));
    }

    private static Node insertNode(Node current, byte[] raw, int totalBits, IpPrefix value,
                                   int depth, boolean[] added) {
        if (depth == totalBits) {
            if (current != null && current.terminal()) {
                return current;
            }
            added[0] = true;
            return new Node(value, current == null ? null : current.zero(),
                    current == null ? null : current.one());
        }
        boolean bit = (raw[depth >> 3] & (0x80 >> (depth & 7))) != 0;
        if (current == null) {
            added[0] = true;
            return buildPath(raw, totalBits, value, depth);
        }
        Node child = bit ? current.one() : current.zero();
        Node newChild = insertNode(child, raw, totalBits, value, depth + 1, added);
        Node newZero = bit ? current.zero() : newChild;
        Node newOne = bit ? newChild : current.one();
        return new Node(current.prefix(), newZero, newOne);
    }

    /** Creates a fresh chain of nodes from {@code depth} down to the terminal node. */
    private static Node buildPath(byte[] raw, int totalBits, IpPrefix value, int depth) {
        if (depth == totalBits) {
            return new Node(value, null, null);
        }
        boolean bit = (raw[depth >> 3] & (0x80 >> (depth & 7))) != 0;
        Node child = buildPath(raw, totalBits, value, depth + 1);
        return bit ? new Node(null, null, child) : new Node(null, child, null);
    }

    /** Returns a new trie without the prefix; removing an absent prefix is a no-op. */
    public IpTrie remove(IpPrefix prefix) {
        if (prefix.prefixLength() == 0) {
            if (prefix.isIpv4()) {
                if (v4Default == null) {
                    return this;
                }
                return new IpTrie(v4Child, v6Child, null, v6Default, size - 1);
            }
            if (v6Default == null) {
                return this;
            }
            return new IpTrie(v4Child, v6Child, v4Default, null, size - 1);
        }
        boolean[] removed = {false};
        byte[] raw = prefix.networkAddress().copyBytes();
        if (prefix.isIpv4()) {
            Node rebuilt = removeNode(v4Child, raw, prefix.prefixLength(), 1, removed);
            return new IpTrie(rebuilt, v6Child, v4Default, v6Default, size - (removed[0] ? 1 : 0));
        }
        Node rebuilt = removeNode(v6Child, raw, prefix.prefixLength(), 1, removed);
        return new IpTrie(v4Child, rebuilt, v4Default, v6Default, size - (removed[0] ? 1 : 0));
    }

    private static Node removeNode(Node current, byte[] raw, int totalBits, int depth, boolean[] removed) {
        if (current == null) {
            return null;
        }
        if (depth == totalBits) {
            if (!current.terminal()) {
                return current;
            }
            removed[0] = true;
            if (current.zero() == null && current.one() == null) {
                return null;
            }
            return new Node(null, current.zero(), current.one());
        }
        boolean bit = (raw[depth >> 3] & (0x80 >> (depth & 7))) != 0;
        if (bit) {
            Node newOne = removeNode(current.one(), raw, totalBits, depth + 1, removed);
            if (!removed[0]) {
                return current;
            }
            if (current.prefix() == null && current.zero() == null && newOne == null) {
                return null;
            }
            return new Node(current.prefix(), current.zero(), newOne);
        }
        Node newZero = removeNode(current.zero(), raw, totalBits, depth + 1, removed);
        if (!removed[0]) {
            return current;
        }
        if (current.prefix() == null && current.one() == null && newZero == null) {
            return null;
        }
        return new Node(current.prefix(), newZero, current.one());
    }

    public int size() {
        return size;
    }

    /**
     * Walks the destination's bit path from its family root, collecting every
     * stored prefix on it. The list is ordered by prefix length, so the last
     * entry is the longest match. Returns empty if the budget expires, which
     * forces callers to answer TIMEOUT rather than a partially traversed result.
     */
    public Optional<TrieMatch> longestPrefixWalk(IpAddress destination, LookupBudget budget) {
        boolean v4 = destination.isIpv4();
        List<IpPrefix> matches = new ArrayList<>();
        IpPrefix familyDefault = v4 ? v4Default : v6Default;
        if (familyDefault != null) {
            matches.add(familyDefault);
        }
        Node current = v4 ? v4Child : v6Child;
        int totalBits = v4 ? 32 : 128;
        for (int depth = 1; depth <= totalBits; depth++) {
            if (budget.expired()) {
                return Optional.empty();
            }
            if (current == null) {
                break;
            }
            if (current.terminal()) {
                matches.add(current.prefix());
            }
            if (depth == totalBits) {
                break;
            }
            boolean bit = destination.bitAt(depth);
            current = bit ? current.one() : current.zero();
        }
        return Optional.of(new TrieMatch(List.copyOf(matches)));
    }
}
