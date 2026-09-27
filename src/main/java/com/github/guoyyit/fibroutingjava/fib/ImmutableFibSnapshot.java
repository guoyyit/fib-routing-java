package com.github.guoyyit.fibroutingjava.fib;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.health.HealthRegistry;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.IpVersion;
import com.github.guoyyit.fibroutingjava.model.LookupOutcome;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * 不可变转发表版本。
 *
 * <p>发布后其路由数据（{@link RouteSet} 与路由清单）不再变化，
 * 查询只会绑定到一个快照实例，因此进行中的查询只能观察某一个完整版本。</p>
 *
 * <p>下一跳可用性 = 路由项 {@code enabled} 且 {@link HealthRegistry} 当前健康。
 * 健康平面可独立变化：读取一次健康快照后在整次查找内复用（内部一致），
 * 选择出结果前再做一次实时复检；若在这两步之间下一跳失效，则放弃结果
 * 重新选择，保证绝不返回失效路径。</p>
 */
final class ImmutableFibSnapshot implements FibSnapshot {

    private final long version;
    private final RouteSet routeSet;
    private final List<RouteEntry> routes;
    private final NextHopSelector selector;
    private final HealthRegistry healthRegistry;
    private final NextHop defaultNextHop;

    ImmutableFibSnapshot(long version,
                         RouteSet routeSet,
                         List<RouteEntry> routes,
                         NextHopSelector selector,
                         HealthRegistry healthRegistry,
                         NextHop defaultNextHop) {
        this.version = version;
        this.routeSet = routeSet;
        this.routes = List.copyOf(routes);
        this.selector = selector;
        this.healthRegistry = healthRegistry;
        this.defaultNextHop = defaultNextHop;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public LookupResult lookup(byte[] destination, String flowKey, LookupControl control) {
        control.checkpoint();
        IpVersion ipVersion = IpPrefix.versionOf(destination);
        List<RouteEntry> candidates = routeSet.matchCandidates(destination, ipVersion);
        for (RouteEntry route : candidates) {
            control.checkpoint();
            NextHop chosen = chooseFromRoute(route, flowKey, control);
            if (chosen != null) {
                return new LookupResult(LookupOutcome.MATCHED, route.prefix(),
                        route.adminDistance(), chosen, flowKey, version);
            }
            // 该前缀的最优（管理距离最小）路由若已无可用下一跳，则继续尝试
            // 同前缀次优管理距离，再退化到更短前缀
        }
        return useDefaultOrUnreachable(flowKey);
    }

    @Override
    public LookupResult lookup(String destinationIp, String flowKey, LookupControl control) {
        return lookup(IpPrefix.parseDestination(destinationIp), flowKey, control);
    }

    @Override
    public RouteEntry getRoute(IpPrefix prefix, int adminDistance) {
        return routeSet.get(prefix, adminDistance);
    }

    @Override
    public java.util.Collection<RouteEntry> allRoutes() {
        return routes;
    }

    @Override
    public int routeCount() {
        return routes.size();
    }

    private NextHop chooseFromRoute(RouteEntry route, String flowKey, LookupControl control) {
        // 读取一次健康快照，整次选择复用：选择过程内部状态一致
        var unhealthy = healthRegistry.unhealthySnapshot();
        List<NextHop> usable = new ArrayList<>(route.nextHops().size());
        for (NextHop hop : route.nextHops()) {
            control.checkpoint();
            if (hop.enabled() && !unhealthy.contains(hop.id())) {
                usable.add(hop);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        NextHop picked = selector.select(usable, flowKey);
        // 结果返回前实时复检：若健康恰好在读取后翻转，重选一次仍失败路径
        if (picked == null || !picked.enabled() || !healthRegistry.isHealthy(picked.id())) {
            List<NextHop> rechecked = new ArrayList<>(usable.size());
            for (NextHop hop : route.nextHops()) {
                if (hop.enabled() && healthRegistry.isHealthy(hop.id())) {
                    rechecked.add(hop);
                }
            }
            picked = rechecked.isEmpty() ? null : selector.select(rechecked, flowKey);
            if (picked != null && (!picked.enabled() || !healthRegistry.isHealthy(picked.id()))) {
                return null;
            }
        }
        return picked;
    }

    private LookupResult useDefaultOrUnreachable(String flowKey) {
        if (defaultNextHop != null
                && defaultNextHop.enabled()
                && healthRegistry.isHealthy(defaultNextHop.id())) {
            return new LookupResult(LookupOutcome.DEFAULT_ROUTE, null, null,
                    defaultNextHop, flowKey, version);
        }
        return new LookupResult(LookupOutcome.UNREACHABLE, null, null, null, flowKey, version);
    }
}
