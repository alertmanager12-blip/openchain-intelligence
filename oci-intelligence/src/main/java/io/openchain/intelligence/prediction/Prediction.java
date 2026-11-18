package io.openchain.intelligence.prediction;

import io.openchain.core.model.Address;
import java.time.Instant;
import java.util.List;

/**
 * A hypothesis about a future observable event of a subject (ADR-0023 amendment 2, level PREDICTED). It always has an
 * expiry and a machine-evaluable condition, rests on signals, states its limits, is never CONFIRMED and never changes a
 * risk score. {@code confidence} is the measured realisation rate of its type, or null while that rate is not established
 * ("uncalibrated"): a confidence is never invented.
 *
 * @param minOutgoing   the condition: at least this many outgoing transfers of the subject after {@code issuedAt} and up
 *                      to {@code expiresAt}, the transfer that triggered the prediction excluded; with
 *                      {@code distinctRecipients}, at least this many distinct recipients of such transfers
 * @param excludedEvent event id of the triggering transfer
 */
public record Prediction(String id, Type type, Address subject, Double confidence, List<String> signalIds, List<String> limits, Instant issuedAt,
                         Instant expiresAt, int minOutgoing, boolean distinctRecipients, String excludedEvent, String model) {
    public static final String LEVEL = "PREDICTED";

    public enum Type {
        /** A wallet that woke up after a long dormancy keeps sending value within the horizon. */
        WAKE_CONTINUED_OUTFLOW,
        /** A wallet that sent most of its balance (RAPID_DRAIN) sends again within the horizon. */
        DRAIN_CONTINUES,
        /** A wallet that forwarded what it received (FAST_FORWARDING) sends again within the horizon. */
        FORWARDING_CONTINUES,
        /** A wallet that dispersed value to many recipients (FLOW_CONCENTRATION) disperses again within the horizon. */
        DISPERSION_REPEATS
    }

    public Prediction {
        if (expiresAt == null || !expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("a prediction needs an expiry after its issue");
        if (minOutgoing < 1) throw new IllegalArgumentException("a prediction needs an observable condition");
        signalIds = List.copyOf(signalIds);
        limits = List.copyOf(limits);
    }

    public String condition() {
        return "at least " + minOutgoing + (distinctRecipients ? " distinct recipient(s) of outgoing transfers of " : " outgoing transfer(s) of ")
                + subject.value() + " after " + issuedAt + " and until " + expiresAt;
    }
}
