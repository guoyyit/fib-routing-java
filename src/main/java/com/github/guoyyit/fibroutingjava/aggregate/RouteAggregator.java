package com.github.guoyyit.fibroutingjava.aggregate;

import com.github.guoyyit.fibroutingjava.model.AggregationRequest;
import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteErrorCode;
import com.github.guoyyit.fibroutingjava.model.RouteValidationException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds summary (aggregate) prefixes and validates the component set.
 *
 * <p>The aggregate is the tightest CIDR block that covers every component: the
 * common leading bits of the component network addresses rounded down to a
 * prefix length. Components must be one family, distinct, pairwise
 * non-overlapping, and none of the aggregate's next hops may fall inside the
 * aggregate block (that would create a loop once the aggregate is installed).
 */
@Component
public final class RouteAggregator {

    /** Validates the request and returns the tightest CIDR covering every component. */
    public IpPrefix summarize(AggregationRequest request) {
        if (request == null || request.componentPrefixes() == null
                || request.componentPrefixes().isEmpty()) {
            throw new RouteValidationException(RouteErrorCode.MALFORMED_PREFIX,
                    "aggregation requires at least one component prefix");
        }
        List<IpPrefix> parsed = request.componentPrefixes().stream().map(IpPrefix::parse).sorted((a, b) -> {
            int byLen = Integer.compare(a.prefixLength(), b.prefixLength());
            return byLen != 0 ? byLen : a.networkAddress().canonicalText()
                    .compareTo(b.networkAddress().canonicalText());
        }).toList();

        Set<IpPrefix> unique = new HashSet<>();
        for (IpPrefix p : parsed) {
            if (!unique.add(p)) {
                throw new RouteValidationException(RouteErrorCode.DUPLICATE_COMPONENT,
                        "duplicate aggregation component: " + p);
            }
        }
        boolean ipv4 = parsed.get(0).isIpv4();
        for (IpPrefix p : parsed) {
            if (p.isIpv4() != ipv4) {
                throw new RouteValidationException(RouteErrorCode.PREFIX_FAMILY_CONFLICT,
                        "cannot aggregate IPv4 and IPv6 components together: " + p);
            }
        }
        // Sorted shortest first, each later component must not fall in an earlier block.
        for (int i = 0; i < parsed.size(); i++) {
            for (int j = i + 1; j < parsed.size(); j++) {
                IpPrefix broader = parsed.get(i);
                IpPrefix narrower = parsed.get(j);
                if (broader.contains(narrower)) {
                    throw new RouteValidationException(RouteErrorCode.OVERLAP_DETECTED,
                            "aggregation components overlap: " + narrower + " is inside " + broader);
                }
            }
        }

        IpPrefix aggregate = coveringCidr(parsed, ipv4);

        if (request.nextHops() != null) {
            for (NextHop hop : request.nextHops()) {
                if (hop.address().isIpv4() != ipv4) {
                    throw new RouteValidationException(RouteErrorCode.PREFIX_FAMILY_CONFLICT,
                            "aggregate next hop " + hop.id() + " family mismatch");
                }
                if (aggregate.contains(hop.address())) {
                    throw new RouteValidationException(RouteErrorCode.LOOP_DETECTED,
                            "aggregate next hop " + hop.id() + " (" + hop.address()
                                    + ") lies inside aggregate " + aggregate);
                }
            }
        }
        return aggregate;
    }

    /** Tightest aligned CIDR block covering every component prefix. */
    private static IpPrefix coveringCidr(List<IpPrefix> prefixes, boolean ipv4) {
        int maxBits = ipv4 ? 32 : 128;
        byte[] lo = prefixes.get(0).networkAddress().copyBytes();
        byte[] hi = broadcast(prefixes.get(0));
        for (IpPrefix p : prefixes) {
            byte[] net = p.networkAddress().copyBytes();
            byte[] end = broadcast(p);
            if (compareBytes(net, lo) < 0) {
                lo = net;
            }
            if (compareBytes(end, hi) > 0) {
                hi = end;
            }
        }
        int commonBits = 0;
        while (commonBits < maxBits && bitAt(lo, commonBits) == bitAt(hi, commonBits)) {
            commonBits++;
        }
        byte[] masked = lo.clone();
        for (int bit = commonBits; bit < masked.length * 8; bit++) {
            masked[bit >> 3] &= (byte) ~(0x80 >> (bit & 7));
        }
        return IpPrefix.parse(IpAddress.fromBytes(masked).canonicalText() + "/" + commonBits);
    }

    /** Last address (all host bits set) of a prefix. */
    private static byte[] broadcast(IpPrefix prefix) {
        byte[] raw = prefix.networkAddress().copyBytes();
        for (int bit = prefix.prefixLength(); bit < raw.length * 8; bit++) {
            raw[bit >> 3] |= (byte) (0x80 >> (bit & 7));
        }
        return raw;
    }

    private static boolean bitAt(byte[] bytes, int bitIndex) {
        return (bytes[bitIndex >> 3] & (0x80 >> (bitIndex & 7))) != 0;
    }

    private static int compareBytes(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) {
            int cmp = Integer.compareUnsigned(a[i] & 0xFF, b[i] & 0xFF);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** True when the two prefixes overlap but neither contains the other. */
    public static boolean overlap(IpPrefix a, IpPrefix b) {
        if (a.isIpv4() != b.isIpv4()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return false;
        }
        int minLen = Math.min(a.prefixLength(), b.prefixLength());
        byte[] x = a.networkAddress().copyBytes();
        byte[] y = b.networkAddress().copyBytes();
        for (int bit = 0; bit < minLen; bit++) {
            if (bitAt(x, bit) != bitAt(y, bit)) {
                return false;
            }
        }
        return true;
    }

    /** True when every component text parses to the same address family. */
    public boolean sameFamily(List<String> components) {
        if (components == null || components.isEmpty()) {
            return false;
        }
        boolean ipv4 = IpPrefix.parse(components.get(0)).isIpv4();
        for (String c : components) {
            if (IpPrefix.parse(c).isIpv4() != ipv4) {
                return false;
            }
        }
        return true;
    }
}
