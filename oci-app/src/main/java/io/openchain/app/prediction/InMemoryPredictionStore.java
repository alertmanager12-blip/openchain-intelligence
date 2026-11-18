package io.openchain.app.prediction;

import io.openchain.intelligence.prediction.Evaluation;
import io.openchain.intelligence.prediction.Prediction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Predictions in memory (lost on restart). */
public final class InMemoryPredictionStore implements PredictionStore {
    private final Map<String, Prediction> predictions = new LinkedHashMap<>();
    private final Map<String, Evaluation> evaluations = new LinkedHashMap<>();

    @Override public synchronized void insert(Prediction p) { predictions.putIfAbsent(p.id(), p); }

    @Override public synchronized void evaluate(Evaluation e) {
        if (!predictions.containsKey(e.predictionId())) throw new IllegalArgumentException("unknown prediction");
        if (evaluations.putIfAbsent(e.predictionId(), e) != null) throw new IllegalStateException("already evaluated");
    }

    @Override public synchronized List<Prediction> open() {
        return predictions.values().stream().filter(p -> !evaluations.containsKey(p.id())).toList();
    }

    @Override public synchronized List<Entry> list(String outcome, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Prediction p : predictions.values()) {
            Evaluation e = evaluations.get(p.id());
            String state = e == null ? "OPEN" : e.outcome().name();
            if (outcome == null || outcome.equals(state)) out.add(new Entry(p, e));
        }
        out.sort(Comparator.comparing((Entry x) -> x.prediction().issuedAt()).reversed().thenComparing(x -> x.prediction().id()));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    @Override public synchronized Map<Evaluation.Outcome, Long> counts(Prediction.Type type) {
        Map<Evaluation.Outcome, Long> m = new EnumMap<>(Evaluation.Outcome.class);
        for (Evaluation e : evaluations.values()) {
            if (predictions.get(e.predictionId()).type() == type) m.merge(e.outcome(), 1L, Long::sum);
        }
        return m;
    }
}
