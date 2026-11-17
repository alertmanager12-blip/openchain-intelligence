package io.openchain.app.api;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.openchain.app.analysis.InvestigationService;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.ClientScope;
import io.openchain.app.trace.TraceArtifact;
import io.openchain.app.trace.TraceArtifactService;
import io.openchain.app.trace.TraceJson;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.trace.Fraction;
import io.openchain.intelligence.trace.Trace;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stored traces (ADR-0022 amendment 1). Every operation is limited to the caller's client scope: another client's trace
 * answers 404 exactly like an unknown id (decision D1, tests N1 to N12). Runs are queued; poll {@code GET /traces/{id}}.
 */
@RestController
@RequestMapping("/api/v1/traces")
public class TraceController {
    private final TraceArtifactService traces;
    private final InvestigationService investigation;

    public TraceController(TraceArtifactService traces, InvestigationService investigation) {
        this.traces = traces;
        this.investigation = investigation;
    }

    /**
     * @param direction "out" (where the seed's value went) or "in" (where the value it received came from)
     * @param method    PROPORTIONAL (default) or FIFO
     */
    public record TraceRequest(String network, String seed, String direction, Integer depth, Integer maxNodes, String token, String method,
                               String from, String to) {}

    public record TraceSummary(String id, String lineageId, int version, String previousId, String network, String seed, String direction,
                               String status, String resultStatus, boolean stale, String createdBy, Instant createdAt, Instant completedAt) {}

    /** A stored trace; {@code request}, {@code result} and {@code diff} are the stored JSON, as computed. */
    public record TraceDetail(String id, String lineageId, int version, String previousId, String network, String seed, String direction,
                              String status, String resultStatus, boolean stale, List<String> staleEventIds, String engineVersion,
                              String error, String createdBy, Instant createdAt, Instant completedAt, int frozenFlows,
                              @JsonRawValue String request, @JsonRawValue String result, @JsonRawValue String diff) {}

    private static final String NOT_FOUND = "no such trace";

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TraceDetail create(@RequestBody TraceRequest r, Authentication auth) {
        return detail(submit(() -> traces.submit(ClientScope.of(auth), request(r), auth.getName())));
    }

    @GetMapping
    public List<TraceSummary> list(@RequestParam(defaultValue = "50") int limit, Authentication auth) {
        if (limit < 1 || limit > 200) throw ApiException.badRequest("limit must be between 1 and 200");
        return traces.list(ClientScope.of(auth), limit).stream().map(TraceController::summary).toList();
    }

    @GetMapping("/{id}")
    public TraceDetail get(@PathVariable String id, Authentication auth) {
        return detail(traces.find(ClientScope.of(auth), id).orElseThrow(() -> ApiException.notFound(NOT_FOUND)));
    }

    /** The frozen flows the run read (its evidence). */
    @GetMapping("/{id}/evidence")
    public Map<String, Object> evidence(@PathVariable String id, Authentication auth) {
        TraceArtifact a = traces.find(ClientScope.of(auth), id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        if (a.evidenceJson() == null) return Map.of("id", a.id(), "status", a.status().name(), "flows", List.of());
        return Map.of("id", a.id(), "status", a.status().name(), "flows", TraceJson.evidence(a.evidenceJson()).flows());
    }

    /** The whole artifact (request, result, frozen evidence, diff), for archiving. An audited read. */
    @GetMapping("/{id}/export")
    public Map<String, Object> export(@PathVariable String id, Authentication auth) {
        String scope = ClientScope.of(auth);
        TraceArtifact a = traces.find(scope, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        ApiKeyService.AUDIT.info("trace.export id={} clientScope={} actor={}", id, scope, auth.getName());
        return Map.of("trace", detail(a), "evidence", a.evidenceJson() == null ? Map.of() : TraceJson.evidence(a.evidenceJson()));
    }

    /** A new version of the trace on the current data, with its diff against this one. */
    @PostMapping("/{id}/rerun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TraceDetail rerun(@PathVariable String id, Authentication auth) {
        return detail(submit(() -> traces.submitRerun(ClientScope.of(auth), id, auth.getName()).orElseThrow(() -> ApiException.notFound(NOT_FOUND))));
    }

    /** Cancels a run still pending; a started or finished run is returned unchanged. */
    @PostMapping("/{id}/cancel")
    public TraceDetail cancel(@PathVariable String id, Authentication auth) {
        return detail(traces.cancel(ClientScope.of(auth), id, auth.getName()).orElseThrow(() -> ApiException.notFound(NOT_FOUND)));
    }

    private static TraceArtifact submit(java.util.function.Supplier<TraceArtifact> s) {
        try {
            return s.get();
        } catch (TraceArtifactService.BudgetExceeded e) {
            throw new ApiException(429, "too-many-requests", "Too many requests", e.getMessage(), null);
        }
    }

    private Trace.Request request(TraceRequest r) {
        if (r == null) throw ApiException.badRequest("body is required");
        Network n = investigation.network(r.network());
        Address seed = investigation.address(n, r.seed());
        Trace.Direction d = switch (r.direction() == null ? "" : r.direction()) {
            case "out" -> Trace.Direction.FORWARD;
            case "in" -> Trace.Direction.BACKWARD;
            default -> throw ApiException.badRequest("direction must be out or in");
        };
        int depth = r.depth() == null ? 3 : r.depth(), nodes = r.maxNodes() == null ? 500 : r.maxNodes();
        if (depth < 1 || depth > Trace.Limits.DEPTH_CAP) throw ApiException.badRequest("depth must be between 1 and " + Trace.Limits.DEPTH_CAP);
        if (nodes < 2 || nodes > Trace.Limits.NODES_CAP) throw ApiException.badRequest("maxNodes must be between 2 and " + Trace.Limits.NODES_CAP);
        Trace.Method m;
        try {
            m = r.method() == null ? Trace.Method.PROPORTIONAL : Trace.Method.valueOf(r.method().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("method must be PROPORTIONAL or FIFO");
        }
        Instant from = InvestigationController.instant(r.from(), "from"), to = InvestigationController.instant(r.to(), "to");
        return new Trace.Request(n, seed, d, new Trace.Window(from, to), r.token() == null || r.token().isBlank() ? null : r.token().trim(), m,
                new Trace.Limits(depth, nodes, 20_000, 5_000), Set.of(LabelCategory.EXCHANGE, LabelCategory.BRIDGE),
                new Fraction(BigInteger.ONE, BigInteger.valueOf(1_000)), Map.of());
    }

    static TraceSummary summary(TraceArtifact a) {
        return new TraceSummary(a.id(), a.lineageId(), a.version(), a.previousId(), a.network(), a.seed(), a.direction(), a.status().name(),
                a.resultStatus(), a.stale(), a.createdBy(), a.createdAt(), a.completedAt());
    }

    static TraceDetail detail(TraceArtifact a) {
        return new TraceDetail(a.id(), a.lineageId(), a.version(), a.previousId(), a.network(), a.seed(), a.direction(), a.status().name(),
                a.resultStatus(), a.stale(), a.staleEventIds(), a.engineVersion(), a.error(), a.createdBy(), a.createdAt(), a.completedAt(),
                a.eventIds().size(), a.requestJson(), a.resultJson(), a.diffJson());
    }
}
