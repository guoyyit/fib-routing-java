package com.github.guoyyit.fibroutingjava.engine;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.health.HealthTracker;
import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.model.RouteErrorCode;
import com.github.guoyyit.fibroutingjava.model.RouteValidationException;
import com.github.guoyyit.fibroutingjava.persist.FibStore;
import com.github.guoyyit.fibroutingjava.trie.LookupBudget;
import com.github.guoyyit.fibroutingjava.validation.RouteValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Central forwarding information base.
 *
 * <p>Reads are lock-free: they take one immutable {@link FibSnapshot} reference
 * and observe exactly one complete version. Commits are serialized by a monitor
 * and only swap the published reference after the whole new table has been
 * validated, built and persisted; a failure at any stage leaves the live table
 * untouched, so no read can ever see a half-applied or field-mixed state.
 *
 * <p>One staged transaction exists at a time, modelling a single configuration
 * session; the staging map is distinct from the published state, so uncommitted
 * edits are invisible to lookups and discarded by rollback.
 */
@Component
public final class FibTable {

    private static final Logger log = LoggerFactory.getLogger(FibTable.class);
    private static final String DEFAULT_SOURCE = "__default__";

    private final HealthTracker healthTracker;
    private final RouteValidator validator;
    private final FibStore store;
    private final FibProperties properties;

    private final Object commitLock = new Object();
    private volatile FibSnapshot current = FibSnapshot.empty();
    private FibTransaction staging;

    public FibTable(HealthTracker healthTracker, RouteValidator validator,
                    FibStore store, FibProperties properties) {
        this.healthTracker = healthTracker;
        this.validator = validator;
        this.store = store;
        this.properties = properties;
    }

    /** Loads the last committed snapshot on startup, if persistence is enabled. */
    @PostConstruct
    public void recover() {
        if (!properties.isPersistenceEnabled()) {
            return;
        }
        store.load().ifPresent(loaded -> {
            current = loaded.withVersionAndDefaults(loaded.version(),
                    resolveDefaultHop(true), resolveDefaultHop(false));
            log.info("FIB recovered version {} with {} routes from {}",
                    loaded.version(), loaded.routeCount(), properties.getStorePath());
        });
    }

    public FibSnapshot snapshot() {
        return current;
    }

    public void beginTransaction() {
        synchronized (commitLock) {
            if (staging != null) {
                throw new IllegalStateException("a transaction is already open; commit or rollback first");
            }
            staging = FibTransaction.begin();
        }
    }

    private FibTransaction requireStaging() {
        FibTransaction tx = staging;
        if (tx == null) {
            throw new RouteValidationException(RouteErrorCode.NO_ACTIVE_TRANSACTION,
                    "no active transaction; begin one before staging changes");
        }
        return tx;
    }

    /**
     * Stages an entry without validating it yet. Staging is deliberately
     * non-judgemental: all validation runs against the complete candidate
     * table during {@link #commit()}, so an illegal entry rejects the entire
     * change-set as one unit rather than blocking earlier legal edits.
     */
    public void stageUpsert(RouteEntry entry) {
        synchronized (commitLock) {
            Objects.requireNonNull(entry, "entry");
            requireStaging().upsert(entry);
        }
    }

    public void stageDelete(String prefixCidr, String source) {
        synchronized (commitLock) {
            IpPrefix prefix = IpPrefix.parse(prefixCidr);
            if (source == null || source.isBlank()) {
                requireStaging().deletePrefix(prefix);
                return;
            }
            boolean exists = current.routes().getOrDefault(prefix, List.of()).stream()
                    .anyMatch(r -> r.source().equals(source));
            boolean staged = staging != null
                    && staging.upserts().containsKey(new FibTransaction.RouteKey(prefix, source));
            if (!exists && !staged) {
                throw new RouteValidationException(RouteErrorCode.PREFIX_NOT_FOUND,
                        "no route " + prefix + " from source '" + source + "' to delete");
            }
            requireStaging().delete(prefix, source);
        }
    }

    public void stageDeletePrefix(String prefixCidr) {
        synchronized (commitLock) {
            IpPrefix prefix = IpPrefix.parse(prefixCidr);
            if (!current.routes().containsKey(prefix)) {
                throw new RouteValidationException(RouteErrorCode.PREFIX_NOT_FOUND,
                        "no route for prefix " + prefix + " to delete");
            }
            requireStaging().deletePrefix(prefix);
        }
    }

    /**
     * Validates and applies the staged change-set as one atomic version and
     * returns the new version number. Any validation or persistence error
     * rejects the entire transaction and leaves both the live table and the
     * transaction ready for correction or rollback.
     */
    public long commit() {
        synchronized (commitLock) {
            FibTransaction tx = requireStaging();
            if (tx.isEmpty()) {
                staging = null;
                return current.version();
            }
            try {
                return doCommit(tx);
            } catch (RuntimeException rejected) {
                // An illegal change-set is rejected as a whole: discard staging so
                // no half-batch lingers, while the live committed version stays intact.
                staging = null;
                throw rejected;
            }
        }
    }

    private long doCommit(FibTransaction tx) {
            // 1. Project the committed map onto a candidate without publishing it.
            Map<FibTransaction.RouteKey, RouteEntry> candidate = new LinkedHashMap<>();
            for (List<RouteEntry> entries : current.routes().values()) {
                for (RouteEntry entry : entries) {
                    candidate.put(new FibTransaction.RouteKey(entry.prefix(), entry.source()), entry);
                }
            }
            for (IpPrefix prefix : tx.removedPrefixes()) {
                candidate.keySet().removeIf(k -> k.prefix().equals(prefix));
            }
            candidate.keySet().removeAll(tx.removedKeys());
            // upsert semantics: same (prefix, source) replaces the prior entry,
            // whether that means adding a new route or updating an existing one.
            for (Map.Entry<FibTransaction.RouteKey, RouteEntry> e : tx.upserts().entrySet()) {
                candidate.put(e.getKey(), e.getValue());
            }

            // 2. Intrinsic validation first. It runs only now, so the change-set
            //    is either legal together or rejected together; live table untouched.
            int projectedSize = candidate.size();
            validator.checkCapacity(projectedSize, properties.getMaxRoutes());
            List<RouteEntry> nextRoutes = new ArrayList<>(candidate.values());
            for (RouteEntry entry : nextRoutes) {
                validator.validate(entry);
            }

            // 3. Build the new immutable version (and its lookup trie) before
            //    touching anything live, then run cross-route topology checks
            //    with O(prefix-depth) longest-match instead of an O(n) scan.
            long nextVersion = current.version() + 1;
            FibSnapshot next = FibSnapshot.of(nextVersion, nextRoutes,
                    resolveDefaultHop(true), resolveDefaultHop(false));
            validateCrossRoutes(next, nextRoutes);

            // 4. Persist first: an I/O failure must never swap the live version.
            if (properties.isPersistenceEnabled()) {
                store.persist(next);
            }

            // 5. Atomic reference switch; in-flight reads keep their old snapshot.
            current = next;
            staging = null;
            log.info("committed FIB version {} with {} routes", nextVersion, projectedSize);
            return nextVersion;
    }

    /**
     * Cross-route topology check via a directed prefix graph.
     *
     * <p>Every prefix is a node. Each next hop resolves through the candidate
     * table's longest matching prefix; that resolution is a directed edge from
     * the route's prefix to the resolved prefix. An edge that stays inside the
     * same prefix is the self-loop already rejected per entry. A directed cycle
     * (A's gateway routes via B and B's gateway routes via A) means traffic
     * would never leave the graph and is rejected with
     * {@link RouteErrorCode#LOOP_DETECTED}.
     *
     * <p>Hop resolution uses the immutable snapshot trie, so building the whole
     * graph costs {@code O(hops * prefix-depth)} rather than a quadratic scan.
     */
    private void validateCrossRoutes(FibSnapshot candidate, List<RouteEntry> routes) {
        Map<IpPrefix, List<IpPrefix>> edges = new HashMap<>();
        for (RouteEntry route : routes) {
            List<IpPrefix> targets = edges.computeIfAbsent(route.prefix(), p -> new ArrayList<>());
            for (NextHop hop : route.nextHops()) {
                IpPrefix resolved = candidate.longestRoutedPrefix(hop.address());
                if (resolved != null && !resolved.equals(route.prefix())) {
                    targets.add(resolved);
                }
            }
        }
        if (hasCycle(edges)) {
            throw new RouteValidationException(RouteErrorCode.LOOP_DETECTED,
                    "cross-route forwarding loop detected among route prefixes");
        }
    }

    /** Iterative three-colour DFS cycle test over the prefix resolution graph. */
    private static boolean hasCycle(Map<IpPrefix, List<IpPrefix>> edges) {
        Set<IpPrefix> done = new HashSet<>();
        for (IpPrefix root : edges.keySet()) {
            if (done.contains(root)) {
                continue;
            }
            Set<IpPrefix> onStack = new HashSet<>();
            ArrayList<IpPrefix> path = new ArrayList<>();
            ArrayList<Integer> cursor = new ArrayList<>();
            path.add(root);
            cursor.add(0);
            onStack.add(root);
            while (!path.isEmpty()) {
                int frame = path.size() - 1;
                IpPrefix node = path.get(frame);
                List<IpPrefix> neighbors = edges.getOrDefault(node, List.of());
                int nextIndex = cursor.get(frame);
                if (nextIndex >= neighbors.size()) {
                    onStack.remove(node);
                    done.add(node);
                    path.remove(frame);
                    cursor.remove(frame);
                    continue;
                }
                IpPrefix child = neighbors.get(nextIndex);
                cursor.set(frame, nextIndex + 1);
                if (onStack.contains(child)) {
                    return true;
                }
                if (!done.contains(child)) {
                    path.add(child);
                    cursor.add(0);
                    onStack.add(child);
                }
            }
        }
        return false;
    }

    public void rollback() {
        synchronized (commitLock) {
            staging = null;
        }
    }

    public boolean hasOpenTransaction() {
        synchronized (commitLock) {
            return staging != null;
        }
    }

    /**
     * Lock-free lookup on one stable snapshot version. Health state is sampled
     * once per call, so the result reflects exactly one version plus exactly one
     * health instant. A timeout budget yields TIMEOUT, never a partial answer.
     */
    public LookupResult lookup(IpAddress destination, Long timeoutNanos) {
        FibSnapshot snapshot = current;
        long budgetNanos = timeoutNanos != null ? timeoutNanos : properties.getLookupTimeoutNanos();
        LookupBudget budget = LookupBudget.ofTimeout(budgetNanos);
        Set<String> disabled = healthTracker.disabledIds();
        return snapshot.lookup(destination, budget, disabled);
    }

    /**
     * Explicit aggregate commit used by the aggregation endpoint. The component
     * list has already been summarized and validated by {@code RouteAggregator};
     * here the aggregate is additionally rejected when it would be wholly
     * shadowed by already-installed more-specific routes, then committed in one
     * atomic version.
     */
    public long commitAggregate(RouteEntry aggregateRoute, java.util.List<IpPrefix> components) {
        synchronized (commitLock) {
            checkNotShadowed(aggregateRoute.prefix(), components);
            beginTransaction();
            try {
                stageUpsert(aggregateRoute);
                return commit();
            } catch (RuntimeException ex) {
                rollback();
                throw ex;
            }
        }
    }

    /**
     * Shadow rule for aggregation. The components named in the request are the
     * routes being summarized - it is normal (and intended) that they cover the
     * aggregate block. A shadow occurs only when <em>other</em> already-installed
     * more-specific routes, distinct from those components, completely cover the
     * aggregate address space: then the new aggregate could never be selected
     * and adds nothing. That change is rejected with
     * {@link RouteErrorCode#SHADOW_DETECTED}.
     */
    private void checkNotShadowed(IpPrefix aggregate, java.util.List<IpPrefix> components) {
        java.util.Set<IpPrefix> componentSet = new java.util.HashSet<>(components);
        java.util.List<IpPrefix> otherCoverage = new java.util.ArrayList<>();
        for (IpPrefix installed : current.routes().keySet()) {
            if (componentSet.contains(installed)) {
                continue;
            }
            if (aggregate.contains(installed) && installed.prefixLength() > aggregate.prefixLength()) {
                otherCoverage.add(installed);
            }
        }
        if (!otherCoverage.isEmpty() && coversEntireSpace(aggregate, otherCoverage)) {
            throw new RouteValidationException(RouteErrorCode.SHADOW_DETECTED,
                    "aggregate " + aggregate + " is fully shadowed by other more-specific routes");
        }
    }

    /**
     * Decides whether the given prefixes (all contained in {@code aggregate})
     * cover every address in it. Coverage is measured on the canonical
     * half-open integer interval of the prefix; intervals are merged.
     */
    private static boolean coversEntireSpace(IpPrefix aggregate, java.util.List<IpPrefix> prefixes) {
        int width = (aggregate.isIpv4() ? 32 : 128) - aggregate.prefixLength();
        if (width > 60) {
            return false; // cannot enumerate super-large spaces; treat as not provably shadowed
        }
        long total = 1L << width;
        boolean[] occupied = new boolean[(int) total];
        byte[] base = aggregate.networkAddress().copyBytes();
        for (IpPrefix p : prefixes) {
            if (!aggregate.contains(p)) {
                continue;
            }
            long offset = offsetWithin(aggregate, p.networkAddress().copyBytes());
            long span = 1L << (width - (p.prefixLength() - aggregate.prefixLength()));
            for (long i = offset; i < offset + span; i++) {
                if (i >= 0 && i < total) {
                    occupied[(int) i] = true;
                }
            }
        }
        for (boolean b : occupied) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    /** Numeric offset of an address relative to an aggregate's network address. */
    private static long offsetWithin(IpPrefix aggregate, byte[] address) {
        long offset = 0;
        for (int bit = aggregate.prefixLength();
             bit < (aggregate.isIpv4() ? 32 : 128); bit++) {
            offset <<= 1;
            if ((address[bit >> 3] & (0x80 >> (bit & 7))) != 0) {
                offset |= 1L;
            }
        }
        return offset;
    }

    private NextHop resolveDefaultHop(boolean ipv4) {
        String configured = ipv4 ? properties.getDefaultNextHopIpv4() : properties.getDefaultNextHopIpv6();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        IpAddress address = IpAddress.parse(configured);
        return new NextHop(DEFAULT_SOURCE + (ipv4 ? "-v4" : "-v6"), address, 1, true);
    }
}
