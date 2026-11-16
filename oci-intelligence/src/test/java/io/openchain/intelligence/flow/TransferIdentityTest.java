package io.openchain.intelligence.flow;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Fund Flow Trace P0-B, case C7: the same TRC-20 transfer seen live and by TronGrid history counts once. */
class TransferIdentityTest {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T = Instant.parse("2026-11-16T10:00:00Z");
    static final String TX = "a".repeat(64);
    static final Address A = new Address(Network.TRON, "TAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), B = new Address(Network.TRON, "TBbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    final InMemoryFlowStore store = new InMemoryFlowStore();

    static ValueFlow live(int logIndex, Address to, long raw) {
        return new ValueFlow("tron:" + TX + ":" + logIndex, Network.TRON, TX, T, A, to, USDT, BigInteger.valueOf(raw));
    }

    static ValueFlow history(int n, Address to, long raw) {
        return new ValueFlow("tron:" + TX + ":trc20:0123456789abcdef:" + n, Network.TRON, TX, T, A, to, USDT, BigInteger.valueOf(raw));
    }

    @Test void aHistoryTransferAlreadyIndexedLiveIsRecognised() {
        ValueFlow l = live(3, B, 1_000_000);
        store.add(l);
        assertThat(TransferIdentity.counterpart(history(0, B, 1_000_000), store)).hasValue(l);
    }

    @Test void aLiveTransferRecognisesTheHistoryCopyLoadedBefore() {
        ValueFlow h = history(0, B, 1_000_000);
        store.add(h);
        assertThat(TransferIdentity.counterpart(live(7, B, 1_000_000), store)).hasValue(h);
    }

    @Test void twoIdenticalTransfersOfOneTransactionStayTwo() {
        ValueFlow first = live(1, B, 5), second = live(4, B, 5);
        store.add(first);
        store.add(second);
        assertThat(TransferIdentity.counterpart(history(0, B, 5), store)).hasValue(first);
        assertThat(TransferIdentity.counterpart(history(1, B, 5), store)).hasValue(second);
        assertThat(TransferIdentity.counterpart(history(2, B, 5), store)).as("a third one only history saw").isEmpty();
    }

    @Test void aDifferentAmountRecipientOrTransactionIsAnotherTransfer() {
        store.add(live(3, B, 1_000_000));
        assertThat(TransferIdentity.counterpart(history(0, B, 999_999), store)).isEmpty();
        assertThat(TransferIdentity.counterpart(history(0, A, 1_000_000), store)).isEmpty();
        ValueFlow otherTx = new ValueFlow("tron:" + "b".repeat(64) + ":trc20:0123456789abcdef:0", Network.TRON, "b".repeat(64), T, A, B, USDT,
                BigInteger.valueOf(1_000_000));
        assertThat(TransferIdentity.counterpart(otherTx, store)).isEmpty();
    }

    @Test void nativeTransfersAndOrdinaryIdsAreNotConcerned() {
        Token trx = Token.nativeOf(Network.TRON, "TRX", 6);
        ValueFlow n = new ValueFlow("tron:" + TX + ":native", Network.TRON, TX, T, A, B, trx, BigInteger.TEN);
        store.add(n);
        assertThat(TransferIdentity.counterpart(new ValueFlow("tron:" + TX + ":native", Network.TRON, TX, T, A, B, trx, BigInteger.TEN), store)).isEmpty();
        assertThat(TransferIdentity.counterpart(live(3, B, 1), store)).isEmpty();
    }
}
