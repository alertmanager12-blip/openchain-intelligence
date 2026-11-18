package io.openchain.app.trace;

import io.openchain.app.security.ApiKeyService;
import io.openchain.app.summary.SummaryPrompt;
import io.openchain.app.summary.SummaryWriter;
import io.openchain.intelligence.investigation.InvestigationPlan;
import io.openchain.intelligence.investigation.SummaryCheck;
import io.openchain.intelligence.investigation.SummaryInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optional AI summaries of investigations (ADR-0022 amendment 5). The analyst picks one of the configured providers; only
 * the filtered assertions are sent; the answer is checked by {@link SummaryCheck} and shown only when accepted. Every
 * request is stored (append-only) with its provider, model, prompt version, input hash and outcome.
 */
public final class InvestigationSummaryService {
    /** A configured provider; {@code dataLocation} says where the assertions go. */
    public record Provider(String id, String label, String connector, String model, String dataLocation, SummaryWriter writer) {}

    public static final class Disabled extends RuntimeException {
        public Disabled() { super("AI summaries are not enabled on this instance"); }
    }

    public static final class UnknownProvider extends RuntimeException {
        public UnknownProvider(String id) { super("no such AI provider: " + id); }
    }

    public static final class DailyCapReached extends RuntimeException {
        public DailyCapReached(int cap) { super("daily limit of " + cap + " AI summaries reached for this client"); }
    }

    private final TraceArtifactStore artifacts;
    private final InvestigationSummaryStore store;
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final boolean enabled;
    private final int dailyCap;
    private final Clock clock;

    public InvestigationSummaryService(TraceArtifactStore artifacts, InvestigationSummaryStore store, List<Provider> providers, boolean enabled,
                                       int dailyCap, Clock clock) {
        this.artifacts = artifacts;
        this.store = store;
        for (Provider p : providers) this.providers.put(p.id(), p);
        this.enabled = enabled;
        this.dailyCap = dailyCap;
        this.clock = clock;
    }

    /** Providers the analyst may choose; empty when the feature is off. */
    public List<Provider> providers() { return enabled ? List.copyOf(providers.values()) : List.of(); }

    public InvestigationSummary request(String scope, String investigationId, String providerId, String actor) {
        if (!enabled || providers.isEmpty()) throw new Disabled();
        TraceArtifact inv = artifacts.find(scope, investigationId).filter(a -> a.kind() == TraceArtifact.Kind.INVESTIGATION)
                .orElseThrow(() -> new AssertionReviewService.NotFound("no such investigation"));
        if (inv.resultJson() == null) throw new AssertionReviewService.NotFinished();
        Provider p = providers.get(providerId);
        if (p == null) throw new UnknownProvider(providerId);
        Instant now = clock.instant();
        if (store.countSince(scope, now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant()) >= dailyCap) throw new DailyCapReached(dailyCap);

        List<InvestigationPlan.Assertion> assertions = TraceJson.assertions(inv.resultJson());
        String user = SummaryPrompt.user(SummaryInput.payload(assertions));
        SummaryWriter.Draft d = p.writer().write(SummaryPrompt.SYSTEM, user);
        InvestigationSummary.Status status;
        List<SummaryCheck.Sentence> shown = null;
        List<String> reasons;
        if (d.failure() != null) {
            status = InvestigationSummary.Status.FAILED;
            reasons = List.of(d.failure());
        } else {
            SummaryCheck.Verdict v = SummaryCheck.check(d.sentences(), assertions);
            status = v.accepted() ? InvestigationSummary.Status.ACCEPTED : InvestigationSummary.Status.REFUSED;
            shown = v.accepted() ? v.sentences() : null;
            reasons = v.reasons();
        }
        InvestigationSummary s = new InvestigationSummary("sm_" + UUID.randomUUID().toString().replace("-", ""), scope, inv.id(), p.id(), d.model(),
                SummaryPrompt.VERSION, sha256(user), status, shown, d.sentences(), reasons, actor, now);
        store.insert(s);
        ApiKeyService.AUDIT.info("investigation.summary id={} investigation={} provider={} model={} status={} clientScope={} actor={}",
                s.id(), inv.id(), p.id(), d.model(), status, scope, actor);
        return s;
    }

    public List<InvestigationSummary> list(String scope, String investigationId) {
        artifacts.find(scope, investigationId).filter(a -> a.kind() == TraceArtifact.Kind.INVESTIGATION)
                .orElseThrow(() -> new AssertionReviewService.NotFound("no such investigation"));
        return store.forInvestigation(scope, investigationId);
    }

    static String sha256(String s) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
