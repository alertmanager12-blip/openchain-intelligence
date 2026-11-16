package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.ApiTestSupport;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/** Fund Flow Trace P0-B, case C7, through the address history sink: a TRC-20 transfer already indexed live counts once. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // adds flows: never leak them to other classes
class HistoryDuplicateTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T = Instant.parse("2026-11-16T10:00:00Z");
    static final Address A = new Address(Network.TRON, "THistDupAaaaaaaaaaaaaaaaaaaaaaaaaa"), B = new Address(Network.TRON, "THistDupBbbbbbbbbbbbbbbbbbbbbbbbbb");

    @Autowired ChainIngestionService chains;
    @Autowired IntelligencePipeline intel;

    static ChainEvent historyTransfer(String tx, int n, Address to, long raw) {
        Map<String, Object> f = Map.of("transaction.hash", tx, "transaction.from", A.value(), "transaction.to", to.value(),
                "transaction.amount_raw", BigInteger.valueOf(raw), "transaction.token.contract", USDT.contract(),
                "transaction.token.symbol", "USDT", "transaction.token.decimals", 6);
        return new ChainEvent("tron:" + tx + ":trc20:0123456789abcdef:" + n, ChainEvent.EventType.TOKEN_TRANSFER, Network.TRON, -1, null,
                T, T, false, f);
    }

    @Test void aHistoryTransferAlreadyIndexedLiveIsNotAppliedAgain() {
        String tx = "c".repeat(64);
        intel.onFlow(new ValueFlow("tron:" + tx + ":2", Network.TRON, tx, T, A, B, USDT, BigInteger.valueOf(7_000_000)));
        int before = intel.flows().involving(A).size();
        long skipped = chains.historyDuplicatesSkipped();

        String other = "d".repeat(64);
        chains.applyHistory(List.of(historyTransfer(tx, 0, B, 7_000_000), historyTransfer(other, 0, B, 7_000_000)));

        assertThat(chains.historyDuplicatesSkipped()).isEqualTo(skipped + 1);
        assertThat(intel.flows().involving(A)).hasSize(before + 1)
                .extracting(ValueFlow::txHash).containsOnlyOnce(tx).contains(other);
    }
}
