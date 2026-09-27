package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.engine.FibSnapshot;
import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupOutcome;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.trie.LookupBudget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Longest-prefix-match boundaries for both IPv4 and IPv6. */
class LongestPrefixMatchTest {

    private static NextHop hop(String id, String address) {
        return new NextHop(id, IpAddress.parse(address), 1, true);
    }

    private static RouteEntry route(String prefix, String source, String... hopAddresses) {
        List<NextHop> hops = new java.util.ArrayList<>();
        int i = 0;
        for (String address : hopAddresses) {
            hops.add(hop("h" + i++, address));
        }
        return new RouteEntry(IpPrefix.parse(prefix), source, 10, hops);
    }

    private final FibSnapshot snapshot = FibSnapshot.of(1, List.of(
            route("0.0.0.0/0", "static", "192.0.2.1"),
            route("10.0.0.0/8", "static", "192.0.2.2"),
            route("10.1.0.0/16", "static", "192.0.2.3"),
            route("10.1.2.0/24", "static", "192.0.2.4"),
            route("10.1.2.0/25", "bgp", "192.0.2.5"),
            route("::/0", "static", "2001:db8:ffff::1"),
            route("2001:db8::/32", "static", "2001:db9::1"),
            route("2001:db8:1::/48", "static", "2001:db9:1::1"),
            route("2001:db8:1:2::/64", "static", "2001:db9:2::1")
    ), null, null);

    private FibSnapshot lookup(String ip) {
        return snapshot;
    }

    @Test
    void picksLongestPrefixAtEveryBoundaryV4() {
        assertForwarded("10.1.2.127", "10.1.2.0/25");
        // address just above the /25 still belongs to the /24
        assertForwarded("10.1.2.128", "10.1.2.0/24");
        assertForwarded("10.1.2.255", "10.1.2.0/24");
        assertForwarded("10.1.3.0", "10.1.0.0/16");
        assertForwarded("10.2.0.1", "10.0.0.0/8");
        assertForwarded("11.0.0.1", "0.0.0.0/0");
    }

    @Test
    void longestMatchIsIndependentOfInstallationOrder() {
        FibSnapshot reversed = FibSnapshot.of(2, List.of(
                route("10.1.2.0/25", "bgp", "192.0.2.5"),
                route("10.1.2.0/24", "static", "192.0.2.4"),
                route("10.1.0.0/16", "static", "192.0.2.3"),
                route("10.0.0.0/8", "static", "192.0.2.2"),
                route("0.0.0.0/0", "static", "192.0.2.1")
        ), null, null);
        var result = reversed.lookup(IpAddress.parse("10.1.2.10"),
                LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(result.matchedPrefix()).hasToString("10.1.2.0/25");
    }

    @Test
    void lowerAdminDistanceWinsForSamePrefix() {
        FibSnapshot twoSources = FibSnapshot.of(3, List.of(
                new RouteEntry(IpPrefix.parse("10.0.0.0/8"), "bgp", 20, List.of(hop("b", "192.0.2.9"))),
                new RouteEntry(IpPrefix.parse("10.0.0.0/8"), "connected", 5, List.of(hop("c", "192.0.2.10")))
        ), null, null);
        var result = twoSources.lookup(IpAddress.parse("10.9.9.9"),
                LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(result.source()).isEqualTo("connected");
        assertThat(result.nextHop().id()).isEqualTo("c");
    }

    @Test
    void adminDistanceTieBreaksDeterministicallyBySource() {
        FibSnapshot tie = FibSnapshot.of(4, List.of(
                new RouteEntry(IpPrefix.parse("10.0.0.0/8"), "zzz", 10, List.of(hop("z", "192.0.2.20"))),
                new RouteEntry(IpPrefix.parse("10.0.0.0/8"), "aaa", 10, List.of(hop("a", "192.0.2.21")))
        ), null, null);
        var first = tie.lookup(IpAddress.parse("10.1.1.1"), LookupBudget.ofTimeout(10_000_000L), Set.of());
        var second = tie.lookup(IpAddress.parse("10.1.1.1"), LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(first.source()).isEqualTo("aaa");
        assertThat(second.nextHop().id()).isEqualTo("a");
    }

    @Test
    void picksLongestPrefixAtEveryBoundaryV6() {
        assertForwarded("2001:db8:1:2:ffff::1", "2001:db8:1:2::/64");
        assertForwarded("2001:db8:1:3::1", "2001:db8:1::/48");
        assertForwarded("2001:db8:2::1", "2001:db8::/32");
        assertForwarded("fe80::1", "::/0");
    }

    @Test
    void familiesNeverCrossMatch() {
        var v6Lookup = snapshot.lookup(IpAddress.parse("fe80::1234"),
                LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(v6Lookup.matchedPrefix()).hasToString("::/0");
        var v4Lookup = snapshot.lookup(IpAddress.parse("10.0.0.1"),
                LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(v4Lookup.matchedPrefix()).hasToString("10.0.0.0/8");
    }

    @Test
    void missReturnsUnreachableWithoutDefaultRoute() {
        FibSnapshot noDefaults = FibSnapshot.of(5,
                List.of(route("10.0.0.0/8", "static", "192.0.2.2")), null, null);
        var v4 = noDefaults.lookup(IpAddress.parse("8.8.8.8"),
                LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(v4.outcome()).isEqualTo(LookupOutcome.UNREACHABLE);
        var v6 = noDefaults.lookup(IpAddress.parse("2001:db8::1"),
                LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(v6.outcome()).isEqualTo(LookupOutcome.UNREACHABLE);
    }

    @Test
    void configurableDefaultRouteCatchesFamilyMisses() {
        FibSnapshot withDefaults = FibSnapshot.of(6,
                List.of(route("10.0.0.0/8", "static", "192.0.2.2")),
                hop("dv4", "198.51.100.1"), hop("dv6", "2001:db8:dead::1"));
        var v4 = withDefaults.lookup(IpAddress.parse("8.8.8.8"),
                LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(v4.outcome()).isEqualTo(LookupOutcome.FORWARDED);
        assertThat(v4.nextHop().id()).isEqualTo("dv4");
        var v6 = withDefaults.lookup(IpAddress.parse("fe80::1"),
                LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(v6.nextHop().id()).isEqualTo("dv6");
    }

    @Test
    void fallsThroughPrefixWhoseBestEntryHasNoUsableHop() {
        FibSnapshot s = FibSnapshot.of(7, List.of(
                new RouteEntry(IpPrefix.parse("10.1.0.0/16"), "static", 10,
                        List.of(hop("specific", "192.0.2.30"))),
                new RouteEntry(IpPrefix.parse("10.0.0.0/8"), "static", 10,
                        List.of(hop("broad", "192.0.2.31")))
        ), null, null);
        var result = s.lookup(IpAddress.parse("10.1.9.9"),
                LookupBudget.ofTimeout(10_000_000L), Set.of("specific"));
        assertThat(result.matchedPrefix()).hasToString("10.0.0.0/8");
        assertThat(result.nextHop().id()).isEqualTo("broad");
    }

    private void assertForwarded(String destination, String expectedPrefix) {
        var result = snapshot.lookup(IpAddress.parse(destination),
                LookupBudget.ofTimeout(10_000_000L), Set.of());
        assertThat(result.outcome()).isEqualTo(LookupOutcome.FORWARDED);
        assertThat(result.matchedPrefix()).hasToString(expectedPrefix);
    }
}
