package com.github.guoyyit.fibroutingjava.engine;

import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.selection.MultipathSelector;
import com.github.guoyyit.fibroutingjava.trie.IpTrie;
import com.github.guoyyit.fibroutingjava.trie.LookupBudget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One immutable, fully committed forwarding-table version.
 *
 * <p>A lookup walks the persistent trie for longest-prefix candidates, then for
 * each prefix (longest first) takes the best source by administrative distance
 * whose hops still include at least one enabled, healthy member. Only then is
 * the multipath choice made. If the best prefix has no usable member, the search
 * falls through to the next shorter prefix, then to the configured family
 * default route, then UNREACHABLE.
 */
public final class FibSnapshot {

    private final long version;
    private final Map<IpPrefix, List<RouteEntry>> routesByPrefix;
    private final IpTrie trie;
    private final NextHop defaultHopV4;
    private final NextHop defaultHopV6;
    private final int routeCount;

    private FibSnapshot(long version,
                        Map<IpPrefix, List<RouteEntry>> routesByPrefix,
                        IpTrie trie,
                        NextHop defaultHopV4,
                        NextHop defaultHopV6,
                        int routeCount) {
        this.version = version;
        this.routesByPrefix = routesByPrefix;
        this.trie = trie;
        this.defaultHopV4 = defaultHopV4;
        this.defaultHopV6 = defaultHopV6;
        this.routeCount = routeCount;
    }

    /** Builds a new immutable snapshot from the full post-commit route set. */
    public static FibSnapshot of(long version,
                                 List<RouteEntry> routes,
                                 NextHop defaultHopV4,
                                 NextHop defaultHopV6) {
        Map<IpPrefix, List<RouteEntry>> grouped = new HashMap<>();
        IpTrie trie = IpTrie.empty();
        int count = 0;
        for (RouteEntry route : routes) {
            grouped.computeIfAbsent(route.prefix(), p -> new ArrayList<>()).add(route);
            trie = trie.insert(route.prefix());
            count++;
        }
        Map<IpPrefix, List<RouteEntry>> immutable = new HashMap<>();
        for (Map.Entry<IpPrefix, List<RouteEntry>> e : grouped.entrySet()) {
            List<RouteEntry> sorted = new ArrayList<>(e.getValue());
            sorted.sort((a, b) -> {
                int byDistance = Integer.compare(a.adminDistance(), b.adminDistance());
                if (byDistance != 0) {
                    return byDistance;
                }
                return a.source().compareTo(b.source());
            });
            immutable.put(e.getKey(), List.copyOf(sorted));
        }
        return new FibSnapshot(version, Collections.unmodifiableMap(immutable), trie,
                defaultHopV4, defaultHopV6, count);
    }

    public static FibSnapshot empty() {
        return new FibSnapshot(0L, Map.of(), IpTrie.empty(), null, null, 0);
    }

    /** Creates a sibling snapshot with a new version and default hops but the same routes. */
    public FibSnapshot withVersionAndDefaults(long newVersion, NextHop v4, NextHop v6) {
        return new FibSnapshot(newVersion, routesByPrefix, trie, v4, v6, routeCount);
    }

    public long version() {
        return version;
    }

    public Map<IpPrefix, List<RouteEntry>> routes() {
        return routesByPrefix;
    }

    public List<RouteEntry> flatRoutes() {
        List<RouteEntry> all = new ArrayList<>(routeCount);
        for (List<RouteEntry> entries : routesByPrefix.values()) {
            all.addAll(entries);
        }
        return List.copyOf(all);
    }

    public IpTrie trie() {
        return trie;
    }

    public int routeCount() {
        return routeCount;
    }

    public NextHop defaultHop(boolean ipv4) {
        return ipv4 ? defaultHopV4 : defaultHopV6;
    }

    /**
     * Longest routed prefix for an address, ignoring hop usability. Used by
     * commit-time topology analysis which reasons about the prefix graph rather
     * than live health. Returns null when nothing routes the address.
     */
    IpPrefix longestRoutedPrefix(IpAddress address) {
        var walk = trie.longestPrefixWalk(address, LookupBudget.ofTimeout(Long.MAX_VALUE / 2));
        if (walk.isEmpty() || walk.get().prefixes().isEmpty()) {
            return null;
        }
        List<IpPrefix> prefixes = walk.get().prefixes();
        return prefixes.get(prefixes.size() - 1);
    }

    /**
     * Atomic lookup against this version. Health status is read exactly once
     * into an immutable set, so the decision can never mix "before" and "after"
     * states of a health change.
     */
    public LookupResult lookup(IpAddress destination, LookupBudget budget, Set<String> disabledHopIds) {
        var walk = trie.longestPrefixWalk(destination, budget);
        if (walk.isEmpty()) {
            return LookupResult.timeout(version);
        }
        List<IpPrefix> matches = walk.get().prefixes();
        for (int i = matches.size() - 1; i >= 0; i--) {
            if (budget.expired()) {
                return LookupResult.timeout(version);
            }
            IpPrefix prefix = matches.get(i);
            for (RouteEntry entry : routesByPrefix.getOrDefault(prefix, List.of())) {
                List<NextHop> eligible = filterEligible(entry.nextHops(), disabledHopIds);
                if (!eligible.isEmpty()) {
                    long salt = version * 1_000_003L
                            ^ prefix.toString().hashCode()
                            ^ entry.source().hashCode();
                    NextHop chosen = MultipathSelector.select(eligible, destination, salt).orElse(null);
                    return LookupResult.forwarded(prefix, entry.source(), chosen, version);
                }
            }
        }
        NextHop familyDefault = destination.isIpv4() ? defaultHopV4 : defaultHopV6;
        if (familyDefault != null
                && familyDefault.enabled()
                && !disabledHopIds.contains(familyDefault.id())) {
            return LookupResult.forwarded(null, "__default__", familyDefault, version);
        }
        return LookupResult.unreachable(version);
    }

    private static List<NextHop> filterEligible(List<NextHop> hops, Set<String> disabledIds) {
        List<NextHop> eligible = new ArrayList<>(hops.size());
        for (NextHop hop : hops) {
            if (hop.enabled() && !disabledIds.contains(hop.id())) {
                eligible.add(hop);
            }
        }
        return eligible;
    }
}
