package io.openchain.app.api;

import io.openchain.app.prediction.PredictionService;
import io.openchain.app.prediction.PredictionStore;
import io.openchain.intelligence.prediction.Prediction;
import io.openchain.intelligence.prediction.Predictions;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Predictions (ADR-0023 amendment 2): hypotheses with an expiry, level PREDICTED, each with its condition, limits and
 * outcome once evaluated; and the measured realisation rate per type. A prediction is never a finding.
 */
@RestController
@RequestMapping("/api/v1/predictions")
public class PredictionController {
    private final PredictionService predictions;

    public PredictionController(PredictionService predictions) { this.predictions = predictions; }

    /** {@code confidence} null means uncalibrated; {@code outcome} OPEN until evaluated. */
    public record PredictionDto(String id, String level, String type, String network, String subject, Double confidence, String condition,
                                List<String> limits, List<String> signalIds, Instant issuedAt, Instant expiresAt, String model, String outcome,
                                List<String> outcomeEvidence, String outcomeReason, Instant evaluatedAt) {}

    /** {@code rate} counts realised and not realised only; {@code established} once enough are evaluated. */
    public record RateDto(String type, long realised, long notRealised, long undetermined, Double rate, boolean established, int minEvaluated) {}

    private static final Set<String> OUTCOMES = Set.of("OPEN", "REALISED", "NOT_REALISED", "UNDETERMINED");

    @GetMapping
    public List<PredictionDto> list(@RequestParam(required = false) String outcome, @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw ApiException.badRequest(ApiCode.PARAM_OUT_OF_RANGE, Map.of("param", "limit", "min", 1, "max", 200));
        String o = outcome == null || outcome.isBlank() ? null : outcome.trim().toUpperCase(Locale.ROOT);
        if (o != null && !OUTCOMES.contains(o)) throw ApiException.badRequest(ApiCode.PREDICTION_OUTCOME_INVALID);
        return predictions.list(o, limit).stream().map(PredictionController::dto).toList();
    }

    @GetMapping("/rates")
    public List<RateDto> rates() {
        return predictions.rates().stream().map(r -> new RateDto(r.type().name(), r.realised(), r.notRealised(), r.undetermined(), r.rate(),
                r.established(), Predictions.MIN_EVALUATED)).toList();
    }

    static PredictionDto dto(PredictionStore.Entry e) {
        Prediction p = e.prediction();
        var ev = e.evaluation();
        return new PredictionDto(p.id(), Prediction.LEVEL, p.type().name(), p.subject().network().id(), p.subject().value(), p.confidence(),
                p.condition(), p.limits(), p.signalIds(), p.issuedAt(), p.expiresAt(), p.model(), ev == null ? "OPEN" : ev.outcome().name(),
                ev == null ? List.of() : ev.evidence(), ev == null ? null : ev.reason(), ev == null ? null : ev.evaluatedAt());
    }
}
