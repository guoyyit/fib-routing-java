package com.github.guoyyit.fibroutingjava.fib;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.health.HealthRegistry;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.IpVersion;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RejectionReason;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.persistence.FibPersistenceService;
import com.github.guoyyit.fibroutingjava.trie.BinaryPrefixTrie;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 本地转发表服务门面。
 *
 * <p><b>版本模型</b>：当前已提交版本由一个 {@code volatile} 的不可变
 * {@link FibSnapshot} 引用表示。更新在暂存区累积；{@code commit} 时一次性做
 * 重复/遮蔽/重叠/环路/容量校验，通过后基于“生效路由全集”重建两棵 LPM 树，
 * 再通过单次 volatile 写发布新版本。因此进行中的查询始终引用某一个完整快照，
 * 绝不会看到半套路由或字段级混合状态；校验失败时旧版本保持不变。</p>
 *
 * <p><b>暂存语义</b>：暂存区只存在于内存，从未写盘；只有已提交版本会持久化。</p>
 *
 * <p><b>健康切换</b>：下一跳健康状态由 {@link HealthRegistry} 独立维护，
 * 失效立即在选路边界生效，详见 {@link ImmutableFibSnapshot}。</p>
 */
@Service
public class FibService {

    private static final Logger log = LoggerFactory.getLogger(FibService.class);

    private final FibProperties properties;
    private final NextHopSelector selector;
    private final RouteValidator validator;
    private final HealthRegistry healthRegistry;
    private final FibPersistenceService persistence;

    /** 已发布版本的不可变引用；volatile 写即原子版本切换点。 */
    private volatile FibSnapshot current;

    /** 暂存区：以 (前缀, 管理距离) 为键，null 值表示删除，非空表示替换/新增。 */
    private final Map<RouteValidator.RouteKey, RouteEntry> staged = new LinkedHashMap<>();

    public FibService(FibProperties properties,
                      NextHopSelector selector,
                      RouteValidator validator,
                      HealthRegistry healthRegistry,
                      FibPersistenceService persistence) {
        this.properties = properties;
        this.selector = selector;
        this.validator = validator;
        this.healthRegistry = healthRegistry;
        this.persistence = persistence;
    }

    @PostConstruct
    void restore() {
        if (!properties.isRestoreOnStart()) {
            current = emptySnapshot(0);
            return;
        }
        FibPersistenceService.PersistedFib loaded;
        try {
            loaded = persistence.loadCommitted();
        } catch (Exception e) {
            // 损坏/部分写入不得污染已提交状态：以空表启动并显式告警
            log.warn("persisted FIB unreadable, starting with empty table: {}", e.getMessage());
            current = emptySnapshot(0);
            return;
        }
        if (loaded == null) {
            current = emptySnapshot(0);
            return;
        }
        // 恢复路径同样重建为不可变快照，恢复数据也要做字段合法性过滤（在持久化层完成反序列化校验）
        current = buildSnapshot(loaded.version(), new ArrayList<>(loaded.routes()));
        log.info("restored {} routes at version {}", loaded.routes().size(), loaded.version());
    }

    /** 当前已发布的不可变版本。 */
    public FibSnapshot currentSnapshot() {
        return current;
    }

    /** 使用默认超时预算的查找。 */
    public LookupResult lookup(String destinationIp, String flowKey) {
        return lookup(destinationIp, flowKey,
                LookupControl.withBudget(properties.getLookupTimeoutNanos()));
    }

    /** 支持显式超时/取消控制的查找；超时或取消以异常终止，不产生部分结果。 */
    public LookupResult lookup(String destinationIp, String flowKey, LookupControl control) {
        FibSnapshot snapshot = current; // 单次读取：整个查找绑定该版本
        return snapshot.lookup(destinationIp, flowKey, control);
    }

    /** 将一条路由加入暂存区（同前缀+同管理距离视为替换）。 */
    public synchronized void stageUpsert(RouteEntry entry) {
        validator.validateEntry(entry);
        staged.put(new RouteValidator.RouteKey(entry.prefix(), entry.adminDistance()), entry);
    }

    /** 将某前缀的某管理距离路由删除放入暂存区。 */
    public synchronized void stageDelete(IpPrefix prefix, int adminDistance) {
        if (adminDistance < 0) {
            throw new RouteRejectedException(RejectionReason.INVALID_DISTANCE,
                    "adminDistance must be >= 0: " + adminDistance);
        }
        staged.put(new RouteValidator.RouteKey(prefix, adminDistance), null);
    }

    /** 丢弃暂存区中的全部未提交更新。 */
    public synchronized void discardStaged() {
        staged.clear();
    }

    /**
     * 校验并原子提交暂存区，返回新版本快照。
     * 未通过校验则暂存区保留（便于调用方修正），已发布版本保持不变。
     */
    public synchronized FibSnapshot commit() {
        List<RouteEntry> upserts = new ArrayList<>();
        List<RouteValidator.RouteKey> deletions = new ArrayList<>();
        for (Map.Entry<RouteValidator.RouteKey, RouteEntry> e : staged.entrySet()) {
            if (e.getValue() == null) {
                deletions.add(e.getKey());
            } else {
                upserts.add(e.getValue());
            }
        }
        FibSnapshot snap = applyCommit(upserts, deletions, null);
        staged.clear();
        return snap;
    }

    /**
     * 直接原子提交一条 upsert（不触碰调用方可能暂存的内容）。
     * 多线程并发调用安全：每条更新独立校验、独立生成新版本，版本号线性递增。
     */
    public synchronized FibSnapshot upsertAndCommit(RouteEntry entry) {
        validator.validateEntry(entry);
        return applyCommit(List.of(entry), List.of(), null);
    }

    /** 直接原子提交一条删除（不触碰暂存区）。 */
    public synchronized FibSnapshot deleteAndCommit(IpPrefix prefix, int adminDistance) {
        if (adminDistance < 0) {
            throw new RouteRejectedException(RejectionReason.INVALID_DISTANCE,
                    "adminDistance must be >= 0: " + adminDistance);
        }
        return applyCommit(List.of(),
                List.of(new RouteValidator.RouteKey(prefix, adminDistance)), null);
    }


    /**
     * 路由聚合/汇总：
     * 计算覆盖全部成员前缀的覆盖域（对齐后的单个 CIDR），删除成员自身的同距离路由，
     * 以聚合语义做重叠/遮蔽/环路校验后原子提交，返回汇总前缀。
     * 注意：聚合为独立的原子操作，不触碰也不包含调用方暂存区中的内容。
     */
    public synchronized IpPrefix aggregateAndCommit(List<IpPrefix> memberPrefixes,
                                                     int adminDistance,
                                                     List<NextHop> nextHops) {
        IpPrefix range = validator.aggregateRange(memberPrefixes);
        // 成员前缀随汇总撤回：汇总使用给定管理距离；成员按“整条前缀的所有管理距离”撤回，
        // 避免遗留其他距离的成员路由被新汇总遮蔽。
        List<RouteValidator.RouteKey> deletions = new ArrayList<>();
        java.util.Set<IpPrefix> memberSet = new java.util.HashSet<>(memberPrefixes);
        for (RouteEntry existing : current.allRoutes()) {
            if (memberSet.contains(existing.prefix())) {
                deletions.add(new RouteValidator.RouteKey(
                        existing.prefix(), existing.adminDistance()));
            }
        }
        RouteEntry summary = new RouteEntry(range, adminDistance, new ArrayList<>(nextHops));
        validator.validateEntry(summary);
        applyCommit(List.of(summary), deletions, range);
        return range;
    }

    public HealthRegistry health() {
        return healthRegistry;
    }

    /**
     * 核心原子提交流程（在 synchronized 方法内调用）：
     * 校验 -> 构造新快照 -> 持久化 -> 单次 volatile 写发布。
     * 任何校验失败都会抛出，current 保持旧版本不变。
     */
    private FibSnapshot applyCommit(List<RouteEntry> upserts,
                                    List<RouteValidator.RouteKey> deletions,
                                    IpPrefix aggregateShadowRange) {
        FibSnapshot base = current;
        List<RouteEntry> baseRoutes = new ArrayList<>(base.allRoutes());

        // 全部校验通过才构造新版本；任何拒绝都不会触碰 current
        validator.validateCommit(baseRoutes, upserts, deletions,
                properties.getMaxRoutes(), aggregateShadowRange);

        // 基于生效全集构造新版本
        Map<RouteValidator.RouteKey, RouteEntry> merged = new LinkedHashMap<>();
        for (RouteEntry r : baseRoutes) {
            merged.put(new RouteValidator.RouteKey(r.prefix(), r.adminDistance()), r);
        }
        for (RouteValidator.RouteKey k : deletions) {
            merged.remove(k);
        }
        for (RouteEntry r : upserts) {
            merged.put(new RouteValidator.RouteKey(r.prefix(), r.adminDistance()), r);
        }

        long newVersion = base.version() + 1;
        ImmutableFibSnapshot newSnapshot = buildSnapshot(newVersion, new ArrayList<>(merged.values()));

        // 先持久化（原子临时文件+rename）：崩溃时要么旧文件完整，要么新文件完整
        persistence.saveCommitted(newVersion, new ArrayList<>(newSnapshot.allRoutes()));

        // 单次 volatile 写：原子版本切换点
        current = newSnapshot;
        log.info("committed version {} with {} routes", newVersion, newSnapshot.routeCount());
        return newSnapshot;
    }

    /** 由路由全集构建不可变快照：按前缀写入两棵 LPM 树，同前缀按管理距离升序。 */
    private ImmutableFibSnapshot buildSnapshot(long version, List<RouteEntry> routes) {
        BinaryPrefixTrie<List<RouteEntry>> v4 = new BinaryPrefixTrie<>(IpVersion.V4);
        BinaryPrefixTrie<List<RouteEntry>> v6 = new BinaryPrefixTrie<>(IpVersion.V6);
        // 先按 (前缀, 管理距离) 归组，保证同前缀候选列表有序
        Map<IpPrefix, List<RouteEntry>> byPrefix = new LinkedHashMap<>();
        for (RouteEntry r : routes) {
            byPrefix.computeIfAbsent(r.prefix(), k -> new ArrayList<>()).add(r);
        }
        for (List<RouteEntry> group : byPrefix.values()) {
            group.sort((a, b) -> Integer.compare(a.adminDistance(), b.adminDistance()));
            List<RouteEntry> immutableGroup = List.copyOf(group);
            IpPrefix prefix = group.get(0).prefix();
            BinaryPrefixTrie<List<RouteEntry>> trie =
                    prefix.version() == IpVersion.V4 ? v4 : v6;
            trie.put(prefix.addressBytes(), prefix.prefixLength(), immutableGroup);
        }
        RouteSet routeSet = new RouteSet(v4, v6);
        return new ImmutableFibSnapshot(version, routeSet, routes, selector,
                healthRegistry, properties.defaultRouteNextHop());
    }

    private ImmutableFibSnapshot emptySnapshot(long version) {
        return buildSnapshot(version, new ArrayList<>());
    }
}
