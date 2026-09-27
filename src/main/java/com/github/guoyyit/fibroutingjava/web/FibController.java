package com.github.guoyyit.fibroutingjava.web;

import com.github.guoyyit.fibroutingjava.aggregate.RouteAggregator;
import com.github.guoyyit.fibroutingjava.engine.FibTable;
import com.github.guoyyit.fibroutingjava.health.HealthTracker;
import com.github.guoyyit.fibroutingjava.model.AggregationRequest;
import com.github.guoyyit.fibroutingjava.model.AggregationResult;
import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP surface for forwarding lookups, transactional route updates, route
 * aggregation/summarization and next-hop health changes.
 */
@RestController
@RequestMapping("/api/fib")
public final class FibController {

    private static final String DEFAULT_SOURCE = "static";
    private static final int DEFAULT_ADMIN_DISTANCE = 10;

    private final FibTable fibTable;
    private final HealthTracker healthTracker;
    private final RouteAggregator routeAggregator;

    public FibController(FibTable fibTable, HealthTracker healthTracker, RouteAggregator routeAggregator) {
        this.fibTable = fibTable;
        this.healthTracker = healthTracker;
        this.routeAggregator = routeAggregator;
    }

    /** Looks up a process-constructed destination; no real network is involved. */
    @GetMapping("/lookup")
    public LookupResponse lookup(@RequestParam String destination,
                                 @RequestParam(required = false) Long timeoutNanos) {
        IpAddress address = IpAddress.parse(destination);
        LookupResult result = fibTable.lookup(address, timeoutNanos);
        return new LookupResponse(
                result.outcome().name(),
                address.canonicalText(),
                result.matchedPrefix() == null ? null : result.matchedPrefix().toString(),
                result.source(),
                result.nextHop() == null ? null : result.nextHop().id(),
                result.nextHop() == null ? null : result.nextHop().address().canonicalText(),
                result.version());
    }

    @PostMapping("/transactions")
    public Map<String, Object> begin() {
        fibTable.beginTransaction();
        return Map.of("status", "OPEN", "version", fibTable.snapshot().version());
    }

    /** Stages a route add/update into the open transaction. */
    @PostMapping("/routes")
    public Map<String, Object> upsert(@RequestBody RouteUpsertRequest request) {
        if (request == null || request.prefix() == null) {
            throw new IllegalArgumentException("prefix is required");
        }
        IpPrefix prefix = IpPrefix.parse(request.prefix());
        List<NextHop> hops = toHops(request.nextHops());
        String source = request.source() == null || request.source().isBlank()
                ? DEFAULT_SOURCE : request.source();
        int adminDistance = request.adminDistance() == null
                ? DEFAULT_ADMIN_DISTANCE : request.adminDistance();
        RouteEntry entry = new RouteEntry(prefix, source, adminDistance, hops);
        fibTable.stageUpsert(entry);
        return Map.of("status", "STAGED", "prefix", prefix.toString(), "source", source);
    }

    /** Deletes one source of a prefix, or the whole prefix when source is omitted. */
    @DeleteMapping("/routes")
    public Map<String, Object> delete(@RequestParam String prefix,
                                      @RequestParam(required = false) String source) {
        if (source == null || source.isBlank()) {
            fibTable.stageDeletePrefix(prefix);
        } else {
            fibTable.stageDelete(prefix, source);
        }
        return Map.of("status", "STAGED", "prefix", prefix);
    }

    /** Validates, summarizes and atomically commits an aggregate route. */
    @PostMapping("/aggregate")
    public AggregationResult aggregate(@RequestBody AggregateApiRequest request) {
        if (request == null || request.components() == null) {
            throw new IllegalArgumentException("components are required");
        }
        List<NextHop> hops = toHops(request.nextHops());
        String source = request.source() == null || request.source().isBlank()
                ? DEFAULT_SOURCE : request.source();
        int adminDistance = request.adminDistance() == null
                ? DEFAULT_ADMIN_DISTANCE : request.adminDistance();
        AggregationRequest aggregationRequest =
                new AggregationRequest(request.components(), hops, source, adminDistance);
        IpPrefix aggregate = routeAggregator.summarize(aggregationRequest);
        RouteEntry route = new RouteEntry(aggregate, source, adminDistance, hops);
        List<IpPrefix> componentPrefixes = request.components().stream()
                .map(IpPrefix::parse).toList();
        long version = fibTable.commitAggregate(route, componentPrefixes);
        return new AggregationResult(aggregate, request.components().size(), version);
    }

    @PostMapping("/commit")
    public Map<String, Object> commit() {
        long version = fibTable.commit();
        return Map.of("status", "COMMITTED", "version", version);
    }

    @PostMapping("/rollback")
    public Map<String, Object> rollback() {
        fibTable.rollback();
        return Map.of("status", "ROLLED_BACK", "version", fibTable.snapshot().version());
    }

    /** Changes next-hop health; in-flight and future lookups observe it immediately. */
    @PostMapping("/health")
    public Map<String, Object> health(@RequestBody HealthRequest request) {
        if (request == null || request.hopId() == null) {
            throw new IllegalArgumentException("hopId is required");
        }
        if (request.enabled()) {
            healthTracker.enable(request.hopId());
        } else {
            healthTracker.disable(request.hopId());
        }
        return Map.of("hopId", request.hopId(), "enabled", request.enabled());
    }

    private static List<NextHop> toHops(List<HopSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("at least one next hop is required");
        }
        List<NextHop> hops = new ArrayList<>(specs.size());
        for (HopSpec spec : specs) {
            if (spec.id() == null || spec.address() == null) {
                throw new IllegalArgumentException("each next hop requires id and address");
            }
            int weight = spec.weight() == null ? 1 : spec.weight();
            boolean enabled = spec.enabled() == null || spec.enabled();
            hops.add(new NextHop(spec.id(), IpAddress.parse(spec.address()), weight, enabled));
        }
        return hops;
    }
}
