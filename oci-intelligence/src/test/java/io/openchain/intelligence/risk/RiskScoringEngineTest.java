package io.openchain.intelligence.risk;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.risk.RiskLevel;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.detection.InMemorySignalRepository;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.InMemoryLabelRepository;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.profile.WalletProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RiskScoringEngineTest {
    final Address me = a("me");
    final Instant now = days(30);
    final RiskScoringEngine engine = new RiskScoringEngine(ScoringConfig.defaults());

    Signal sig(String id, SignalType type, Severity sev, double conf, String tx, Instant at) {
        return new Signal(id, type, me, tx, sev, conf, EvidenceLevel.RISK_SIGNAL,
                List.of(new Evidence("flow", "flow", tx, "chain:ethereum", at)), "test@1", at);
    }

    AddressLabel label(Address a, LabelCategory c, double rel, Instant at) {
        return new AddressLabel(a, c, null, "demo-src", rel, at, EvidenceLevel.CONFIRMED_BY_SOURCE, null);
    }

    RiskScore score(List<AddressLabel> labels, List<Signal> signals) { return engine.score(me, null, labels, signals, now); }

    @Test void unknownWhenNoDataLowWhenActiveButClean() {
        RiskScore s = score(List.of(), List.of());
        assertThat(s.level()).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(s.score()).isZero();
        assertThat(s.method()).contains("insufficient data");
        var active = new WalletProfile(me, java.util.Map.of(), 3, 2, 1, days(0), days(1), 1, Duration.ZERO, List.of(), 0, 0, List.of(), now);
        RiskScore clean = engine.score(me, active, List.of(), List.of(), now);
        assertThat(clean.level()).isEqualTo(RiskLevel.LOW);
        assertThat(clean.score()).isZero();
    }

    @Test void contributionsCarryPlainLanguageTitleAndSummary() {
        Signal drain = new Signal("d1", SignalType.RAPID_DRAIN, me, "0xd", Severity.MEDIUM, 0.7, EvidenceLevel.RISK_SIGNAL,
                List.of(new Evidence("flow", "flow", "0xd", "chain:ethereum", now)), "rapid-drain@1", now, null,
                "Sent 95% of its USDT balance (35,000 USDT) to 2 addresses within 2 minutes.");
        Signal again = new Signal("d2", SignalType.RAPID_DRAIN, me, "0xd", Severity.LOW, 0.5, EvidenceLevel.RISK_SIGNAL,
                List.of(new Evidence("flow", "flow", "0xd", "chain:ethereum", now)), "rapid-drain@1", now);
        AddressLabel ofac = new AddressLabel(me, LabelCategory.SANCTIONED, null, "ofac-sdn", 1.0, now, EvidenceLevel.CONFIRMED_BY_SOURCE, null);
        AddressLabel frozen = new AddressLabel(me, LabelCategory.ISSUER_FROZEN, null, "tether-onchain", 1.0, now, EvidenceLevel.CONFIRMED_BY_SOURCE, "0xf");
        AddressLabel community = new AddressLabel(me, LabelCategory.FLAGGED_COMMUNITY, null, "community", 0.4, now, EvidenceLevel.RISK_SIGNAL, null);
        RiskScore s = score(List.of(ofac, frozen, community), List.of(drain, again));
        assertThat(s.contributions()).anySatisfy(c -> {
            assertThat(c.factor()).isEqualTo("signal:RAPID_DRAIN");
            assertThat(c.signalId()).isEqualTo("d1");
            assertThat(c.title()).isEqualTo("Rapid drain");
            assertThat(c.summary()).isEqualTo("Sent 95% of its USDT balance (35,000 USDT) to 2 addresses within 2 minutes.");
            assertThat(c.explanation()).contains("RAPID_DRAIN MEDIUM RISK_SIGNAL by rapid-drain@1").contains("q=");
        });
        assertThat(s.contributions()).anySatisfy(c -> {
            assertThat(c.signalId()).isEqualTo("d2");
            assertThat(c.summary()).endsWith("Not counted again: the same transactions are already counted for this factor.");
        });
        assertThat(s.contributions()).anySatisfy(c -> {
            assertThat(c.factor()).isEqualTo("label:SANCTIONED");
            assertThat(c.title()).isEqualTo("Listed as sanctioned");
            assertThat(c.summary()).isEqualTo("Listed as sanctioned by ofac-sdn (authoritative source).");
        });
        assertThat(s.contributions()).anySatisfy(c -> {
            assertThat(c.title()).isEqualTo("Frozen by the stablecoin issuer");
            assertThat(c.summary()).isEqualTo("The stablecoin issuer froze this address in its own token contract (source tether-onchain); "
                    + "the issuer does not publish the reason.");
        });
        assertThat(s.contributions()).anySatisfy(c -> assertThat(c.summary())
                .isEqualTo("Listed as flagged by the community by community (source reliability 40%)."));
        assertThat(RiskScoringEngine.METHOD_SUMMARY).doesNotContain("prod_f", "q_i");
        // a signal built without wording gets the type title and an evidence-derived sentence
        assertThat(again.title()).isEqualTo("Rapid drain");
        assertThat(again.summary()).isEqualTo("Rapid drain observed.");
    }

    @Test void sanctionedLabelIsCriticalAndDoesNotDecay() {
        RiskScore fresh = score(List.of(label(me, LabelCategory.SANCTIONED, 1.0, now)), List.of());
        RiskScore old = score(List.of(label(me, LabelCategory.SANCTIONED, 1.0, now.minus(Duration.ofDays(3650)))), List.of());
        assertThat(fresh.score()).isEqualTo(95);
        assertThat(fresh.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(old.score()).isEqualTo(fresh.score());
    }

    @Test void scoreIsBoundedEvenWithEverything() {
        List<Signal> all = new ArrayList<>();
        int i = 0;
        for (SignalType t : SignalType.values()) for (int k = 0; k < 5; k++)
            all.add(sig("s" + i++, t, Severity.CRITICAL, 1.0, "0x" + i, now));
        List<AddressLabel> labels = new ArrayList<>();
        for (LabelCategory c : LabelCategory.values()) labels.add(label(me, c, 1.0, now));
        RiskScore s = score(labels, all);
        assertThat(s.score()).isBetween(0, 100);
        assertThat(s.contributions()).allSatisfy(c -> assertThat(c.points()).isBetween(0.0, 100.0));
    }

    @Test void addingEvidenceNeverLowersTheScore() {
        Random r = new Random(42);
        SignalType[] types = SignalType.values();
        Severity[] sevs = Severity.values();
        List<Signal> acc = new ArrayList<>();
        int prev = score(List.of(), acc).score();
        for (int i = 0; i < 200; i++) {
            acc.add(sig("s" + i, types[r.nextInt(types.length)], sevs[r.nextInt(sevs.length)], r.nextDouble(),
                    "0x" + r.nextInt(50), now.minus(Duration.ofDays(r.nextInt(400)))));
            int cur = score(List.of(), acc).score();
            assertThat(cur).isGreaterThanOrEqualTo(prev);
            prev = cur;
        }
    }

    @Test void sameEvidenceIsCountedOncePerFactor() {
        Signal a = sig("a", SignalType.FAST_FORWARDING, Severity.MEDIUM, 0.8, "0xsame", now);
        Signal b = sig("b", SignalType.FAST_FORWARDING, Severity.MEDIUM, 0.8, "0xsame", now);
        RiskScore one = score(List.of(), List.of(a));
        RiskScore two = score(List.of(), List.of(a, b));
        assertThat(two.score()).isEqualTo(one.score());
        assertThat(two.contributions()).anyMatch(c -> c.points() == 0 && c.explanation().contains("same evidence"));
        // the same signal listed twice is also counted once
        assertThat(score(List.of(), List.of(a, a)).score()).isEqualTo(one.score());
        // distinct evidence does add
        Signal c = sig("c", SignalType.FAST_FORWARDING, Severity.MEDIUM, 0.8, "0xother", now);
        assertThat(score(List.of(), List.of(a, c)).score()).isGreaterThan(one.score());
    }

    @Test void factorIsCappedByItsWeight() {
        List<Signal> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(sig("i" + i, SignalType.INDIRECT_EXPOSURE, Severity.CRITICAL, 1.0, "0x" + i, now));
        assertThat(score(List.of(), many).score()).isEqualTo(35);
    }

    @Test void olderEvidenceWeighsLess() {
        int fresh = score(List.of(), List.of(sig("a", SignalType.STRUCTURING, Severity.MEDIUM, 0.8, "0x1", now))).score();
        int halfLife = score(List.of(), List.of(sig("a", SignalType.STRUCTURING, Severity.MEDIUM, 0.8, "0x1", now.minus(Duration.ofDays(180))))).score();
        int ancient = score(List.of(), List.of(sig("a", SignalType.STRUCTURING, Severity.MEDIUM, 0.8, "0x1", now.minus(Duration.ofDays(3000))))).score();
        assertThat(fresh).isEqualTo(20);      // 100 * 0.35 * 0.8 * 0.7 = 19.6
        assertThat(halfLife).isEqualTo(10);   // halved
        assertThat(ancient).isZero();
    }

    @Test void lowerReliabilitySourceWeighsLess() {
        int high = score(List.of(label(me, LabelCategory.SCAM, 0.9, now)), List.of()).score();
        int low = score(List.of(label(me, LabelCategory.SCAM, 0.3, now)), List.of()).score();
        assertThat(high).isGreaterThan(low);
    }

    @Test void noPropagationWithoutValueFlow() {
        // label of another address is not used
        RiskScore s = score(List.of(label(a("neighbour"), LabelCategory.SANCTIONED, 1.0, now)), List.of());
        assertThat(s.score()).isZero();
        // exposure signal without flow evidence is ignored
        Signal noFlow = new Signal("x", SignalType.INDIRECT_EXPOSURE, me, null, Severity.HIGH, 0.9, EvidenceLevel.RISK_SIGNAL,
                List.of(new Evidence("label", "listed", "demo://x", "demo", now)), "test@1", now);
        RiskScore t = score(List.of(), List.of(noFlow));
        assertThat(t.score()).isZero();
        assertThat(t.contributions()).singleElement().satisfies(c -> assertThat(c.explanation()).contains("without value-flow evidence"));
    }

    @Test void everyPointIsExplained() {
        RiskScore s = score(List.of(label(me, LabelCategory.MIXER, 0.6, now.minus(Duration.ofDays(20))),
                        label(me, LabelCategory.EXCHANGE, 0.9, now)),
                List.of(sig("a", SignalType.SANCTIONED_EXPOSURE, Severity.CRITICAL, 1.0, "0x1", now.minus(Duration.ofDays(3))),
                        sig("b", SignalType.FAST_FORWARDING, Severity.MEDIUM, 0.7, "0x2", now),
                        sig("c", SignalType.UNUSUAL_AMOUNT, Severity.LOW, 0.5, "0x3", now)));
        assertThat(s.contributions()).hasSize(5).allSatisfy(c -> {
            assertThat(c.factor()).isNotBlank();
            assertThat(c.signalId()).isNotBlank();
            assertThat(c.source()).isNotBlank();
            assertThat(c.explanation()).contains("confidence").contains("decay").contains("factor weight");
            assertThat(c.weight()).isBetween(0.0, 1.0);
            assertThat(c.ageSeconds()).isGreaterThanOrEqualTo(0);
        });
        double sum = s.contributions().stream().mapToDouble(RiskScore.Contribution::points).sum();
        assertThat(sum).isCloseTo(s.score(), within(0.6));
        assertThat(s.contributions()).filteredOn(c -> c.factor().equals("label:EXCHANGE"))
                .singleElement().satisfies(c -> assertThat(c.points()).isZero());
        assertThat(s.method()).contains("noisy-or");
        assertThat(s.modelVersion()).isEqualTo("oci-risk-1");
    }

    @Test void serviceKeepsHistoryOnlyOnChange() {
        var labels = new InMemoryLabelRepository();
        var signals = new InMemorySignalRepository();
        var repo = new InMemoryRiskScoreRepository();
        var svc = new RiskScoringService(engine, labels, signals, a -> null, repo, Clock.fixed(now, ZoneOffset.UTC));
        svc.rescore(me);
        svc.rescore(me);
        assertThat(svc.history(me)).hasSize(1);
        signals.saveIfAbsent(sig("a", SignalType.STRUCTURING, Severity.MEDIUM, 0.8, "0x1", now));
        svc.rescore(me);
        assertThat(svc.history(me)).extracting(RiskScore::score).containsExactly(0, 20);
        assertThat(svc.latest(me).orElseThrow().level()).isEqualTo(RiskLevel.LOW);
    }
}
