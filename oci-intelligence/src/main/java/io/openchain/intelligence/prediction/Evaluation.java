package io.openchain.intelligence.prediction;

import java.time.Instant;
import java.util.List;

/** The outcome of a prediction (ADR-0023 amendment 2), stored append-only. */
public record Evaluation(String predictionId, Outcome outcome, List<String> evidence, String reason, Instant evaluatedAt) {
    public enum Outcome {
        /** The condition was observed before the expiry; {@code evidence} holds the event ids. */
        REALISED,
        /** The expiry passed while the chain was observed without gap, and the condition was not met. */
        NOT_REALISED,
        /** The expiry passed but the chain was not observed continuously: never counted as not realised. */
        UNDETERMINED
    }

    public Evaluation { evidence = List.copyOf(evidence); }
}
