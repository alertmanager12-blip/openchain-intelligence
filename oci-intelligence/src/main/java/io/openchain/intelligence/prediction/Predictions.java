package io.openchain.intelligence.prediction;

import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.intelligence.flow.ValueFlow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Precursors and evaluation of predictions (ADR-0023 amendment 2), deterministic. Model {@link #MODEL}, one precursor per
 * signal type, each measured on its own:
 * <ul>
 * <li>DORMANT_REACTIVATION: more outgoing transfers within 72 h;</li>
 * <li>RAPID_DRAIN: another outgoing transfer within 24 h;</li>
 * <li>FAST_FORWARDING: another outgoing transfer within 24 h;</li>
 * <li>FLOW_CONCENTRATION: a new dispersion, at least as many distinct recipients as the detector's threshold, within 72 h.</li>
 * </ul>
 */
public final class Predictions {
    private Predictions() {}

    public static final String MODEL = "prediction@1";
    public static final Duration WAKE_HORIZON = Duration.ofHours(72);
    public static final Duration SHORT_HORIZON = Duration.ofHours(24);
    /** Below this number of evaluated predictions of a type, its rate is not established and no confidence is given. */
    public static final int MIN_EVALUATED = 30;

    /**
     * The prediction a signal gives, if any.
     *
     * @param trigger the transfer the signal was raised on (its time is the issue time, its event is excluded)
     * @param rate    the established realisation rate of the type, or null
     */
    public static Optional<Prediction> fromSignal(Signal s, ValueFlow trigger, Double rate) { return fromSignal(s, trigger, rate, 20); }

    /** @param dispersionThreshold distinct recipients that make a dispersion (the fan-out detector's threshold) */
    public static Optional<Prediction> fromSignal(Signal s, ValueFlow trigger, Double rate, int dispersionThreshold) {
        if (trigger == null || !trigger.from().equals(s.address())) return Optional.empty();
        Prediction.Type type;
        Duration horizon;
        int min = 1;
        boolean distinct = false;
        String why;
        switch (s.type()) {
            case DORMANT_REACTIVATION -> { type = Prediction.Type.WAKE_CONTINUED_OUTFLOW; horizon = WAKE_HORIZON;
                why = "a wallet waking up is common and usually legitimate"; }
            case RAPID_DRAIN -> { type = Prediction.Type.DRAIN_CONTINUES; horizon = SHORT_HORIZON;
                why = "moving most of a balance is common (exchanges, migrations, payments)"; }
            case FAST_FORWARDING -> { type = Prediction.Type.FORWARDING_CONTINUES; horizon = SHORT_HORIZON;
                why = "forwarding what was received is what routers, payment and exchange wallets do"; }
            case FLOW_CONCENTRATION -> { type = Prediction.Type.DISPERSION_REPEATS; horizon = WAKE_HORIZON; min = dispersionThreshold; distinct = true;
                why = "paying many recipients is what payroll, airdrops and exchange hot wallets do"; }
            default -> { return Optional.empty(); }
        }
        Instant at = trigger.timestamp();
        return Optional.of(new Prediction(id(type, s.id()), type, s.address(), rate, List.of(s.id()), List.of(
                        "A hypothesis about future activity, not a finding: " + why + ".",
                        rate == null ? "Uncalibrated: fewer than " + MIN_EVALUATED + " predictions of this type have been evaluated."
                                : "Confidence is the realisation rate measured on past predictions of this type.",
                        "Evaluated on the transfers this instance indexes; a gap in indexing" + (distinct ? " or a restart during the window" : "")
                                + " makes the outcome undetermined."),
                at, at.plus(horizon), min, distinct, trigger.eventId(), MODEL));
    }

    /** True when these transfers (already filtered by {@link #realisedBy}) meet the condition. */
    public static boolean satisfied(Prediction p, List<ValueFlow> hits) {
        long n = p.distinctRecipients() ? hits.stream().map(ValueFlow::to).distinct().count() : hits.size();
        return n >= p.minOutgoing();
    }

    /** True when this flow realises the prediction (an outgoing transfer of the subject inside the window). */
    public static boolean realisedBy(Prediction p, ValueFlow f) {
        return f.from().equals(p.subject()) && !f.to().equals(p.subject()) && !f.eventId().equals(p.excludedEvent())
                && f.timestamp().isAfter(p.issuedAt()) && !f.timestamp().isAfter(p.expiresAt());
    }

    /**
     * Outcome of an open prediction whose expiry may have passed.
     *
     * @param observed           outgoing transfers that realise it (already filtered by {@link #realisedBy})
     * @param indexedUntil       event time the indexer of the subject's network has reached, or null if unknown
     * @param gapSinceIssue      an indexing gap was detected on that network after the issue
     * @return empty while the outcome is not known yet
     */
    public static Optional<Evaluation> evaluate(Prediction p, List<ValueFlow> observed, Instant indexedUntil, boolean gapSinceIssue, Instant now) {
        return evaluate(p, observed, indexedUntil, gapSinceIssue, true, now);
    }

    /** @param wholeWindowObserved false when the transfers counted so far may not cover the whole window (a restart) */
    public static Optional<Evaluation> evaluate(Prediction p, List<ValueFlow> observed, Instant indexedUntil, boolean gapSinceIssue,
                                                boolean wholeWindowObserved, Instant now) {
        List<ValueFlow> hits = observed.stream().filter(f -> realisedBy(p, f)).toList();
        if (satisfied(p, hits)) {
            return Optional.of(new Evaluation(p.id(), Evaluation.Outcome.REALISED, hits.stream().map(ValueFlow::eventId).sorted().toList(),
                    "condition observed", now));
        }
        if (indexedUntil == null || !indexedUntil.isAfter(p.expiresAt())) return Optional.empty(); // the chain is not past the expiry yet
        if (gapSinceIssue) return Optional.of(new Evaluation(p.id(), Evaluation.Outcome.UNDETERMINED, List.of(),
                "an indexing gap after the issue: transfers may have been missed", now));
        if (!wholeWindowObserved) return Optional.of(new Evaluation(p.id(), Evaluation.Outcome.UNDETERMINED, List.of(),
                "a restart during the window: transfers counted before it are not known", now));
        return Optional.of(new Evaluation(p.id(), Evaluation.Outcome.NOT_REALISED, List.of(), "expiry passed without the condition", now));
    }

    /** Realisation rate per type: realised / (realised + not realised); undetermined and open ones are not counted. */
    public record Rate(Prediction.Type type, long realised, long notRealised, long undetermined, Double rate, boolean established) {}

    public static Rate rate(Prediction.Type type, Map<Evaluation.Outcome, Long> counts) {
        long r = counts.getOrDefault(Evaluation.Outcome.REALISED, 0L), n = counts.getOrDefault(Evaluation.Outcome.NOT_REALISED, 0L),
                u = counts.getOrDefault(Evaluation.Outcome.UNDETERMINED, 0L);
        Double rate = r + n == 0 ? null : (double) r / (r + n);
        return new Rate(type, r, n, u, rate, r + n >= MIN_EVALUATED);
    }

    static String id(Prediction.Type t, String signalId) {
        try {
            return "pr_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((MODEL + "|" + t + "|" + signalId)
                    .getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
