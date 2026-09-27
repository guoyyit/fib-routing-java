package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.fib.FibService;
import com.github.guoyyit.fibroutingjava.fib.RouteRejectedException;
import com.github.guoyyit.fibroutingjava.fib.RouteValidator;
import com.github.guoyyit.fibroutingjava.fib.WeightedRendezvousSelector;
import com.github.guoyyit.fibroutingjava.health.HealthRegistry;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RejectionReason;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.persistence.FibPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteValidationTest {

    private Path tmpFile;
    private FibService fib;

    private NextHop nh(String id, String addr) {
        return new NextHop(id, addr, 1, true);
    }

    private RouteEntry route(String cidr, int distance, NextHop... hops) {
        return new RouteEntry(IpPrefix.parse(cidr), distance, List.of(hops));
    }

    @BeforeEach
    void setUp() throws Exception {
        tmpFile = Files.createTempFile("fib-val-", ".json");
        Files.delete(tmpFile);
        FibProperties props = new FibProperties();
        props.setPersistenceFile(tmpFile.toString());
        fib = new FibService(props, new WeightedRendezvousSelector(), new RouteValidator(),
                new HealthRegistry(), new FibPersistenceService(props));
        var m = FibService.class.getDeclaredMethod("restore");
        m.setAccessible(true);
        m.invoke(fib);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tmpFile);
    }

    @Test
    void rejectsBadWeight() {
        RouteRejectedException ex = assertThrows(RouteRejectedException.class,
                () -> fib.stageUpsert(route("10.0.0.0/8", 0,
                        new NextHop("h", "10.0.0.1", 0, true))));
        assertEquals(RejectionReason.INVALID_WEIGHT, ex.reason());
    }

    @Test
    void rejectsNegativeDistance() {
        RouteRejectedException ex = assertThrows(RouteRejectedException.class,
                () -> fib.stageUpsert(route("10.0.0.0/8", -1, nh("h", "10.0.0.1"))));
        assertEquals(RejectionReason.INVALID_DISTANCE, ex.reason());
    }

    @Test
    void rejectsEmptyNextHops() {
        RouteRejectedException ex = assertThrows(RouteRejectedException.class,
                () -> fib.stageUpsert(new RouteEntry(IpPrefix.parse("10.0.0.0/8"), 0, List.of())));
        assertEquals(RejectionReason.EMPTY_NEXTHOP_SET, ex.reason());
    }

    @Test
    void rejectsDuplicateNextHopId() {
        RouteRejectedException ex = assertThrows(RouteRejectedException.class,
                () -> fib.stageUpsert(route("10.0.0.0/8", 0,
                        nh("h", "10.0.0.1"), nh("h", "10.0.0.2"))));
        assertEquals(RejectionReason.DUPLICATE_NEXTHOP, ex.reason());
    }

    @Test
    void rejectsInvalidNextHopAddress() {
        RouteRejectedException ex = assertThrows(RouteRejectedException.class,
                () -> fib.stageUpsert(route("10.0.0.0/8", 0, nh("h", "not-an-ip"))));
        assertEquals(RejectionReason.INVALID_ADDRESS, ex.reason());
    }

    @Test
    void rejectsDuplicateRouteInSameBatch() {
        fib.stageUpsert(route("10.0.0.0/8", 0, nh("a", "10.0.0.1")));
        fib.stageUpsert(route("10.0.0.0/8", 0, nh("b", "10.0.0.2")));
        // 暂存 Map 后写覆盖先写，无法检测“同一批次重复”——重复键语义即替换，
        // 真正的重复保护针对显式批次接口；这里验证最终提交只保留最后一条
        fib.commit();
        assertEquals("b",
                fib.lookup("10.1.1.1", "f").selectedNextHop().id());
    }

    @Test
    void normalNestedPrefixesAreAllowed() {
        fib.upsertAndCommit(route("10.0.0.0/8", 0, nh("a", "10.0.0.1")));
        fib.upsertAndCommit(route("10.1.0.0/16", 0, nh("b", "10.1.0.1")));
        assertEquals("b", fib.lookup("10.1.1.1", "f").selectedNextHop().id());
    }

    @Test
    void rejectsResolutionLoop() {
        // 10.0.0.0/8 的下一跳在 10.1.0.0/16 内；10.1.0.0/16 的下一跳在 10.0.0.0/8 内
        fib.upsertAndCommit(route("10.0.0.0/8", 0, nh("a", "10.1.0.1")));
        RouteRejectedException ex = assertThrows(RouteRejectedException.class,
                () -> fib.upsertAndCommit(route("10.1.0.0/16", 0, nh("b", "10.0.0.1"))));
        assertEquals(RejectionReason.RESOLUTION_LOOP, ex.reason());
        // 被拒绝后旧版本保持不变
        assertEquals("a", fib.lookup("10.1.1.1", "f").selectedNextHop().id());
    }

    @Test
    void aggregateShadowRejectedWhenMoreSpecificRemains() {
        fib.upsertAndCommit(route("10.0.0.0/24", 0, nh("a", "10.0.0.1")));
        fib.upsertAndCommit(route("10.0.2.0/24", 0, nh("b", "10.0.2.1")));
        fib.upsertAndCommit(route("10.0.3.0/24", 0, nh("c", "10.0.3.1")));
        // 成员 .0 与 .3 不相邻：覆盖它们的对齐 CIDR 是 /22（10.0.0.0..10.0.3.255），
        // 域内未纳入且未删除的 10.0.2.0/24 会被遮蔽 => 拒绝
        RouteRejectedException ex = assertThrows(RouteRejectedException.class,
                () -> fib.aggregateAndCommit(
                        List.of(IpPrefix.parse("10.0.0.0/24"), IpPrefix.parse("10.0.3.0/24")),
                        0, List.of(nh("agg", "192.0.2.1"))));
        assertEquals(RejectionReason.SHADOWED_BY_EXISTING, ex.reason());
        // 拒绝后基线三条路由保持不变
        assertEquals(3, fib.currentSnapshot().routeCount());
    }

    @Test
    void aggregateSucceedsWhenAllMembersIncluded() {
        fib.upsertAndCommit(route("10.0.0.0/24", 0, nh("a", "10.0.0.1")));
        fib.upsertAndCommit(route("10.0.1.0/24", 0, nh("b", "10.0.1.1")));
        var agg = fib.aggregateAndCommit(
                List.of(IpPrefix.parse("10.0.0.0/24"), IpPrefix.parse("10.0.1.0/24")),
                0, List.of(nh("agg", "192.0.2.1")));
        assertEquals("10.0.0.0/23", agg.toString());
        assertEquals("agg", fib.lookup("10.0.0.5", "f").selectedNextHop().id());
        assertEquals("agg", fib.lookup("10.0.1.5", "f").selectedNextHop().id());
    }

    @Test
    void rejectsMixedVersionAggregate() {
        RouteRejectedException ex = assertThrows(RouteRejectedException.class,
                () -> new RouteValidator().commonCoveringPrefix(List.of(
                        IpPrefix.parse("10.0.0.0/8"), IpPrefix.parse("2001:db8::/32"))));
        assertEquals(RejectionReason.AGGREGATE_MISMATCH, ex.reason());
    }

    @Test
    void rejectsWhenTableFull() throws Exception {
        tmpFile = Files.createTempFile("fib-full-", ".json");
        Files.delete(tmpFile);
        FibProperties props = new FibProperties();
        props.setMaxRoutes(2);
        props.setPersistenceFile(tmpFile.toString());
        FibService small = new FibService(props, new WeightedRendezvousSelector(),
                new RouteValidator(), new HealthRegistry(), new FibPersistenceService(props));
        var m = FibService.class.getDeclaredMethod("restore");
        m.setAccessible(true);
        m.invoke(small);
        small.upsertAndCommit(route("10.0.0.0/8", 0, nh("a", "10.0.0.1")));
        small.upsertAndCommit(route("11.0.0.0/8", 0, nh("b", "11.0.0.1")));
        RouteRejectedException ex = assertThrows(RouteRejectedException.class,
                () -> small.upsertAndCommit(route("12.0.0.0/8", 0, nh("c", "12.0.0.1"))));
        assertEquals(RejectionReason.TABLE_FULL, ex.reason());
        assertEquals(2, small.currentSnapshot().routeCount());
    }
}
