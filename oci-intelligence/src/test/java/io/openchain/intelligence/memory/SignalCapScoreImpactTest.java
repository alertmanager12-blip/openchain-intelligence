package io.openchain.intelligence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.detection.InMemorySignalRepository;
import io.openchain.intelligence.label.InMemoryLabelRepository;
import io.openchain.intelligence.profile.WalletProfile;
import io.openchain.intelligence.risk.InMemoryRiskScoreRepository;
import io.openchain.intelligence.risk.RiskScoringEngine;
import io.openchain.intelligence.risk.RiskScoringService;
import io.openchain.intelligence.risk.ScoringConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P0 item 3, measurement: score of a very active wallet with every signal kept versus with the per-wallet cap of 64.
 * SYNTHETIC: 200 signals over 30 days (fast forwarding, fan-out, unusual amounts, structuring) and 3 HIGH exposures.
 * Prints the scores (PROBE| lines, used in docs/bench/SIGNAL_CAP_REPORT.md) and checks the HIGH evidence is kept.
 */
class SignalCapScoreImpactTest {
    static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    static final Address HOT = new Address(Network.TRON, "T" + "7".repeat(33));

    static List<Signal> signals() {
        SignalType[] weak = {SignalType.FAST_FORWARDING, SignalType.FLOW_CONCENTRATION, SignalType.UNUSUAL_AMOUNT, SignalType.STRUCTURING};
        Severity[] sev = {Severity.MEDIUM, Severity.LOW, Severity.LOW, Severity.MEDIUM};
        List<Signal> out = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Instant at = NOW.minus(Duration.ofMinutes(i * 216L)); // spread over 30 days
            int k = i % 4;
            out.add(new Signal("s" + i, weak[k], HOT, "0xs" + i, sev[k], 0.7, Signal.EvidenceLevel.RISK_SIGNAL,
                    List.of(new Signal.Evidence("flow", "flow " + i, "0xs" + i, "chain:tron", at)), "test@1", at));
        }
        for (int i = 0; i < 3; i++) {
            Instant at = NOW.minus(Duration.ofDays(10L * i + 1));
            out.add(new Signal("x" + i, SignalType.MIXER_EXPOSURE, HOT, "0xx" + i, Severity.HIGH, 0.9, Signal.EvidenceLevel.RISK_SIGNAL,
                    List.of(new Signal.Evidence("flow", "from mixer " + i, "0xx" + i, "chain:tron", at)), "test@1", at));
        }
        return out;
    }

    static RiskScore score(InMemorySignalRepository repo) {
        signals().forEach(repo::saveIfAbsent);
        var svc = new RiskScoringService(new RiskScoringEngine(ScoringConfig.defaults()), new InMemoryLabelRepository(), repo,
                a -> WalletProfile.empty(a, NOW), new InMemoryRiskScoreRepository(), Clock.fixed(NOW, ZoneOffset.UTC));
        return svc.rescore(HOT);
    }

    @Test void perWalletCapKeepsTheGraveEvidenceAndBarelyMovesTheScore() {
        var full = new InMemorySignalRepository(1_000_000);
        var capped = new InMemorySignalRepository(64);
        RiskScore all = score(full), cap = score(capped);
        System.out.println("PROBE|signals kept: all=" + full.findByAddress(HOT).size() + " capped=" + capped.findByAddress(HOT).size());
        System.out.println("PROBE|score all=" + all.score() + " " + all.level() + " capped=" + cap.score() + " " + cap.level());
        java.util.Map<String, Double> fa = new java.util.TreeMap<>(), fc = new java.util.TreeMap<>();
        all.contributions().forEach(c -> fa.merge(c.factor(), c.points(), Double::sum));
        cap.contributions().forEach(c -> fc.merge(c.factor(), c.points(), Double::sum));
        fa.forEach((f, p) -> System.out.printf(java.util.Locale.ROOT, "PROBE|factor %s all=%.2f capped=%.2f%n", f, p, fc.getOrDefault(f, 0.0)));
        assertThat(capped.findByAddress(HOT)).filteredOn(s -> s.severity() == Severity.HIGH).hasSize(3);
        assertThat(cap.score()).isLessThanOrEqualTo(all.score());
        // no kind of evidence disappears (the first policy, by severity only, wiped fan-out and unusual-amount: 83 -> 77)
        assertThat(fc.keySet()).isEqualTo(fa.keySet());
        assertThat(all.score() - cap.score()).isLessThanOrEqualTo(1);
    }
}
