package io.openchain.intelligence.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.detection.Severity;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-0023 amendment 2: a prediction has an expiry and a condition, is evaluated deterministically, gaps are undetermined. */
class PredictionsTest {
    static final Token USD = new Token(Network.ETHEREUM, "0xusd0000000000000000000000000000000000001", "USDT", 0);
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    final Address w = new Address(Network.ETHEREUM, "0x" + "a".repeat(40)), o = new Address(Network.ETHEREUM, "0x" + "b".repeat(40));
    int seq;

    ValueFlow flow(Address from, Address to, Duration after) {
        String tx = "0x%064x".formatted(++seq);
        return new ValueFlow("ethereum:" + tx + ":0", Network.ETHEREUM, tx, T0.plus(after), from, to, USD, BigInteger.TEN);
    }

    Signal signal(SignalType t) {
        return new Signal("sig-1", t, w, "0xabc", Severity.LOW, 0.8, Signal.EvidenceLevel.ANOMALY, List.of(), "dormant-reactivation@1", T0, null, null);
    }

    @Test void aWakeUpGivesAnUncalibratedPredictionWithExpiryAndCondition() {
        ValueFlow trigger = flow(w, o, Duration.ZERO);
        Prediction p = Predictions.fromSignal(signal(SignalType.DORMANT_REACTIVATION), trigger, null).orElseThrow();
        assertThat(p.type()).isEqualTo(Prediction.Type.WAKE_CONTINUED_OUTFLOW);
        assertThat(p.confidence()).as("never invented").isNull();
        assertThat(p.expiresAt()).isEqualTo(T0.plus(Predictions.WAKE_HORIZON));
        assertThat(p.limits()).anyMatch(l -> l.startsWith("Uncalibrated"));
        assertThat(p.signalIds()).containsExactly("sig-1");
        assertThat(Predictions.fromSignal(signal(SignalType.DORMANT_REACTIVATION), trigger, null).orElseThrow().id()).isEqualTo(p.id());
        assertThat(Predictions.fromSignal(signal(SignalType.UNUSUAL_AMOUNT), trigger, null)).isEmpty();
        assertThatThrownBy(() -> new Prediction("x", Prediction.Type.WAKE_CONTINUED_OUTFLOW, w, null, List.of(), List.of(), T0, T0, 1, false, "e", "m"))
                .hasMessageContaining("expiry");
    }

    @Test void eachPrecursorHasItsTypeHorizonAndCondition() {
        ValueFlow trigger = flow(w, o, Duration.ZERO);
        assertThat(Predictions.fromSignal(signal(SignalType.RAPID_DRAIN), trigger, null, 20).orElseThrow())
                .satisfies(p -> { assertThat(p.type()).isEqualTo(Prediction.Type.DRAIN_CONTINUES); assertThat(p.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(24))); });
        assertThat(Predictions.fromSignal(signal(SignalType.FAST_FORWARDING), trigger, null, 20).orElseThrow().type())
                .isEqualTo(Prediction.Type.FORWARDING_CONTINUES);
        Prediction d = Predictions.fromSignal(signal(SignalType.FLOW_CONCENTRATION), trigger, null, 3).orElseThrow();
        assertThat(d.type()).isEqualTo(Prediction.Type.DISPERSION_REPEATS);
        assertThat(d.minOutgoing()).isEqualTo(3);
        assertThat(d.distinctRecipients()).isTrue();
        assertThat(d.condition()).contains("3 distinct recipient(s)");
        // a signal raised on someone else's transfer gives nothing
        assertThat(Predictions.fromSignal(signal(SignalType.RAPID_DRAIN), flow(o, w, Duration.ZERO), null, 20)).isEmpty();
        assertThat(Predictions.fromSignal(signal(SignalType.UNUSUAL_AMOUNT), trigger, null, 20)).isEmpty();
    }

    @Test void aDispersionCountsDistinctRecipientsAndARestartMakesItUndetermined() {
        ValueFlow trigger = flow(w, o, Duration.ZERO);
        Prediction d = Predictions.fromSignal(signal(SignalType.FLOW_CONCENTRATION), trigger, null, 3).orElseThrow();
        Address r1 = new Address(Network.ETHEREUM, "0x" + "1".repeat(40)), r2 = new Address(Network.ETHEREUM, "0x" + "2".repeat(40)),
                r3 = new Address(Network.ETHEREUM, "0x" + "3".repeat(40));
        List<ValueFlow> twoTo1 = List.of(flow(w, r1, Duration.ofHours(1)), flow(w, r1, Duration.ofHours(2)), flow(w, r2, Duration.ofHours(3)));
        assertThat(Predictions.satisfied(d, twoTo1)).as("two distinct recipients only").isFalse();
        assertThat(Predictions.satisfied(d, List.of(twoTo1.get(0), twoTo1.get(2), flow(w, r3, Duration.ofHours(4))))).isTrue();
        Instant past = T0.plus(Duration.ofHours(73));
        assertThat(Predictions.evaluate(d, twoTo1, past, false, false, past)).hasValueSatisfying(e -> {
            assertThat(e.outcome()).isEqualTo(Evaluation.Outcome.UNDETERMINED);
            assertThat(e.reason()).contains("restart");
        });
        assertThat(Predictions.evaluate(d, twoTo1, past, false, true, past))
                .hasValueSatisfying(e -> assertThat(e.outcome()).isEqualTo(Evaluation.Outcome.NOT_REALISED));
    }

    @Test void evaluationIsDeterministicAndAGapIsNeverNotRealised() {
        ValueFlow trigger = flow(w, o, Duration.ZERO);
        Prediction p = Predictions.fromSignal(signal(SignalType.DORMANT_REACTIVATION), trigger, null).orElseThrow();
        Instant now = T0.plus(Duration.ofDays(10));
        ValueFlow later = flow(w, o, Duration.ofHours(5)), incoming = flow(o, w, Duration.ofHours(6)), afterExpiry = flow(w, o, Duration.ofHours(80));

        assertThat(Predictions.evaluate(p, List.of(trigger, incoming, afterExpiry), T0.plus(Duration.ofHours(10)), false, now)).as("still open").isEmpty();
        assertThat(Predictions.evaluate(p, List.of(trigger, later), null, false, now)).hasValueSatisfying(e -> {
            assertThat(e.outcome()).isEqualTo(Evaluation.Outcome.REALISED);
            assertThat(e.evidence()).containsExactly(later.eventId());
        });
        Instant past = T0.plus(Duration.ofHours(73));
        assertThat(Predictions.evaluate(p, List.of(trigger, incoming, afterExpiry), past, false, now))
                .hasValueSatisfying(e -> assertThat(e.outcome()).isEqualTo(Evaluation.Outcome.NOT_REALISED));
        assertThat(Predictions.evaluate(p, List.of(), past, true, now))
                .hasValueSatisfying(e -> assertThat(e.outcome()).isEqualTo(Evaluation.Outcome.UNDETERMINED));
    }

    @Test void theRateCountsOnlyEvaluatedPredictionsAndIsEstablishedFromThirty() {
        var r = Predictions.rate(Prediction.Type.WAKE_CONTINUED_OUTFLOW,
                Map.of(Evaluation.Outcome.REALISED, 6L, Evaluation.Outcome.NOT_REALISED, 4L, Evaluation.Outcome.UNDETERMINED, 50L));
        assertThat(r.rate()).isEqualTo(0.6);
        assertThat(r.established()).isFalse();
        assertThat(Predictions.rate(Prediction.Type.WAKE_CONTINUED_OUTFLOW, Map.of(Evaluation.Outcome.REALISED, 20L, Evaluation.Outcome.NOT_REALISED, 10L))
                .established()).isTrue();
        assertThat(Predictions.rate(Prediction.Type.WAKE_CONTINUED_OUTFLOW, Map.of()).rate()).isNull();
    }
}
