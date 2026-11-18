package io.openchain.app.prediction;

import io.openchain.intelligence.prediction.Evaluation;
import io.openchain.intelligence.prediction.Prediction;
import java.util.List;
import java.util.Map;

/** Predictions and their evaluations (V18), append-only. */
public interface PredictionStore {
    /** Inserts once; a prediction already stored (same id) is left as it is. */
    void insert(Prediction p);

    void evaluate(Evaluation e);

    /** Predictions without an evaluation. */
    List<Prediction> open();

    record Entry(Prediction prediction, Evaluation evaluation) {}

    /** Newest first; {@code outcome} null for every state, "OPEN" for the unevaluated ones. */
    List<Entry> list(String outcome, int limit);

    Map<Evaluation.Outcome, Long> counts(Prediction.Type type);
}
