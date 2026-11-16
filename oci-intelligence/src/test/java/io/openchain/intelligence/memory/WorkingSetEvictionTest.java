package io.openchain.intelligence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.risk.RiskLevel;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.detection.InMemorySignalRepository;
import io.openchain.intelligence.flow.InMemoryFlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.profile.WalletProfileService;
import io.openchain.intelligence.risk.InMemoryRiskScoreRepository;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** ADR-0024 step 3: the in-memory working set is bounded, kept addresses excepted. */
class WorkingSetEvictionTest {
    static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static Address a(String s) { return new Address(Network.TRON, "T" + s.repeat(33).substring(0, 33)); }
    static final Address OLD_A = a("1"), OLD_B = a("2"), KEPT = a("3"), NEW_A = a("4"), NEW_B = a("5");

    static ValueFlow flow(String id, Address from, Address to, Instant t) {
        return new ValueFlow(id, Network.TRON, "0x" + id, t, from, to, USDT, BigInteger.TEN);
    }

    @Test void oldFlowsGoUnlessTheyTouchAKeptAddress() {
        var store = new InMemoryFlowStore();
        store.add(flow("old", OLD_A, OLD_B, NOW.minusSeconds(3 * 86400)));
        store.add(flow("old-kept", OLD_A, KEPT, NOW.minusSeconds(3 * 86400)));
        store.add(flow("new", NEW_A, NEW_B, NOW.minusSeconds(3600)));
        assertThat(store.evictBefore(n -> NOW.minusSeconds(86400), KEPT::equals)).isEqualTo(1);
        assertThat(store.contains("old")).isFalse();
        assertThat(store.contains("old-kept")).isTrue();
        assertThat(store.contains("new")).isTrue();
        assertThat(store.outgoing(OLD_A)).extracting(ValueFlow::eventId).containsExactly("old-kept");
        assertThat(store.incoming(OLD_B)).isEmpty();
        assertThat(store.size()).isEqualTo(2);
    }

    @Test void oldLowSeveritySignalsGoHighOnesAndKeptAddressesStay() {
        var repo = new InMemorySignalRepository();
        Signal low = signal("s1", OLD_A, Severity.LOW, NOW.minusSeconds(3 * 86400));
        Signal high = signal("s2", OLD_A, Severity.HIGH, NOW.minusSeconds(3 * 86400));
        Signal kept = signal("s3", KEPT, Severity.LOW, NOW.minusSeconds(3 * 86400));
        Signal recent = signal("s4", NEW_A, Severity.LOW, NOW.minusSeconds(60));
        List.of(low, high, kept, recent).forEach(repo::saveIfAbsent);
        assertThat(repo.evictBefore(NOW.minusSeconds(2 * 86400),
                s -> s.address().equals(KEPT) || s.severity().compareTo(Severity.HIGH) >= 0)).isEqualTo(1);
        assertThat(repo.findById("s1")).isEmpty();
        assertThat(repo.findByAddress(OLD_A)).containsExactly(high);
        assertThat(repo.size()).isEqualTo(3);
    }

    @Test void idleProfilesAndTheirScoresGo() {
        var profiles = new WalletProfileService(Clock.fixed(NOW, ZoneOffset.UTC));
        profiles.ingest(flow("old", OLD_A, KEPT, NOW.minusSeconds(10 * 86400)));
        profiles.ingest(flow("new", NEW_A, NEW_B, NOW.minusSeconds(3600)));
        Set<Address> gone = profiles.evictIdle(n -> NOW.minusSeconds(7 * 86400), KEPT::equals);
        assertThat(gone).containsExactly(OLD_A);
        assertThat(profiles.profile(OLD_A)).isEmpty();
        assertThat(profiles.profile(KEPT)).isPresent();
        assertThat(profiles.profile(NEW_A)).isPresent();

        var scores = new InMemoryRiskScoreRepository();
        scores.append(new RiskScore(OLD_A, 10, RiskLevel.LOW, List.of(), "m", "v", NOW));
        assertThat(scores.forget(gone)).isEqualTo(1);
        assertThat(scores.latest(OLD_A)).isEmpty();
    }

    @Test void aNetworkWithoutCutoffIsNotTrimmed() {
        var store = new InMemoryFlowStore();
        store.add(flow("old", OLD_A, OLD_B, NOW.minusSeconds(3 * 86400)));
        assertThat(store.evictBefore(n -> null, a -> false)).isZero();
        var profiles = new WalletProfileService(Clock.fixed(NOW, ZoneOffset.UTC));
        profiles.ingest(flow("old", OLD_A, OLD_B, NOW.minusSeconds(10 * 86400)));
        assertThat(profiles.evictIdle(n -> null, a -> false)).isEmpty();
    }

    /** A flow for an evicted wallet starts a fresh profile; it is never applied to the removed accumulator. */
    @Test void aFlowAfterEvictionStartsAFreshProfile() {
        var profiles = new WalletProfileService(Clock.fixed(NOW, ZoneOffset.UTC));
        profiles.ingest(flow("old", OLD_A, OLD_B, NOW.minusSeconds(10 * 86400)));
        assertThat(profiles.evictIdle(n -> NOW.minusSeconds(7 * 86400), a -> false)).contains(OLD_A);
        profiles.ingest(flow("new", OLD_A, NEW_A, NOW.minusSeconds(60)));
        assertThat(profiles.profile(OLD_A)).hasValueSatisfying(p -> assertThat(p.transactionCount()).isEqualTo(1));
    }

    /**
     * The race between ingestion and eviction, made deterministic: the writer has looked the accumulator up and waits for
     * its lock while the trim (same thread as the lock holder, the monitor is reentrant) removes it. The flow must land in a
     * fresh profile, never in the removed accumulator.
     */
    @Test void aFlowRacingTheEvictionOfItsWalletIsNotLost() throws Exception {
        var profiles = new WalletProfileService(Clock.fixed(NOW, ZoneOffset.UTC));
        profiles.ingest(flow("old", OLD_A, OLD_B, NOW.minusSeconds(10 * 86400)));
        var field = WalletProfileService.class.getDeclaredField("accumulators");
        field.setAccessible(true);
        Object acc = ((java.util.Map<?, ?>) field.get(profiles)).get(OLD_A);
        Thread writer;
        synchronized (acc) {
            writer = Thread.ofPlatform().start(() -> profiles.ingest(flow("new", OLD_A, NEW_A, NOW.minusSeconds(60))));
            while (writer.getState() != Thread.State.BLOCKED) Thread.onSpinWait();
            assertThat(profiles.evictIdle(n -> NOW.minusSeconds(7 * 86400), a -> false)).contains(OLD_A);
        }
        writer.join(5_000);
        assertThat(profiles.profile(OLD_A)).hasValueSatisfying(p -> assertThat(p.lastActivity()).isEqualTo(NOW.minusSeconds(60)));
    }

    static Signal signal(String id, Address a, Severity sev, Instant at) {
        return new Signal(id, SignalType.UNUSUAL_AMOUNT, a, null, sev, 0.5, Signal.EvidenceLevel.ANOMALY, List.of(), "test@1", at);
    }

    // ---- ADR-0024 step 3b: hard caps, oldest first, kept addresses excepted

    static Address w(int i) { return new Address(Network.TRON, "T" + String.format("%033d", i)); }

    @Test void flowCapRemovesTheOldestFirst() {
        var store = new InMemoryFlowStore();
        for (int i = 0; i < 10; i++) store.add(flow("f" + i, w(i), w(100 + i), NOW.plusSeconds(i)));
        store.add(flow("kept-old", KEPT, w(999), NOW.minusSeconds(3600)));
        assertThat(store.evictOldest(4, KEPT::equals)).isEqualTo(6);
        assertThat(store.size()).isEqualTo(5);
        assertThat(store.contains("kept-old")).isTrue();
        assertThat(store.contains("f5")).isFalse();
        assertThat(store.contains("f6")).isTrue();
        assertThat(store.contains("f9")).isTrue();
        assertThat(store.evictOldest(4, KEPT::equals)).isZero();
    }

    @Test void signalCapRemovesTheOldestRemovableOnesWhateverTheirSeverity() {
        var repo = new InMemorySignalRepository();
        for (int i = 0; i < 6; i++) {
            repo.saveIfAbsent(new Signal("s" + i, SignalType.UNUSUAL_AMOUNT, w(i), null, i % 2 == 0 ? Severity.HIGH : Severity.LOW, 0.5,
                    Signal.EvidenceLevel.ANOMALY, List.of(), "test@1", NOW.plusSeconds(i)));
        }
        repo.saveIfAbsent(new Signal("s-kept", SignalType.UNUSUAL_AMOUNT, KEPT, null, Severity.LOW, 0.5,
                Signal.EvidenceLevel.ANOMALY, List.of(), "test@1", NOW.minusSeconds(3600)));
        assertThat(repo.evictOldest(2, sig -> sig.address().equals(KEPT))).isEqualTo(4);
        assertThat(repo.findById("s-kept")).isPresent();
        assertThat(repo.findById("s3")).isEmpty();
        assertThat(repo.findById("s4")).isPresent();
        assertThat(repo.findById("s5")).isPresent();
    }

    @Test void profileCapRemovesTheLeastRecentlyActiveWallets() {
        var profiles = new WalletProfileService(Clock.fixed(NOW, ZoneOffset.UTC));
        for (int i = 0; i < 5; i++) profiles.ingest(flow("p" + i, w(i), w(i), NOW.plusSeconds(i))); // self transfers: one profile each
        profiles.ingest(flow("p-kept", KEPT, KEPT, NOW.minusSeconds(3600)));
        Set<Address> gone = profiles.evictLeastRecent(2, KEPT::equals);
        assertThat(gone).containsExactlyInAnyOrder(w(0), w(1), w(2));
        assertThat(profiles.has(KEPT)).isTrue();
        assertThat(profiles.has(w(3))).isTrue();
        assertThat(profiles.size()).isEqualTo(3);
    }
}
