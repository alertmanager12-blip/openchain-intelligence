package io.openchain.app.trace;

import io.openchain.app.security.ApiKeyService;
import io.openchain.intelligence.investigation.InvestigationPlan;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Human review of investigation assertions (ADR-0022 amendment 3). A review is a decision next to an assertion: it never
 * changes the assertion, its level or the report. Append-only; the latest decision on an assertion id within the
 * investigation's lineage, up to its version, is the current one.
 */
public final class AssertionReviewService {
    private final TraceArtifactStore artifacts;
    private final AssertionReviewStore reviews;
    private final Clock clock;
    private final BooleanSupplier fourEyes;

    public AssertionReviewService(TraceArtifactStore artifacts, AssertionReviewStore reviews, Clock clock, BooleanSupplier fourEyes) {
        this.artifacts = artifacts;
        this.reviews = reviews;
        this.clock = clock;
        this.fourEyes = fourEyes;
    }

    /** No such investigation in this scope, or no such assertion in it: answered 404, like an unknown id. */
    public static final class NotFound extends RuntimeException {
        public NotFound(String m) { super(m); }
    }

    /** The investigation has no assertions yet (pending, running or failed). */
    public static final class NotFinished extends RuntimeException {
        public NotFinished() { super("the investigation has not finished"); }
    }

    /** The four-eyes rule refuses a review by the creator of the investigation. */
    public static final class FourEyes extends RuntimeException {
        public FourEyes() { super("four-eyes rule: the creator of an investigation may not review its assertions"); }
    }

    /** The current decision on one assertion; {@code inherited} when it was taken on an earlier version. */
    public record Current(AssertionReview review, boolean inherited) {}

    /** Reviews as seen from one version: every review of its lineage up to that version, and the current decision per assertion. */
    public record View(TraceArtifact investigation, List<AssertionReview> history, Map<String, Current> current, int decided, int total) {}

    public AssertionReview review(String scope, String investigationId, String assertionId, AssertionReview.Decision decision, String comment,
                                  String actor) {
        TraceArtifact inv = investigation(scope, investigationId);
        if (inv.resultJson() == null) throw new NotFinished();
        if (!assertionIds(inv).contains(assertionId)) throw new NotFound("no such assertion in this investigation");
        if (fourEyes.getAsBoolean() && actor != null && actor.equals(inv.createdBy())) throw new FourEyes();
        String c = comment == null || comment.isBlank() ? null : comment.strip();
        if (c != null && c.length() > AssertionReview.MAX_COMMENT) {
            throw new IllegalArgumentException("comment must be at most " + AssertionReview.MAX_COMMENT + " characters");
        }
        AssertionReview r = new AssertionReview("rv_" + UUID.randomUUID().toString().replace("-", ""), scope, inv.id(), assertionId, decision, c,
                actor, clock.instant(), inv.version());
        reviews.insert(r);
        ApiKeyService.AUDIT.info("investigation.review id={} investigation={} assertion={} decision={} clientScope={} actor={} comment={}",
                r.id(), inv.id(), assertionId, decision, scope, actor, c);
        return r;
    }

    public View view(String scope, String investigationId) {
        TraceArtifact inv = investigation(scope, investigationId);
        Set<String> ids = inv.resultJson() == null ? Set.of() : assertionIds(inv);
        List<AssertionReview> history = reviews.forLineage(scope, inv.lineageId()).stream()
                .filter(r -> r.investigationVersion() <= inv.version()).toList();
        Map<String, Current> current = new LinkedHashMap<>();
        for (AssertionReview r : history) { // oldest first: the last one wins
            if (ids.contains(r.assertionId())) current.put(r.assertionId(), new Current(r, r.investigationVersion() < inv.version()));
        }
        return new View(inv, history, current, current.size(), ids.size());
    }

    private TraceArtifact investigation(String scope, String id) {
        Optional<TraceArtifact> a = artifacts.find(scope, id).filter(x -> x.kind() == TraceArtifact.Kind.INVESTIGATION);
        return a.orElseThrow(() -> new NotFound("no such investigation"));
    }

    private static Set<String> assertionIds(TraceArtifact inv) {
        return TraceJson.assertions(inv.resultJson()).stream().map(InvestigationPlan.Assertion::id).collect(Collectors.toSet());
    }
}
