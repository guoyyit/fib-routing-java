package com.github.guoyyit.fibroutingjava.validation;

import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.model.RouteErrorCode;
import com.github.guoyyit.fibroutingjava.model.RouteValidationException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural and local-topology validation for a single route entry.
 *
 * <p>Cross-prefix topology (overlap/shadow against other installed routes, or
 * aggregation components) is enforced by the commit logic in {@code FibTable}
 * and by {@code RouteAggregator}; this validator covers only rules intrinsic to
 * one entry plus the table capacity.
 */
@Component
public final class RouteValidator {

    /** Administrative distances accepted from static/configured sources. */
    public static final int MIN_ADMIN_DISTANCE = 1;
    public static final int MAX_ADMIN_DISTANCE = 255;

    /**
     * Validates a candidate entry's intrinsic invariants: admin distance, hop
     * list, weights, family consistency and a self-contained forwarding loop.
     */
    public void validate(RouteEntry entry) {
        if (entry == null) {
            throw new RouteValidationException(RouteErrorCode.MALFORMED_PREFIX, "route entry is required");
        }
        if (entry.adminDistance() < MIN_ADMIN_DISTANCE || entry.adminDistance() > MAX_ADMIN_DISTANCE) {
            throw new RouteValidationException(RouteErrorCode.INVALID_ADMIN_DISTANCE,
                    "admin distance must be within [" + MIN_ADMIN_DISTANCE + ","
                            + MAX_ADMIN_DISTANCE + "]: " + entry.adminDistance());
        }
        List<NextHop> hops = entry.nextHops();
        if (hops.isEmpty()) {
            throw new RouteValidationException(RouteErrorCode.EMPTY_NEXT_HOPS,
                    "route " + entry.prefix() + " needs at least one next hop");
        }
        Set<String> seen = new HashSet<>();
        for (NextHop hop : hops) {
            if (!seen.add(hop.id())) {
                throw new RouteValidationException(RouteErrorCode.DUPLICATE_NEXT_HOP_ID,
                        "duplicate next hop id '" + hop.id() + "' in route " + entry.prefix());
            }
            if (hop.weight() < 1) {
                throw new RouteValidationException(RouteErrorCode.INVALID_WEIGHT,
                        "next hop '" + hop.id() + "' weight must be positive: " + hop.weight());
            }
            if (hop.address().isIpv4() != entry.prefix().isIpv4()) {
                throw new RouteValidationException(RouteErrorCode.PREFIX_FAMILY_CONFLICT,
                        "next hop " + hop.id() + " address family does not match prefix "
                                + entry.prefix());
            }
            // A hop pointing back inside the route's own prefix is a forwarding loop.
            if (entry.prefix().contains(hop.address())) {
                throw new RouteValidationException(RouteErrorCode.LOOP_DETECTED,
                        "next hop " + hop.id() + " (" + hop.address()
                                + ") lies inside its own route prefix " + entry.prefix());
            }
        }
    }

    /** Rejects a change-set that would push the table past its configured capacity. */
    public void checkCapacity(int nextTableSize, int maxRoutes) {
        if (nextTableSize > maxRoutes) {
            throw new RouteValidationException(RouteErrorCode.TABLE_LIMIT_EXCEEDED,
                    "route table capacity " + maxRoutes + " exceeded (" + nextTableSize + " entries)");
        }
    }
}
