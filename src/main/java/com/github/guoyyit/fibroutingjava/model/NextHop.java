package com.github.guoyyit.fibroutingjava.model;

import java.util.Objects;

/**
 * A candidate next hop for a route.
 *
 * @param id      stable identifier used for health state and deterministic ordering
 * @param address forwarding address (IPv4 or IPv6); the family must match the route prefix
 * @param weight  positive ECMP/WCMP weight; equal weights mean equal-cost multipath
 * @param enabled administrative flag; disabled hops never participate in selection
 */
public final class NextHop {
    private final String id;
    private final IpAddress address;
    private final int weight;
    private final boolean enabled;

    public NextHop(String id, IpAddress address, int weight, boolean enabled) {
        Objects.requireNonNull(id, "next hop id");
        Objects.requireNonNull(address, "next hop address");
        if (id.isBlank()) {
            throw new IllegalArgumentException("next hop id must not be blank");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("next hop weight must be positive: " + weight);
        }
        this.id = id;
        this.address = address;
        this.weight = weight;
        this.enabled = enabled;
    }

    public String id() {
        return id;
    }

    public IpAddress address() {
        return address;
    }

    public int weight() {
        return weight;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Returns a hop with the same identity but a new enabled flag. */
    public NextHop withEnabled(boolean newEnabled) {
        return new NextHop(id, address, weight, newEnabled);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NextHop nextHop)) {
            return false;
        }
        return Objects.equals(id, nextHop.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "NextHop{" + id + " -> " + address.canonicalText()
                + ", weight=" + weight + ", enabled=" + enabled + '}';
    }
}
