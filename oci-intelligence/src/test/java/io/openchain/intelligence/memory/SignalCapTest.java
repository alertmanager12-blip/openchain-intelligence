package io.openchain.intelligence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.detection.InMemorySignalRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P0 item 3: the signal store is bounded per wallet and globally, HIGH/CRITICAL removed last, every removal counted. */
class SignalCapTest {
    static final Instant T = Instant.parse("2026-11-01T00:00:00Z");
    static Address w(int i) { return new Address(Network.TRON, "T" + String.format("%033d", i)); }

    static Signal sig(String id, Address a, Severity sev, long minute) {
        return new Signal(id, SignalType.FAST_FORWARDING, a, "0x" + id, sev, 0.7, Signal.EvidenceLevel.RISK_SIGNAL,
                List.of(new Signal.Evidence("flow", "f", "0x" + id, "chain:tron", T.plusSeconds(60 * minute))), "test@1", T.plusSeconds(60 * minute));
    }

    @Test void perWalletCapRemovesLowestSeverityThenOldestAndCountsIt() {
        var repo = new InMemorySignalRepository(4);
        Address a = w(1);
        repo.saveIfAbsent(sig("h1", a, Severity.HIGH, 0));
        repo.saveIfAbsent(sig("l1", a, Severity.LOW, 1));
        repo.saveIfAbsent(sig("c1", a, Severity.CRITICAL, 2));
        repo.saveIfAbsent(sig("l2", a, Severity.LOW, 3));
        repo.saveIfAbsent(sig("m1", a, Severity.MEDIUM, 4));   // 5th: the oldest LOW goes
        repo.saveIfAbsent(sig("h2", a, Severity.HIGH, 5));     // 5th again: the other LOW goes
        assertThat(repo.findByAddress(a)).extracting(Signal::id).containsExactlyInAnyOrder("h1", "c1", "m1", "h2");
        assertThat(repo.evictionCounts()).containsEntry("PER_WALLET_CAP|LOW|FAST_FORWARDING", 2L);
    }

    @Test void aLowSignalArrivingInAWalletFullOfGraveOnesIsNotAnnouncedAsStored() {
        var repo = new InMemorySignalRepository(2);
        Address a = w(2);
        repo.saveIfAbsent(sig("h1", a, Severity.HIGH, 0));
        repo.saveIfAbsent(sig("h2", a, Severity.HIGH, 1));
        assertThat(repo.saveIfAbsent(sig("l1", a, Severity.LOW, 2))).isFalse();
        assertThat(repo.findById("l1")).isEmpty();
        assertThat(repo.findByAddress(a)).extracting(Signal::id).containsExactly("h1", "h2");
        assertThat(repo.evictionCounts()).containsEntry("PER_WALLET_CAP|LOW|FAST_FORWARDING", 1L);
    }

    @Test void everyKindOfEvidenceKeepsItsLatestSignals() {
        var repo = new InMemorySignalRepository(8);
        Address a = w(30);
        for (int i = 0; i < 40; i++)
            repo.saveIfAbsent(new Signal("ff" + i, SignalType.FAST_FORWARDING, a, "0xff" + i, Severity.MEDIUM, 0.7, Signal.EvidenceLevel.RISK_SIGNAL,
                    List.of(), "test@1", T.plusSeconds(60L * i)));
        repo.saveIfAbsent(new Signal("fan", SignalType.FLOW_CONCENTRATION, a, "0xfan", Severity.LOW, 0.6, Signal.EvidenceLevel.RISK_SIGNAL,
                List.of(), "test@1", T));
        for (int i = 40; i < 60; i++)
            repo.saveIfAbsent(new Signal("ff" + i, SignalType.FAST_FORWARDING, a, "0xff" + i, Severity.MEDIUM, 0.7, Signal.EvidenceLevel.RISK_SIGNAL,
                    List.of(), "test@1", T.plusSeconds(60L * i)));
        // the single, old, LOW fan-out signal survives: the crowded type gives way first
        assertThat(repo.findByAddress(a)).extracting(Signal::id).contains("fan", "ff59").doesNotContain("ff0");
    }

    @Test void aVeryActiveWalletCannotMonopoliseTheStore() {
        var repo = new InMemorySignalRepository(64);
        for (int i = 0; i < 1_000; i++) repo.saveIfAbsent(sig("hot" + i, w(3), Severity.MEDIUM, i));
        for (int i = 0; i < 10; i++) repo.saveIfAbsent(sig("other" + i, w(4 + i), Severity.LOW, i));
        assertThat(repo.findByAddress(w(3))).hasSize(64);
        assertThat(repo.size()).isEqualTo(74);
        assertThat(repo.findByAddress(w(3))).extracting(Signal::id).contains("hot999").doesNotContain("hot0");
    }

    @Test void globalCapIsExactAndFollowsTheTiersWithExemptionsUncounted() {
        var repo = new InMemorySignalRepository(64);
        Address noProfile = w(10), profiled = w(11), watched = w(12);
        repo.saveIfAbsent(sig("n1", noProfile, Severity.CRITICAL, 0));   // tier 0 even if critical: no profile left
        repo.saveIfAbsent(sig("p-low", profiled, Severity.LOW, 1));     // tier 1
        repo.saveIfAbsent(sig("p-med", profiled, Severity.MEDIUM, 2));  // tier 1
        repo.saveIfAbsent(sig("p-high", profiled, Severity.HIGH, 3));   // tier 2
        repo.saveIfAbsent(sig("w1", watched, Severity.LOW, 4));         // exempt
        int removed = repo.evictToCap(2, s -> s.address().equals(watched),
                s -> s.address().equals(noProfile) ? 0 : s.severity().compareTo(Severity.HIGH) < 0 ? 1 : 2);
        assertThat(removed).isEqualTo(2);
        assertThat(repo.size()).isEqualTo(3); // 2 under the cap + 1 exempt
        assertThat(repo.findById("n1")).isEmpty();
        assertThat(repo.findById("p-low")).isEmpty();
        assertThat(repo.findById("p-med")).isPresent();
        assertThat(repo.findById("p-high")).isPresent();
        assertThat(repo.findById("w1")).isPresent();
        assertThat(repo.evictionCounts()).containsEntry("GLOBAL_CAP_NO_PROFILE|CRITICAL|FAST_FORWARDING", 1L)
                .containsEntry("GLOBAL_CAP_LOW_SEVERITY|LOW|FAST_FORWARDING", 1L);
        assertThat(repo.evictToCap(2, s -> s.address().equals(watched), s -> 1)).as("already at the cap").isZero();
    }

    @Test void highSeverityGoesOnlyWhenNothingWeakerRemains() {
        var repo = new InMemorySignalRepository(64);
        repo.saveIfAbsent(sig("h-old", w(20), Severity.HIGH, 0));
        repo.saveIfAbsent(sig("c-new", w(21), Severity.CRITICAL, 5));
        repo.saveIfAbsent(sig("m-new", w(22), Severity.MEDIUM, 9));
        repo.evictToCap(1, s -> false, s -> s.severity().compareTo(Severity.HIGH) < 0 ? 1 : 2);
        assertThat(repo.findById("c-new")).isPresent();
        assertThat(repo.evictionCounts()).containsEntry("GLOBAL_CAP_LOW_SEVERITY|MEDIUM|FAST_FORWARDING", 1L)
                .containsEntry("GLOBAL_CAP_HIGH_SEVERITY|HIGH|FAST_FORWARDING", 1L);
    }
}
