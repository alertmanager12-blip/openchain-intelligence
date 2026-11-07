package io.openchain.intelligence.risk;

import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalText;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.risk.RiskLevel;
import io.openchain.core.risk.RiskScore;
import io.openchain.core.risk.RiskScore.Contribution;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.profile.WalletProfile;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explainable, deterministic risk scoring.
 *
 * <pre>
 * q_i = confidence_i * severityMult_i * 0.5^(age_i / halfLife)      (labels: q = sourceReliability * decay)
 * p_f = w_f * (1 - Π_i (1 - q_i))          per factor f, saturating and capped at its weight w_f
 * S   = 1 - Π_f (1 - p_f);  score = round(100 * S)
 * </pre>
 * Within a factor, an item whose evidence references (tx hashes / label key) were all already counted is not
 * counted again. Exposure signals without value-flow evidence are ignored. Only the subject's OWN labels and
 * signals are used: labels never propagate to neighbours; exposure enters only through flow-backed signals.
 * Points are attributed by marginal contribution so that they sum to the (unrounded) score.
 */
public final class RiskScoringEngine {
    public static final String METHOD = "noisy-or saturating aggregation: score=100*(1-prod_f(1-p_f)), "
            + "p_f=w_f*(1-prod_i(1-q_i)), q_i=confidence*severity*0.5^(age/halfLife); dedup by evidence per factor";

    private static final Set<SignalType> EXPOSURE = Set.of(SignalType.SANCTIONED_EXPOSURE, SignalType.FLAGGED_EXPOSURE,
            SignalType.INDIRECT_EXPOSURE, SignalType.MIXER_EXPOSURE, SignalType.SCAM_EXPOSURE);

    private final ScoringConfig config;

    public RiskScoringEngine(ScoringConfig config) { this.config = Objects.requireNonNull(config); }

    public ScoringConfig config() { return config; }

    /** Plain-language description of {@link #METHOD} for analysts (the formula stays in the score's {@code method}). */
    public static final String METHOD_SUMMARY = "Each finding adds points according to how strong the factor is (its weight), "
            + "how reliable the finding is (confidence and severity) and how recent it is (older findings count less, "
            + "except active sanctions and issuer freezes). Several findings of the same kind add up but never exceed the "
            + "factor's weight, and the same transactions are never counted twice. The score is between 0 and 100.";

    private record Item(String factor, String id, String source, double confidence, double severityMult, long ageSeconds,
                        double decay, Set<String> refs, String what, String ignoredReason, String title, String summary) {
        double q() { return ignoredReason != null ? 0 : confidence * severityMult * decay; }
    }

    /**
     * @param profile nullable; used only to decide UNKNOWN (no activity, no labels, no signals)
     */
    public RiskScore score(Address address, WalletProfile profile, List<AddressLabel> labels, List<Signal> signals, Instant now) {
        boolean activity = profile != null && profile.hasActivity();
        List<Item> items = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (AddressLabel l : labels) {
            if (!l.address().equals(address) || !seenIds.add("label:" + l.key())) continue;
            String f = ScoringConfig.labelFactor(l.category());
            long age = age(l.observedAt(), now);
            items.add(new Item(f, "label:" + l.key(), l.source(), l.sourceReliability(), 1.0, age, decay(f, age),
                    Set.of("label:" + l.key()), "listed as " + l.category() + " by source '" + l.source() + "'"
                    + (l.evidenceLevel() == Signal.EvidenceLevel.CONFIRMED_BY_SOURCE ? " (authoritative source)" : ""),
                    config.weight(f) == 0 ? "informational category (weight 0)" : null,
                    SignalText.labelTitle(l.category().name()), labelSummary(l)));
        }
        for (Signal s : signals) {
            if (!s.address().equals(address) || !seenIds.add(s.id())) continue;
            String f = ScoringConfig.signalFactor(s.type());
            long age = age(signalTime(s), now);
            Set<String> refs = new LinkedHashSet<>();
            if (s.txHash() != null) refs.add("tx:" + s.txHash());
            for (Signal.Evidence e : s.evidence())
                if (e.reference() != null && !"label".equals(e.kind())) refs.add("tx:" + e.reference());
            String ignored = null;
            if (EXPOSURE.contains(s.type()) && s.evidence().stream().noneMatch(e -> "flow".equals(e.kind()) && e.reference() != null))
                ignored = "ignored: exposure without value-flow evidence";
            items.add(new Item(f, s.id(), s.detectorId(), s.confidence(), config.severityMultiplier(s.severity()), age, decay(f, age),
                    refs, s.type() + " " + s.severity() + " " + s.evidenceLevel() + " by " + s.detectorId(), ignored,
                    s.title(), s.summary()));
        }

        // group by factor, strongest first; dedup by evidence references
        Map<String, List<Item>> byFactor = new LinkedHashMap<>();
        items.stream().sorted(Comparator.comparingDouble(Item::q).reversed().thenComparing(Item::id))
                .forEach(i -> byFactor.computeIfAbsent(i.factor(), k -> new ArrayList<>()).add(i));

        record Counted(Item item, double marginal, String note) {}
        Map<String, List<Counted>> counted = new LinkedHashMap<>();
        Map<String, Double> factorP = new LinkedHashMap<>();
        for (var e : byFactor.entrySet()) {
            double w = config.weight(e.getKey());
            double prod = 1;
            Set<String> used = new HashSet<>();
            List<Counted> list = new ArrayList<>();
            for (Item i : e.getValue()) {
                if (i.ignoredReason() != null) { list.add(new Counted(i, 0, i.ignoredReason())); continue; }
                if (!i.refs().isEmpty() && used.containsAll(i.refs())) { list.add(new Counted(i, 0, "not counted: same evidence already counted for this factor")); continue; }
                used.addAll(i.refs());
                double m = w * prod * i.q();
                prod *= (1 - i.q());
                list.add(new Counted(i, m, null));
            }
            counted.put(e.getKey(), list);
            factorP.put(e.getKey(), w * (1 - prod));
        }

        List<String> order = new ArrayList<>(factorP.keySet());
        order.sort(Comparator.comparingDouble((String k) -> factorP.get(k)).reversed().thenComparing(k -> k));
        double s = 0;
        List<Contribution> contributions = new ArrayList<>();
        for (String f : order) {
            double p = factorP.get(f);
            double delta = (1 - s) * p;
            s += delta;
            for (Counted c : counted.get(f)) {
                Item i = c.item();
                double points = p == 0 ? 0 : 100 * delta * (c.marginal() / p);
                String expl = String.format(Locale.ROOT, "%s; confidence %.2f, severity x%.2f, age %s, decay %.3f -> q=%.3f; factor weight %.2f, factor p=%.3f%s",
                        i.what(), i.confidence(), i.severityMult(), human(i.ageSeconds()), i.decay(), i.q(), config.weight(f), p,
                        c.note() == null ? "" : "; " + c.note());
                contributions.add(new Contribution(f, i.id(), i.source(), config.weight(f), i.confidence(), i.ageSeconds(),
                        Math.round(points * 100) / 100.0, expl, i.title(), humanNote(i.summary(), c.note())));
            }
        }
        int score = (int) Math.max(0, Math.min(100, Math.round(100 * s)));
        RiskLevel level = !activity && labels.stream().noneMatch(l -> l.address().equals(address)) && contributions.isEmpty()
                ? RiskLevel.UNKNOWN : RiskLevel.fromScore(score);
        String method = METHOD + "; halfLife=" + human(config.halfLife().toSeconds()) + (level == RiskLevel.UNKNOWN ? "; insufficient data" : "");
        return new RiskScore(address, score, level, contributions, method, config.modelVersion(), now);
    }

    /** "Listed as sanctioned by ofac-sdn (authoritative source)." / issuer freeze wording. */
    static String labelSummary(AddressLabel l) {
        if (l.category() == io.openchain.intelligence.label.LabelCategory.ISSUER_FROZEN) {
            return "The stablecoin issuer froze this address in its own token contract (source " + l.source()
                    + "); the issuer does not publish the reason.";
        }
        return "Listed as " + SignalText.category(l.category().name()) + " by " + l.source()
                + (l.evidenceLevel() == Signal.EvidenceLevel.CONFIRMED_BY_SOURCE ? " (authoritative source)."
                : String.format(Locale.ROOT, " (source reliability %.0f%%).", l.sourceReliability() * 100));
    }

    /** Adds why an item scored nothing, in plain words. */
    private static String humanNote(String summary, String note) {
        if (note == null) return summary;
        String why = note.startsWith("ignored: exposure") ? "Not counted: no value transfer backs this exposure."
                : note.startsWith("not counted") ? "Not counted again: the same transactions are already counted for this factor."
                : note.startsWith("informational") ? "Informational only: this category does not change the score."
                : SignalText.sentence(note);
        return summary == null ? why : summary + " " + why;
    }

    private double decay(String factor, long ageSeconds) {
        if (config.nonDecayingFactors().contains(factor)) return 1.0;
        return Math.pow(0.5, ageSeconds / (double) config.halfLife().toSeconds());
    }

    /** Age reference: most recent on-chain evidence time, else detection time. */
    private static Instant signalTime(Signal s) {
        return s.evidence().stream().filter(e -> !"label".equals(e.kind())).map(Signal.Evidence::observedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(s.detectedAt());
    }

    private static long age(Instant t, Instant now) { return t == null ? 0 : Math.max(0, Duration.between(t, now).toSeconds()); }

    private static String human(long s) {
        if (s >= 86_400) return (s / 86_400) + "d";
        if (s >= 3_600) return (s / 3_600) + "h";
        return (s / 60) + "m";
    }
}
