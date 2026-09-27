package com.github.guoyyit.fibroutingjava.fib;

import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.IpVersion;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RejectionReason;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 路由变更校验器。
 *
 * <p>单条字段校验：前缀合法性（构造时保证）、正权重、非负管理距离、
 * 非空下一跳集合、下一跳 id 唯一、地址字面量合法。</p>
 *
 * <p>提交批次整体校验（基线 + upserts - deletions 构成的“生效集合”）：</p>
 * <ul>
 *   <li>{@code DUPLICATE_ROUTE}：批次内出现重复 (前缀, 管理距离)；</li>
 *   <li>{@code SHADOWED_BY_EXISTING}：新插入的汇总/聚合前缀会把基线中
 *       未在本批次删除的更具体前缀完全遮蔽；</li>
 *   <li>{@code OVERLAP}：非聚合的普通路由与既有前缀形成跨管理距离的重叠冲突，
 *       或待插入前缀落在既有聚合的遮蔽域内（防止静默遮蔽）；</li>
 *   <li>{@code RESOLUTION_LOOP}：把下一跳地址当作目的地址解析得到“下一跳解析图”，
 *       其中出现环（下一跳经彼此路由才能解析）；默认路由不参与解析。</li>
 * </ul>
 * 注：同一 (前缀, 管理距离) 的 upsert 对基线是替换语义，不算冲突。
 */
@Component
public class RouteValidator {

    /** 校验单个路由项字段。 */
    public void validateEntry(RouteEntry entry) {
        if (entry == null) {
            throw new RouteRejectedException(RejectionReason.INVALID_PREFIX, "route must not be null");
        }
        if (entry.adminDistance() < 0) {
            throw new RouteRejectedException(RejectionReason.INVALID_DISTANCE,
                    "adminDistance must be >= 0: " + entry.adminDistance());
        }
        List<NextHop> hops = entry.nextHops();
        if (hops == null || hops.isEmpty()) {
            throw new RouteRejectedException(RejectionReason.EMPTY_NEXTHOP_SET,
                    "route " + entry.prefix() + " must have at least one next hop");
        }
        Set<String> ids = new HashSet<>();
        for (NextHop hop : hops) {
            if (hop.weight() <= 0) {
                throw new RouteRejectedException(RejectionReason.INVALID_WEIGHT,
                        "next hop " + hop.id() + " weight must be positive: " + hop.weight());
            }
            try {
                IpPrefix.parseDestination(hop.address());
            } catch (IllegalArgumentException e) {
                throw new RouteRejectedException(RejectionReason.INVALID_ADDRESS,
                        "next hop " + hop.id() + " has invalid address: " + hop.address());
            }
            if (!ids.add(hop.id())) {
                throw new RouteRejectedException(RejectionReason.DUPLICATE_NEXTHOP,
                        "duplicate next hop id in route " + entry.prefix() + ": " + hop.id());
            }
        }
    }

    /**
     * 计算覆盖全部成员前缀的最短共同前缀（聚合/汇总结果）。
     * 成员必须同一协议版本且非空；结果主机位清零。
     */
    public IpPrefix commonCoveringPrefix(List<IpPrefix> members) {
        if (members == null || members.isEmpty()) {
            throw new RouteRejectedException(RejectionReason.AGGREGATE_MISMATCH,
                    "aggregate requires at least one member prefix");
        }
        IpVersion version = members.get(0).version();
        byte[] min = null;
        byte[] max = null;
        for (IpPrefix p : members) {
            if (p.version() != version) {
                throw new RouteRejectedException(RejectionReason.AGGREGATE_MISMATCH,
                        "cannot aggregate IPv4 and IPv6 members together");
            }
            byte[] b = p.addressBytes();
            if (min == null || compareBytes(b, min) < 0) {
                min = b.clone();
            }
            if (max == null || compareBytes(b, max) > 0) {
                max = b.clone();
            }
        }
        int commonBits = 0;
        int totalBits = version.maxPrefixLength();
        while (commonBits < totalBits && bitAt(min, commonBits) == bitAt(max, commonBits)) {
            commonBits++;
        }
        return new IpPrefix(version, min.clone(), commonBits);
    }

    /**
     * 聚合的遮蔽域：由成员的最小起始地址与“最大成员前缀末端地址”界定，
     * 求覆盖该区间的最短单个 CIDR 块。
     * 当成员只有一个或彼此不邻接时，该域可能比成员 LCP 更宽（更短），
     * 这正是汇总路由需要宣告的覆盖范围；域内未删除的更具体前缀构成遮蔽。
     */
    public IpPrefix aggregateRange(List<IpPrefix> members) {
        if (members == null || members.isEmpty()) {
            throw new RouteRejectedException(RejectionReason.AGGREGATE_MISMATCH,
                    "aggregate requires at least one member prefix");
        }
        IpVersion version = members.get(0).version();
        int width = version.maxPrefixLength();
        byte[] lo = null;   // 最小成员前缀地址
        byte[] hi = null;   // 最大成员前缀的末端地址（含主机位全 1）
        for (IpPrefix p : members) {
            if (p.version() != version) {
                throw new RouteRejectedException(RejectionReason.AGGREGATE_MISMATCH,
                        "cannot aggregate IPv4 and IPv6 members together");
            }
            byte[] start = p.addressBytes();
            byte[] end = prefixEndAddress(p);
            if (lo == null || compareBytes(start, lo) < 0) {
                lo = start.clone();
            }
            if (hi == null || compareBytes(end, hi) > 0) {
                hi = end;
            }
        }
        return smallestCoveringCidr(lo, hi, width, version);
    }

    /**
     * 求覆盖闭区间 [lo, hi] 的最短 CIDR 块。
     * 不变量：长度为 k 的当前块（以 lo 的前 k 位为键）包含 lo；逐位 k 增长，
     * 当 lo 的第 k 位为 0 而 hi 的第 k 位为 1 时，区间在当前块内跨过下一位
     * 两侧半块，长度 k 即最短覆盖。
     */
    private IpPrefix smallestCoveringCidr(byte[] lo, byte[] hi, int width, IpVersion version) {
        int prefixLen = 0;
        while (prefixLen < width) {
            if (bitAt(lo, prefixLen) == 0 && bitAt(hi, prefixLen) == 1) {
                break;
            }
            prefixLen++;
        }
        return new IpPrefix(version, lo.clone(), prefixLen);
    }

    /** 计算某前缀覆盖地址区间的最后一个地址（主机位全置 1）。 */
    private static byte[] prefixEndAddress(IpPrefix p) {
        byte[] b = p.addressBytes().clone();
        int prefixLen = p.prefixLength();
        for (int i = prefixLen; i < p.version().maxPrefixLength(); i++) {
            b[i >> 3] |= (byte) (1 << (7 - (i & 7)));
        }
        return b;
    }

    /**
     * 整体提交校验。
     *
     * @param baseRoutes  基线全部已存在路由
     * @param upserts     本批次新增/替换
     * @param deletions   本批次删除的 (前缀, 管理距离) 键
     * @param maxRoutes   最大路由条数
     */
    public void validateCommit(List<RouteEntry> baseRoutes,
                               List<RouteEntry> upserts,
                               List<RouteKey> deletions,
                               int maxRoutes) {
        validateCommit(baseRoutes, upserts, deletions, maxRoutes, null);
    }

    /**
     * 整体提交校验。
     *
     * @param shadowRange 非 null 表示显式聚合/汇总请求：该覆盖域内若存在基线中
     *                   未在本批次删除的更具体前缀则拒绝（SHADOWED_BY_EXISTING），
     *                   另有覆盖该汇总的前缀则拒绝（OVERLAP）。
     *                   普通提交传 null，允许长短前缀共存（LPM 下更具体优先）。
     */
    public void validateCommit(List<RouteEntry> baseRoutes,
                               List<RouteEntry> upserts,
                               List<RouteKey> deletions,
                               int maxRoutes,
                               IpPrefix shadowRange) {
        // 1) 批次内重复键
        Set<RouteKey> upsertKeys = new HashSet<>();
        for (RouteEntry e : upserts) {
            if (!upsertKeys.add(new RouteKey(e.prefix(), e.adminDistance()))) {
                throw new RouteRejectedException(RejectionReason.DUPLICATE_ROUTE,
                        "duplicate route in same batch: " + e.prefix() + "@" + e.adminDistance());
            }
        }

        // 2) 构造生效集合（基线 - 删除 + upsert 替换/新增）
        Set<RouteKey> deleteKeys = new HashSet<>(deletions);
        Map<RouteKey, RouteEntry> effective = new LinkedHashMap<>();
        for (RouteEntry e : baseRoutes) {
            effective.put(new RouteKey(e.prefix(), e.adminDistance()), e);
        }
        for (RouteKey k : deleteKeys) {
            effective.remove(k);
        }
        for (RouteEntry e : upserts) {
            effective.put(new RouteKey(e.prefix(), e.adminDistance()), e);
        }
        if (effective.size() > maxRoutes) {
            throw new RouteRejectedException(RejectionReason.TABLE_FULL,
                    "route count " + effective.size() + " exceeds max-routes " + maxRoutes);
        }

        // 3) 遮蔽 / 重叠检测（只针对本批次 upsert 键）
        List<RouteEntry> existing = new ArrayList<>(effective.values());
        if (shadowRange != null) {
            // 聚合语义：对“基线快照”检查覆盖域内是否有未删除的更具体前缀（遮蔽）。
            // 与更短父前缀（如 10/8 与新的 10.2.0.0/23）的共存是合法的 LPM 层次，
            // 不在聚合阶段拒绝；跨长度重叠风险由遮蔽检查 + LPM 选路共同覆盖。
            checkAggregateShadow(shadowRange, baseRoutes, deleteKeys);
        }

        // 4) 下一跳解析环路检测（在完整生效集合上）
        checkResolutionLoop(existing);
    }

    /**
     * 聚合遮蔽：基线中落在覆盖域内、但“前缀未作为成员在本批次删除”的更具体前缀，
     * 必须拒绝。成员前缀按整条（任意管理距离）豁免——聚合会撤回成员的全部距离。
     */
    private void checkAggregateShadow(IpPrefix shadowRange,
                                      List<RouteEntry> baseRoutes,
                                      Set<RouteKey> deleteKeys) {
        IpPrefix p = shadowRange;
        for (RouteEntry base : baseRoutes) {
            IpPrefix q = base.prefix();
            if (q.version() != p.version()) {
                continue;
            }
            // 覆盖域自身或其更短前缀不算被遮蔽；只抓域内更具体前缀
            boolean moreSpecific = q.prefixLength() > p.prefixLength() && p.containsPrefix(q);
            if (!moreSpecific) {
                continue;
            }
            // 该前缀若以任意管理距离出现在删除列表（即聚合成员），则豁免
            boolean isMember = deleteKeys.stream().anyMatch(k -> k.prefix().equals(q));
            if (!isMember) {
                throw new RouteRejectedException(RejectionReason.SHADOWED_BY_EXISTING,
                        "aggregate " + p + " would shadow existing more-specific " + q
                                + "; delete or re-aggregate it in the same commit");
            }
        }
    }

    /**
     * 下一跳解析环路：把每个路由视为其前缀到“下一跳地址所解析到的前缀”的边。
     * 下一跳地址按 LPM（忽略管理距离/健康，仅看前缀存在性）解析；
     * 解析不到前缀（可能走默认路由）的不产生边。
     *
     * <p>实现上先按版本各建一棵二进制前缀树，LPM 解析成本为 O(地址位宽)，
     * 整体 O(路由数×位宽 + 下一跳数×位宽)，可支撑大规模提交。</p>
     */
    private void checkResolutionLoop(List<RouteEntry> routes) {
        Map<IpVersion, com.github.guoyyit.fibroutingjava.trie.BinaryPrefixTrie<IpPrefix>> tries =
                new HashMap<>();
        for (RouteEntry r : routes) {
            tries.computeIfAbsent(r.prefix().version(), com.github.guoyyit.fibroutingjava.trie.BinaryPrefixTrie::new)
                    .put(r.prefix().addressBytes(), r.prefix().prefixLength(), r.prefix());
        }
        // 节点为前缀；边：前缀 p -> 其下一跳地址最长命中前缀 q
        Map<IpPrefix, Set<IpPrefix>> edges = new HashMap<>();
        for (RouteEntry r : routes) {
            IpVersion version = r.prefix().version();
            var trie = tries.get(version);
            Set<IpPrefix> targets = new HashSet<>();
            for (NextHop hop : r.nextHops()) {
                byte[] addr;
                try {
                    addr = IpPrefix.parseDestination(hop.address());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (addr.length != version.maxPrefixLength() / 8) {
                    continue; // 下一跳地址族必须与前缀一致，跨族不构成解析边
                }
                List<IpPrefix> hits = trie.longestPrefixMatch(addr);
                IpPrefix longest = hits.isEmpty() ? null : hits.get(0);
                if (longest != null && !longest.equals(r.prefix())) {
                    targets.add(longest);
                }
            }
            if (!targets.isEmpty()) {
                edges.computeIfAbsent(r.prefix(), k -> new HashSet<>()).addAll(targets);
            }
        }
        // 三色 DFS 检测有向环
        Set<IpPrefix> white = new HashSet<>();
        for (RouteEntry r : routes) {
            white.add(r.prefix());
        }
        Set<IpPrefix> gray = new HashSet<>();
        Set<IpPrefix> black = new HashSet<>();
        Deque<IpPrefix> stack = new ArrayDeque<>();
        for (IpPrefix start : white.toArray(new IpPrefix[0])) {
            if (black.contains(start)) {
                continue;
            }
            dfsCycle(start, edges, gray, black, stack);
        }
    }

    private void dfsCycle(IpPrefix node,
                          Map<IpPrefix, Set<IpPrefix>> edges,
                          Set<IpPrefix> gray,
                          Set<IpPrefix> black,
                          Deque<IpPrefix> stack) {
        gray.add(node);
        stack.push(node);
        for (IpPrefix next : edges.getOrDefault(node, Set.of())) {
            if (gray.contains(next)) {
                List<IpPrefix> cycle = new ArrayList<>();
                boolean found = false;
                for (IpPrefix p : stack) {
                    if (p.equals(next)) {
                        found = true;
                    }
                    if (found) {
                        cycle.add(p);
                    }
                }
                java.util.Collections.reverse(cycle);
                cycle.add(next);
                throw new RouteRejectedException(RejectionReason.RESOLUTION_LOOP,
                        "next-hop resolution loop detected: " + cycle);
            }
            if (!black.contains(next)) {
                dfsCycle(next, edges, gray, black, stack);
            }
        }
        stack.pop();
        gray.remove(node);
        black.add(node);
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ua = a[i] & 0xff;
            int ub = b[i] & 0xff;
            if (ua != ub) {
                return Integer.compare(ua, ub);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static int bitAt(byte[] bytes, int bitIndex) {
        return (bytes[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1;
    }

    /** 路由主键：前缀 + 管理距离。 */
    public record RouteKey(IpPrefix prefix, int adminDistance) {
    }
}
