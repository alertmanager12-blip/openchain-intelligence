package io.openchain.app.api;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.openchain.app.analysis.InvestigationService;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.ClientScope;
import io.openchain.app.trace.TraceArtifact;
import io.openchain.app.trace.TraceArtifactService;
import io.openchain.app.trace.TraceJson;
import io.openchain.core.model.Network;
import io.openchain.intelligence.investigation.InvestigationReport;
import java.time.Instant;
import java.util.List;
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
 * Investigations (ADR-0022 amendment 2): the deterministic plan run on one address, stored with its frozen evidence; the
 * report is generated from the stored assertions only. Limited to the caller's client scope like stored traces (404 for
 * another client's investigation, exactly like an unknown id).
 */
@RestController
@RequestMapping("/api/v1/investigations")
public class InvestigationRunController {
    private final TraceArtifactService runs;
    private final InvestigationService investigation;

    public InvestigationRunController(TraceArtifactService runs, InvestigationService investigation) {
        this.runs = runs;
        this.investigation = investigation;
    }

    public record InvestigationRequest(String network, String address) {}

    /** {@code assertions} and {@code diff} are the stored JSON; {@code report} is built from the assertions only. */
    public record InvestigationDetail(String id, String lineageId, int version, String previousId, String network, String subject, String plan,
                                      String status, String resultStatus, boolean stale, List<String> staleEventIds, String error, String createdBy,
                                      Instant createdAt, Instant completedAt, int frozenFlows, @JsonRawValue String assertions,
                                      @JsonRawValue String diff, InvestigationReport.Report report) {}

    private static final String NOT_FOUND = "no such investigation";

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InvestigationDetail create(@RequestBody InvestigationRequest r, Authentication auth) {
        if (r == null) throw ApiException.badRequest("body is required");
        Network n = investigation.network(r.network());
        var subject = investigation.address(n, r.address());
        try {
            return detail(runs.submitInvestigation(ClientScope.of(auth), subject, auth.getName()));
        } catch (TraceArtifactService.BudgetExceeded e) {
            throw new ApiException(429, "too-many-requests", "Too many requests", e.getMessage(), null);
        }
    }

    @GetMapping
    public List<TraceController.TraceSummary> list(@RequestParam(defaultValue = "50") int limit, Authentication auth) {
        if (limit < 1 || limit > 200) throw ApiException.badRequest("limit must be between 1 and 200");
        return runs.list(ClientScope.of(auth), limit, TraceArtifact.Kind.INVESTIGATION).stream().map(TraceController::summary).toList();
    }

    @GetMapping("/{id}")
    public InvestigationDetail get(@PathVariable String id, Authentication auth) { return detail(find(id, auth)); }

    /** The report alone (JSON), built from the stored assertions; an audited read. */
    @GetMapping("/{id}/report")
    public InvestigationReport.Report report(@PathVariable String id, Authentication auth) {
        TraceArtifact a = find(id, auth);
        if (a.resultJson() == null) throw ApiException.conflict("the investigation has not finished");
        ApiKeyService.AUDIT.info("investigation.report id={} clientScope={} actor={}", id, a.clientScope(), auth.getName());
        return InvestigationReport.build(a.network() + ":" + a.seed(), TraceJson.assertions(a.resultJson()));
    }

    @PostMapping("/{id}/rerun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InvestigationDetail rerun(@PathVariable String id, Authentication auth) {
        try {
            return detail(runs.submitInvestigationRerun(ClientScope.of(auth), id, auth.getName()).orElseThrow(() -> ApiException.notFound(NOT_FOUND)));
        } catch (TraceArtifactService.BudgetExceeded e) {
            throw new ApiException(429, "too-many-requests", "Too many requests", e.getMessage(), null);
        }
    }

    private TraceArtifact find(String id, Authentication auth) {
        return runs.find(ClientScope.of(auth), id).filter(a -> a.kind() == TraceArtifact.Kind.INVESTIGATION)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    static InvestigationDetail detail(TraceArtifact a) {
        InvestigationReport.Report report = a.resultJson() == null ? null
                : InvestigationReport.build(a.network() + ":" + a.seed(), TraceJson.assertions(a.resultJson()));
        return new InvestigationDetail(a.id(), a.lineageId(), a.version(), a.previousId(), a.network(), a.seed(), a.engineVersion(),
                a.status().name(), a.resultStatus(), a.stale(), a.staleEventIds(), a.error(), a.createdBy(), a.createdAt(), a.completedAt(),
                a.eventIds().size(), a.resultJson(), a.diffJson(), report);
    }
}
