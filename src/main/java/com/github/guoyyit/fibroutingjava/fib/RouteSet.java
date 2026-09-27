package com.github.guoyyit.fibroutingjava.fib;

import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.IpVersion;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.trie.PrefixTrie;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 一个版本内的路由集合：IPv4 / IPv6 各一棵 LPM 树。
 * 树节点负载为该前缀上“按管理距离升序”排列的路由候选列表。
 */
public final class RouteSet {

    private final PrefixTrie<List<RouteEntry>> v4;
    private final PrefixTrie<List<RouteEntry>> v6;

    public RouteSet(PrefixTrie<List<RouteEntry>> v4, PrefixTrie<List<RouteEntry>> v6) {
        this.v4 = v4;
        this.v6 = v6;
    }

    /**
     * 返回目的地址的全部候选路由：
     * 外层按前缀长度从长到短，同前缀内按管理距离从小到大。
     * 调用方按此顺序依次尝试，首个存在“可用下一跳”的候选即胜出，
     * 从而实现“最长且满足约束（仍有可用下一跳）”的选择。
     */
    public List<RouteEntry> matchCandidates(byte[] destination, IpVersion version) {
        PrefixTrie<List<RouteEntry>> trie = version == IpVersion.V4 ? v4 : v6;
        List<List<RouteEntry>> byPrefix = trie.longestPrefixMatch(destination);
        List<RouteEntry> flat = new ArrayList<>();
        for (List<RouteEntry> samePrefix : byPrefix) {
            flat.addAll(samePrefix); // 同前缀列表在写入时已按 adminDistance 升序
        }
        return flat;
    }

    /**
     * 路由清单由快照在构建时维护（前缀树不提供全量枚举），
     * 请直接使用 {@code FibSnapshot#allRoutes()}。
     */
    public Collection<RouteEntry> allRoutes() {
        throw new UnsupportedOperationException("use FibSnapshot.allRoutes()");
    }

    public RouteEntry get(IpPrefix prefix, int adminDistance) {
        PrefixTrie<List<RouteEntry>> trie = prefix.version() == IpVersion.V4 ? v4 : v6;
        if (!trie.contains(prefix.addressBytes(), prefix.prefixLength())) {
            return null;
        }
        for (List<RouteEntry> candidates : trie.longestPrefixMatch(prefix.addressBytes())) {
            // 该前缀自身一定是其地址的命中之一
            for (RouteEntry entry : candidates) {
                if (entry.prefix().equals(prefix) && entry.adminDistance() == adminDistance) {
                    return entry;
                }
            }
        }
        return null;
    }

    public int routeCount() {
        return v4.size() + v6.size();
    }
}
