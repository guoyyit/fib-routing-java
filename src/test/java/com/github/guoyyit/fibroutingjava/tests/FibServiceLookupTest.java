package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.fib.FibService;
import com.github.guoyyit.fibroutingjava.fib.FibSnapshot;
import com.github.guoyyit.fibroutingjava.fib.LookupControl;
import com.github.guoyyit.fibroutingjava.fib.WeightedRendezvousSelector;
import com.github.guoyyit.fibroutingjava.fib.RouteValidator;
import com.github.guoyyit.fibroutingjava.health.HealthRegistry;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupOutcome;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.persistence.FibPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FibServiceLookupTest {

    private Path tmpFile;
    private FibProperties props;
    private FibService fib;

    @BeforeEach
    void setUp() throws Exception {
        tmpFile = Files.createTempFile("fib-test-", ".json");
        Files.delete(tmpFile);
        props = new FibProperties();
        props.setPersistenceFile(tmpFile.toString());
        props.setRestoreOnStart(true);
        HealthRegistry health = new HealthRegistry();
        fib = new FibService(props, new WeightedRendezvousSelector(),
                new RouteValidator(), health, new FibPersistenceService(props));
        invokeRestore();
    }

    private void invokeRestore() {
        // @PostConstruct 在手动 new 时不会触发，反射调用一次
        try {
            var m = FibService.class.getDeclaredMethod("restore");
            m.setAccessible(true);
            m.invoke(fib);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tmpFile);
    }

    private NextHop nh(String id, String addr, int weight) {
        return new NextHop(id, addr, weight, true);
    }

    private void putRoute(String cidr, int distance, NextHop... hops) {
        fib.upsertAndCommit(new RouteEntry(IpPrefix.parse(cidr), distance, List.of(hops)));
    }

    @Test
    void longestPrefixWinsAcrossStackedPrefixes() {
        putRoute("10.0.0.0/8", 0, nh("a", "10.0.0.1", 1));
        putRoute("10.1.0.0/16", 0, nh("b", "10.1.0.1", 1));
        putRoute("10.1.2.0/24", 0, nh("c", "10.1.2.1", 1));

        assertEquals("c", fib.lookup("10.1.2.3", "f1").selectedNextHop().id());
        assertEquals("b", fib.lookup("10.1.9.3", "f1").selectedNextHop().id());
        assertEquals("a", fib.lookup("10.9.9.9", "f1").selectedNextHop().id());
    }

    @Test
    void lowerAdminDistanceWinsOnSamePrefix() {
        putRoute("10.0.0.0/8", 10, nh("backup", "10.0.0.9", 1));
        putRoute("10.0.0.0/8", 5, nh("primary", "10.0.0.1", 1));
        LookupResult r = fib.lookup("10.1.2.3", "f");
        assertEquals("primary", r.selectedNextHop().id());
        assertEquals(5, r.adminDistance());
    }

    @Test
    void fallsBackToShorterPrefixWhenSpecificHasNoUsableNextHop() {
        putRoute("10.1.2.0/24", 0, nh("dead", "10.1.2.7", 1));
        putRoute("10.1.0.0/16", 0, nh("alive", "10.1.0.1", 1));
        fib.health().setHealthy("dead", false);

        LookupResult r = fib.lookup("10.1.2.5", "f");
        assertEquals("alive", r.selectedNextHop().id());
        assertEquals("10.1.0.0/16", r.matchedPrefix().toString());
    }

    @Test
    void unreachableWithoutDefaultRoute() {
        putRoute("10.0.0.0/8", 0, nh("a", "10.0.0.1", 1));
        LookupResult r = fib.lookup("8.8.8.8", "f");
        assertEquals(LookupOutcome.UNREACHABLE, r.outcome());
        assertNull(r.selectedNextHop());
    }

    @Test
    void configurableDefaultRoute() {
        props.setDefaultRouteEnabled(true);
        props.setDefaultRouteNextHopId("gw");
        props.setDefaultRouteNextHopAddress("192.0.2.1");
        // 新服务读取新配置
        fib = new FibService(props, new WeightedRendezvousSelector(),
                new RouteValidator(), new HealthRegistry(), new FibPersistenceService(props));
        invokeRestore();
        putRoute("10.0.0.0/8", 0, nh("a", "10.0.0.1", 1));

        LookupResult r = fib.lookup("8.8.8.8", "f");
        assertEquals(LookupOutcome.DEFAULT_ROUTE, r.outcome());
        assertEquals("gw", r.selectedNextHop().id());
        assertNull(r.matchedPrefix());

        // 默认下一跳失效 => 不可达
        fib.health().setHealthy("gw", false);
        assertEquals(LookupOutcome.UNREACHABLE, fib.lookup("8.8.8.8", "f").outcome());
    }

    @Test
    void ipv6Lookup() {
        putRoute("2001:db8::/32", 0, nh("v6", "2001:db8::1", 1));
        LookupResult r = fib.lookup("2001:db8:1::1", "f");
        assertEquals("v6", r.selectedNextHop().id());
        assertEquals(LookupOutcome.MATCHED, r.outcome());
    }

    @Test
    void disabledNextHopNeverSelected() {
        NextHop disabled = new NextHop("off", "10.0.0.2", 1, false);
        fib.upsertAndCommit(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0,
                List.of(disabled, nh("on", "10.0.0.3", 1))));
        for (int i = 0; i < 50; i++) {
            assertEquals("on", fib.lookup("10.1.1.1", "f" + i).selectedNextHop().id());
        }
    }

    @Test
    void lookupResultCarriesObservedVersion() {
        putRoute("10.0.0.0/8", 0, nh("a", "10.0.0.1", 1));
        FibSnapshot v1 = fib.currentSnapshot();
        LookupResult r = fib.lookup("10.1.1.1", "f");
        assertEquals(v1.version(), r.version());
        putRoute("10.2.0.0/16", 0, nh("b", "10.2.0.1", 1));
        assertEquals(v1.version() + 1, fib.currentSnapshot().version());
        assertNotNull(fib.currentSnapshot().getRoute(IpPrefix.parse("10.2.0.0/16"), 0));
    }
}
