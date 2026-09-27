package com.github.guoyyit.fibroutingjava.model;

import java.util.List;
import java.util.Objects;

/**
 * A route to one prefix, learned from one named source (protocol/static peer).
 *
 * <p>Multiple entries may exist for the same prefix from different sources; the
 * entry with the lowest administrative distance wins. Ties are broken by the
 * lexicographically smaller source id so the choice stays deterministic.
 */
public final class RouteEntry {
    private final IpPrefix prefix;
    private final String source;
    private final int adminDistance;
    private final List<NextHop> nextHops;

    public RouteEntry(IpPrefix prefix, String source, int adminDistance, List<NextHop> nextHops) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.source = Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("route source must not be blank");
        }
        this.adminDistance = adminDistance;
        this.nextHops = List.copyOf(Objects.requireNonNull(nextHops, "nextHops"));
    }

    public IpPrefix prefix() {
        return prefix;
    }

    public String source() {
        return source;
    }

    public int adminDistance() {
        return adminDistance;
    }

    /** Unmodifiable list of configured next hops. */
    public List<NextHop> nextHops() {
        return nextHops;
    }

    public RouteEntry withNextHops(List<NextHop> hops) {
        return new RouteEntry(prefix, source, adminDistance, hops);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RouteEntry that)) {
            return false;
        }
        return Objects.equals(prefix, that.prefix) && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefix, source);
    }

    @Override
    public String toString() {
        return "Route{" + prefix + " from " + source + " ad=" + adminDistance + " hops=" + nextHops + '}';
    }
}
