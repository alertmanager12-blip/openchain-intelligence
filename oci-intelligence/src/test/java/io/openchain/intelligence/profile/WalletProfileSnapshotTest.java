package io.openchain.intelligence.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.snapshot.SnapshotIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * ADR-0024 step 3d: a wallet profile written to a snapshot and read back is the same profile. Not only equal figures:
 * the bounded structures (remembered keys, folded activity, pruned counterparties) must behave the same afterwards, or
 * the next events would be counted differently after a restart.
 */
class WalletProfileSnapshotTest {
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Address ME = new Address(Network.TRON, "TMe00000000000000000000000000000000");
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Token TRX = Token.nativeOf(Network.TRON);

    static Address other(int i) { return new Address(Network.TRON, "TOther" + i); }

    static ValueFlow flow(int i, boolean in, int counterparty, Token token, long seconds) {
        Address cp = other(counterparty);
        return new ValueFlow("tron:tx" + i + ":0", Network.TRON, "tx" + (i / 2), T0.plusSeconds(seconds),
                in ? cp : ME, in ? ME : cp, token, BigInteger.valueOf(1_000 + i));
    }

    static byte[] bytes(WalletProfileAccumulator a) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        a.writeTo(new DataOutputStream(b));
        return b.toByteArray();
    }

    static WalletProfileAccumulator read(byte[] b, UnaryOperator<Address> canonical) throws IOException {
        return WalletProfileAccumulator.readFrom(new DataInputStream(new ByteArrayInputStream(b)), canonical);
    }

    static WalletProfile profile(WalletProfileAccumulator a) { return a.build(50, List.of(), T0.plusSeconds(10_000_000)); }

    /** A busy wallet: every cap of the accumulator is exceeded (keys, activity, counterparties). */
    static WalletProfileAccumulator busy() {
        WalletProfileAccumulator a = new WalletProfileAccumulator(ME);
        for (int i = 0; i < 6_000; i++) {
            a.apply(flow(i, i % 3 != 0, i % 2_500, i % 5 == 0 ? TRX : USDT, i * 7L + (i % 11 == 0 ? 86_400 * 3 : 0)));
        }
        a.markStartUnknown();
        return a;
    }

    @Test void aSnapshotRoundTripIsExactAndDeterministic() throws IOException {
        WalletProfileAccumulator a = busy();
        assertThat(a.retainedEntries()).isGreaterThan(WalletProfileAccumulator.RECENT_KEYS); // caps really reached
        byte[] b = bytes(a);
        WalletProfileAccumulator r = read(b, x -> x);
        assertThat(bytes(r)).isEqualTo(b);                 // same state, same bytes
        assertThat(profile(r)).isEqualTo(profile(a));     // same figures
        assertThat(r.startUnknown()).isTrue();
        assertThat(r.eventKeys()).isEqualTo(a.eventKeys()); // same keys, same order (decides what is forgotten next)
        assertThat(r.txKeys()).isEqualTo(a.txKeys());
    }

    @Test void theRestoredProfileBehavesLikeTheOriginal() throws IOException {
        WalletProfileAccumulator a = busy();
        WalletProfileAccumulator r = read(bytes(a), x -> x);
        for (int i = 6_000; i < 9_000; i++) { // new events after the restart, a replay and reverts among them
            ValueFlow f = flow(i, i % 2 == 0, i % 3_000, USDT, i * 5L);
            assertThat(r.apply(f)).isEqualTo(a.apply(f));
        }
        ValueFlow recent = flow(8_999, false, 8_999 % 3_000, USDT, 8_999 * 5L), forgotten = flow(1, true, 1, USDT, 7L);
        assertThat(r.apply(recent)).isEqualTo(a.apply(recent)).isFalse();      // remembered: a duplicate
        assertThat(r.revert(recent)).isEqualTo(a.revert(recent)).isTrue();
        assertThat(r.apply(forgotten)).isEqualTo(a.apply(forgotten));          // beyond the remembered keys: same answer
        assertThat(profile(r)).isEqualTo(profile(a));
        assertThat(bytes(r)).isEqualTo(bytes(a));
    }

    @Test void addressesAreCanonicalisedOnRead() throws IOException {
        java.util.Map<Address, Address> pool = new java.util.HashMap<>();
        WalletProfileAccumulator r = read(bytes(busy()), x -> pool.computeIfAbsent(x, k -> k));
        assertThat(r.address()).isSameAs(pool.get(ME));
        assertThat(profile(r).topCounterparties()).allSatisfy(c -> assertThat(c.counterparty()).isSameAs(pool.get(c.counterparty())));
    }

    @Test void anUnknownVersionOrTruncatedDataIsRefusedNeverHalfRead() throws IOException {
        byte[] b = bytes(busy());
        byte[] future = b.clone();
        future[0] = (byte) (WalletProfileAccumulator.SNAPSHOT_FORMAT + 1);
        assertThatThrownBy(() -> read(future, x -> x)).isInstanceOf(SnapshotIO.SnapshotFormatException.class)
                .hasMessageContaining("format version");
        byte[] truncated = java.util.Arrays.copyOf(b, b.length / 2);
        assertThatThrownBy(() -> read(truncated, x -> x)).isInstanceOfAny(java.io.EOFException.class, SnapshotIO.SnapshotFormatException.class);
    }
}
