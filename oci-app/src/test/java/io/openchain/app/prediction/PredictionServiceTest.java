package io.openchain.app.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.chain.IndexingCoverage;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.prediction.Evaluation;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-0023 amendment 2: live realisation, expiry only past the indexer, gaps undetermined, restart resumes open ones. */
class PredictionServiceTest {
    static final Token USD = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    final Address w = new Address(Network.TRON, "TPredWallet1111111111111111111111"), o = new Address(Network.TRON, "TPredOther11111111111111111111111");
    final InMemoryPredictionStore store = new InMemoryPredictionStore();
    final List<IndexingCoverage.Gap> gaps = new ArrayList<>();
    Instant now = T0;
    final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    };
    int seq;

    PredictionService service() { return new PredictionService(store, n -> gaps, clock); }

    ValueFlow flow(Address from, Address to, Duration after) {
        String tx = "%064x".formatted(++seq);
        return new ValueFlow("tron:" + tx + ":0", Network.TRON, tx, T0.plus(after), from, to, USD, BigInteger.TEN);
    }

    Signal wake() {
        return new Signal("sig-" + seq, SignalType.DORMANT_REACTIVATION, w, "x", Severity.LOW, 0.8, Signal.EvidenceLevel.ANOMALY, List.of(),
                "dormant-reactivation@1", T0, null, null);
    }

    @Test void anOutgoingTransferRealisesTheOpenPredictionAsItIsIndexed() {
        PredictionService s = service();
        s.observe(flow(w, o, Duration.ZERO), List.of(wake()));
        assertThat(s.openCount()).isEqualTo(1);
        s.observe(flow(o, w, Duration.ofHours(1)), List.of()); // incoming: no
        s.observe(flow(w, o, Duration.ofHours(2)), List.of());
        assertThat(s.openCount()).isZero();
        now = T0.plus(Duration.ofHours(3));
        s.tick();
        assertThat(store.list("REALISED", 10)).hasSize(1);
    }

    @Test void atExpiryNotRealisedOnlyPastTheIndexerAndUndeterminedAfterAGap() {
        PredictionService s = service();
        s.observe(flow(w, o, Duration.ZERO), List.of(wake()));
        now = T0.plus(Duration.ofHours(80));
        s.tick();
        assertThat(s.openCount()).as("the indexer has not passed the expiry yet").isEqualTo(1);
        s.observe(flow(o, o, Duration.ofHours(75)), List.of()); // indexer now past the expiry on this network
        s.tick();
        assertThat(store.list("NOT_REALISED", 10)).hasSize(1);

        PredictionService g = new PredictionService(new InMemoryPredictionStore(), n -> gaps, clock);
        now = T0;
        g.observe(flow(w, o, Duration.ZERO), List.of(wake()));
        gaps.add(new IndexingCoverage.Gap(Network.TRON, 1, 2, IndexingCoverage.Cause.RESUME_GAP,
                T0.plus(Duration.ofHours(10))));
        g.observe(flow(o, o, Duration.ofHours(75)), List.of());
        now = T0.plus(Duration.ofHours(80));
        g.tick();
        assertThat(g.openCount()).isZero();
        assertThat(g.list("UNDETERMINED", 10)).hasSize(1);
        assertThat(g.rates().getFirst().rate()).as("undetermined is not counted").isNull();
    }

    @Test void openPredictionsSurviveARestart() {
        PredictionService s = service();
        s.observe(flow(w, o, Duration.ZERO), List.of(wake()));
        s.tick();
        PredictionService again = service();
        again.start();
        assertThat(again.openCount()).isEqualTo(1);
        again.observe(flow(w, o, Duration.ofHours(1)), List.of());
        now = T0.plus(Duration.ofHours(2));
        again.tick();
        assertThat(store.counts(io.openchain.intelligence.prediction.Prediction.Type.WAKE_CONTINUED_OUTFLOW)).containsEntry(Evaluation.Outcome.REALISED, 1L);
    }
}
