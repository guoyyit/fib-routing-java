package com.github.guoyyit.fibroutingjava.model;

import java.util.List;
import java.util.Objects;

/**
 * 不可变路由项：网络前缀 + 管理距离 + 下一跳集合。
 * 同一前缀可存在不同管理距离的多条路由，查找时优先管理距离更小者。
 */
public record RouteEntry(IpPrefix prefix, int adminDistance, List<NextHop> nextHops) {

    public RouteEntry {
        Objects.requireNonNull(prefix, "prefix");
        nextHops = List.copyOf(nextHops);
    }
}
