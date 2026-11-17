package io.openchain.app.api;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.openchain.app.analysis.InvestigationService;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.ClientScope;
import io.openchain.app.trace.AssertionReview;
import io.openchain.app.trace.AssertionReviewService;
import io.openchain.app.trace.TraceArtifact;
import io.openchain.app.trace.TraceArtifactService;
import io.openchain.app.trace.TraceJson;
import io.openchain.core.model.Network;
import io.openchain.intelligence.investigation.InvestigationReport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final AssertionReviewService reviews;

    public InvestigationRunController(TraceArtifactService runs, InvestigationService investigation, AssertionReviewService reviews) {
        this.runs = runs;
        this.investigation = investigation;
        this.reviews = reviews;
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

    public record ReviewRequest(String decision, String comment) {}

    /** {@code version} is the version of the investigation the review was taken on. */
    public record ReviewDto(String id, String investigationId, int version, String assertionId, String decision, String comment, String actor,
                            Instant createdAt) {}

    /** The current decision on one assertion; {@code inherited} when it was taken on an earlier version (shown, never copied). */
    public record CurrentReview(String decision, String comment, String actor, Instant createdAt, int version, boolean inherited) {}

    /**
     * Reviews as seen from this version: {@code history} holds every review of the lineage up to this version, oldest first;
     * {@code current} maps each assertion of this version that has a decision to its latest one.
     */
    public record Reviews(String investigationId, int version, boolean stale, List<ReviewDto> history, Map<String, CurrentReview> current,
                          int decided, int total) {}

    /** A human decision next to one assertion (ADR-0022 amendment 3); it changes nothing the assertion or the report states. */
    @PostMapping("/{id}/assertions/{assertionId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto review(@PathVariable String id, @PathVariable String assertionId, @RequestBody ReviewRequest r, Authentication auth) {
        if (r == null || r.decision() == null || r.decision().isBlank()) throw ApiException.badRequest("decision is required");
        AssertionReview.Decision d;
        try {
            d = AssertionReview.Decision.valueOf(r.decision().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("decision must be ACCEPTED, REJECTED or NEEDS_EVIDENCE");
        }
        try {
            return dto(reviews.review(ClientScope.of(auth), id, assertionId, d, r.comment(), auth.getName()));
        } catch (AssertionReviewService.NotFound e) {
            throw ApiException.notFound(e.getMessage());
        } catch (AssertionReviewService.NotFinished e) {
            throw ApiException.conflict(e.getMessage());
        } catch (AssertionReviewService.FourEyes e) {
            throw new ApiException(403, "forbidden", "Forbidden", e.getMessage(), null);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{id}/reviews")
    public Reviews reviews(@PathVariable String id, Authentication auth) {
        AssertionReviewService.View v;
        try {
            v = reviews.view(ClientScope.of(auth), id);
        } catch (AssertionReviewService.NotFound e) {
            throw ApiException.notFound(NOT_FOUND);
        }
        Map<String, CurrentReview> current = new LinkedHashMap<>();
        v.current().forEach((k, c) -> current.put(k, new CurrentReview(c.review().decision().name(), c.review().comment(), c.review().actor(),
                c.review().createdAt(), c.review().investigationVersion(), c.inherited())));
        return new Reviews(v.investigation().id(), v.investigation().version(), v.investigation().stale(),
                v.history().stream().map(InvestigationRunController::dto).toList(), current, v.decided(), v.total());
    }

    static ReviewDto dto(AssertionReview r) {
        return new ReviewDto(r.id(), r.investigationId(), r.investigationVersion(), r.assertionId(), r.decision().name(), r.comment(), r.actor(),
                r.createdAt());
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
