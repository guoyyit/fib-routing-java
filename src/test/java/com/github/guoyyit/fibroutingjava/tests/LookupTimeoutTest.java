package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.engine.FibSnapshot;
import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupOutcome;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.trie.LookupBudget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Deadline behaviour: fail-fast, no partial result, interruption treated as timeout. */
class LookupTimeoutTest {

    private static FibSnapshot tableWithDefaults() {
        List<RouteEntry> routes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            routes.add(new RouteEntry(IpPrefix.parse("10." + i + ".0.0/16"), "static", 10, List.of(
                    new NextHop("h" + i, IpAddress.parse("192.0.2." + ((i % 200) + 1)), 1, true))));
        }
        NextHop v4Default = new NextHop("d", IpAddress.parse("198.51.100.1"), 1, true);
        return FibSnapshot.of(1, routes, v4Default, null);
    }

    @Test
    void zeroTimeoutFailsFastWithoutPartialResult() {
        FibSnapshot snapshot = tableWithDefaults();
        LookupResult result = snapshot.lookup(IpAddress.parse("10.5.0.1"),
                LookupBudget.ofTimeout(0L), Set.of());
        assertThat(result.outcome()).isEqualTo(LookupOutcome.TIMEOUT);
        assertThat(result.nextHop()).isNull();
        assertThat(result.matchedPrefix()).isNull();
        assertThat(result.source()).isNull();
        assertThat(result.version()).isEqualTo(1);
    }

    @Test
    void negativeTimeoutAlsoFailsFast() {
        LookupResult result = tableWithDefaults().lookup(IpAddress.parse("10.5.0.1"),
                LookupBudget.ofTimeout(-5L), Set.of());
        assertThat(result.outcome()).isEqualTo(LookupOutcome.TIMEOUT);
    }

    @Test
    void expiredBudgetDuringWalkStillReturnsTimeoutNotDefaultOrUnreachable() {
        FibSnapshot snapshot = tableWithDefaults();
        // a budget that is already expired is checked even when a default route exists,
        // so the answer must never masquerade as FORWARDED or UNREACHABLE
        for (String ip : List.of("10.5.0.1", "8.8.8.8", "10.99.0.1")) {
            LookupResult result = snapshot.lookup(IpAddress.parse(ip),
                    LookupBudget.ofTimeout(0L), Set.of());
            assertThat(result.outcome()).isEqualTo(LookupOutcome.TIMEOUT);
        }
    }

    @Test
    void generousBudgetAlwaysCompletesNormally() {
        FibSnapshot snapshot = tableWithDefaults();
        for (String ip : List.of("10.5.0.1", "10.99.0.1", "8.8.8.8")) {
            LookupResult result = snapshot.lookup(IpAddress.parse(ip),
                    LookupBudget.ofTimeout(60_000_000_000L), Set.of());
            assertThat(result.outcome()).isNotEqualTo(LookupOutcome.TIMEOUT);
        }
    }

    @Test
    void interruptedThreadIsReportedAsTimeoutWithoutThrowing() throws Exception {
        FibSnapshot snapshot = tableWithDefaults();
        LookupResult[] holder = new LookupResult[1];
        Thread worker = new Thread(() -> holder[0] = snapshot.lookup(
                IpAddress.parse("10.5.0.1"), LookupBudget.ofTimeout(60_000_000_000L), Set.of()));
        worker.start();
        worker.interrupt();
        worker.join(5_000);
        assertThat(holder[0]).isNotNull();
        // either it completed just before interruption (FORWARDED) or honored it as TIMEOUT;
        // it must never be UNREACHABLE given the matching route exists
        assertThat(holder[0].outcome()).isIn(LookupOutcome.TIMEOUT, LookupOutcome.FORWARDED);
    }
}
