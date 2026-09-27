package com.github.guoyyit.fibroutingjava.web;

import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.fib.FibService;
import com.github.guoyyit.fibroutingjava.fib.FibSnapshot;
import com.github.guoyyit.fibroutingjava.fib.LookupControl;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.LookupResult;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RejectionReason;
import com.github.guoyyit.fibroutingjava.fib.RouteRejectedException;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import com.github.guoyyit.fibroutingjava.web.dto.AggregateRequest;
import com.github.guoyyit.fibroutingjava.web.dto.AggregateResponse;
import com.github.guoyyit.fibroutingjava.web.dto.BatchUpdateRequest;
import com.github.guoyyit.fibroutingjava.web.dto.CommitResponse;
import com.github.guoyyit.fibroutingjava.web.dto.HealthRequest;
import com.github.guoyyit.fibroutingjava.web.dto.LookupResponse;
import com.github.guoyyit.fibroutingjava.web.dto.NextHopDto;
import com.github.guoyyit.fibroutingjava.web.dto.RouteDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 转发表 REST 接口：
 * <ul>
 *   <li>GET  /fib/lookup?destination=&flowKey=&timeoutNanos= 查找；</li>
 *   <li>POST /fib/routes/batch 批量增删，autocommit=false 仅暂存（不生效不持久化）；</li>
 *   <li>POST /fib/commit 提交暂存区；POST /fib/discard 丢弃暂存；</li>
 *   <li>POST /fib/aggregate 聚合/汇总（服务端计算最短覆盖前缀并校验）；</li>
 *   <li>POST /fib/health 下一跳健康状态变更（立即影响选路，不产生新版本路由）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/fib")
public class FibController {

    private final FibService fibService;
    private final FibProperties properties;

    public FibController(FibService fibService, FibProperties properties) {
        this.fibService = fibService;
        this.properties = properties;
    }

    @GetMapping("/lookup")
    public LookupResponse lookup(@RequestParam String destination,
                                 @RequestParam(required = false) String flowKey,
                                 @RequestParam(required = false) Long timeoutNanos) {
        String key = flowKey == null || flowKey.isBlank() ? "" : flowKey;
        LookupControl control = LookupControl.withBudget(
                timeoutNanos == null ? properties.getLookupTimeoutNanos() : timeoutNanos);
        LookupResult r = fibService.lookup(destination, key, control);
        NextHop nh = r.selectedNextHop();
        return new LookupResponse(
                r.outcome().name(),
                destination,
                r.matchedPrefix() == null ? null : r.matchedPrefix().toString(),
                r.adminDistance(),
                nh == null ? null : nh.id(),
                nh == null ? null : nh.address(),
                key,
                r.version());
    }

    @PostMapping("/routes/batch")
    public synchronized CommitResponse batchUpdate(@RequestBody BatchUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body required");
        }
        if (request.upsert() != null) {
            for (RouteDto dto : request.upsert()) {
                fibService.stageUpsert(toEntry(dto));
            }
        }
        if (request.delete() != null) {
            for (String key : request.delete()) {
                ParsedKey pk = parseKey(key);
                fibService.stageDelete(pk.prefix, pk.adminDistance);
            }
        }
        boolean autocommit = request.autocommit() == null || request.autocommit();
        if (autocommit) {
            FibSnapshot snap = fibService.commit();
            return new CommitResponse(snap.version(), snap.routeCount(), true);
        }
        return new CommitResponse(fibService.currentSnapshot().version(),
                fibService.currentSnapshot().routeCount(), false);
    }

    @PostMapping("/commit")
    public CommitResponse commit() {
        FibSnapshot snap = fibService.commit();
        return new CommitResponse(snap.version(), snap.routeCount(), true);
    }

    @PostMapping("/discard")
    public CommitResponse discard() {
        fibService.discardStaged();
        FibSnapshot snap = fibService.currentSnapshot();
        return new CommitResponse(snap.version(), snap.routeCount(), false);
    }

    @PostMapping("/aggregate")
    public AggregateResponse aggregate(@RequestBody AggregateRequest request) {
        if (request == null || request.members() == null || request.members().isEmpty()) {
            throw new RouteRejectedException(RejectionReason.AGGREGATE_MISMATCH,
                    "aggregate requires non-empty members");
        }
        List<IpPrefix> members = new ArrayList<>();
        for (String m : request.members()) {
            members.add(IpPrefix.parse(m));
        }
        int distance = request.adminDistance() == null ? 0 : request.adminDistance();
        List<NextHop> hops = new ArrayList<>();
        if (request.nextHops() != null) {
            for (NextHopDto dto : request.nextHops()) {
                hops.add(toNextHop(dto));
            }
        }
        IpPrefix aggregate = fibService.aggregateAndCommit(members, distance, hops);
        FibSnapshot snap = fibService.currentSnapshot();
        return new AggregateResponse(aggregate.toString(), snap.version(), snap.routeCount());
    }

    @PostMapping("/health")
    public CommitResponse health(@RequestBody HealthRequest request) {
        if (request == null || request.nextHopId() == null) {
            throw new IllegalArgumentException("nextHopId required");
        }
        fibService.health().setHealthy(request.nextHopId(), request.healthy());
        FibSnapshot snap = fibService.currentSnapshot();
        return new CommitResponse(snap.version(), snap.routeCount(), false);
    }

    private RouteEntry toEntry(RouteDto dto) {
        if (dto == null || dto.prefix() == null) {
            throw new IllegalArgumentException("route prefix required");
        }
        IpPrefix prefix = IpPrefix.parse(dto.prefix());
        int distance = dto.adminDistance() == null ? 0 : dto.adminDistance();
        List<NextHop> hops = new ArrayList<>();
        if (dto.nextHops() != null) {
            for (NextHopDto h : dto.nextHops()) {
                hops.add(toNextHop(h));
            }
        }
        return new RouteEntry(prefix, distance, hops);
    }

    private NextHop toNextHop(NextHopDto h) {
        if (h == null || h.id() == null || h.address() == null) {
            throw new IllegalArgumentException("next hop requires id and address");
        }
        int weight = h.weight() == null ? 1 : h.weight();
        boolean enabled = h.enabled() == null || h.enabled();
        return new NextHop(h.id(), h.address(), weight, enabled);
    }

    private ParsedKey parseKey(String key) {
        String trimmed = key.trim();
        int at = trimmed.lastIndexOf('@');
        if (at >= 0) {
            return new ParsedKey(IpPrefix.parse(trimmed.substring(0, at)),
                    Integer.parseInt(trimmed.substring(at + 1)));
        }
        return new ParsedKey(IpPrefix.parse(trimmed), 0);
    }

    private record ParsedKey(IpPrefix prefix, int adminDistance) {
    }
}
