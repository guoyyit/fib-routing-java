package com.github.guoyyit.fibroutingjava.fib;

import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import java.util.Collection;

/**
 * 转发表的一个不可变版本。一旦发布即只读，进行中的查询只观察某个完整版本，
 * 绝不会看到半套路由或字段级混合状态。
 */
public interface FibSnapshot {

    long version();

    /**
     * 对目的地址执行最长前缀匹配 + 确定性多路径选择。
     * 整个查找绑定在本快照上；下一跳健康状态在选择边界实时校验。
     */
    LookupResult lookup(byte[] destination, String flowKey, LookupControl control);

    /** 以 CIDR 字符串为目标的便捷查找。 */
    LookupResult lookup(String destinationIp, String flowKey, LookupControl control);

    RouteEntry getRoute(IpPrefix prefix, int adminDistance);

    Collection<RouteEntry> allRoutes();

    int routeCount();
}
