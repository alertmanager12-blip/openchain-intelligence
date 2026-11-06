package io.openchain.blockchain.indexer;

import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.BlockRef;
import io.openchain.core.model.TokenTransfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** TokenTransfer -> flat ChainEvent consumed by the rule engine. Field names are a public contract. */
public final class TransferEventMapper {
    private final TokenRegistry tokens;

    public TransferEventMapper(TokenRegistry tokens) { this.tokens = tokens; }

    public ChainEvent map(TokenTransfer t, BlockRef block, Instant ingestedAt) {
        Instant eventTime = t.timestamp() != null ? t.timestamp() : block.timestamp();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("network", t.network().id());
        f.put("transaction.hash", t.txHash());
        f.put("transaction.log_index", (long) t.logIndex());
        f.put("transaction.from", t.from().value());
        f.put("transaction.to", t.to().value());
        f.put("transaction.amount", t.amount());
        f.put("transaction.amount_raw", new BigDecimal(t.rawAmount()));
        f.put("transaction.token.symbol", t.token().symbol());
        if (t.token().contract() != null) f.put("transaction.token.contract", t.token().contract());
        f.put("transaction.token.decimals", (long) t.token().decimals());
        f.put("transaction.token.known", tokens.isKnown(t.token()));
        f.put("block.number", block.number());
        f.put("block.hash", block.hash());
        if (eventTime != null) f.put("block.timestamp", eventTime);
        return new ChainEvent(t.eventId(), ChainEvent.EventType.TOKEN_TRANSFER, t.network(), block.number(), block.hash(),
                eventTime, ingestedAt, false, f);
    }

    public static ChainEvent retraction(ChainEvent e, Instant ingestedAt) {
        return new ChainEvent(e.eventId(), e.type(), e.network(), e.blockNumber(), e.blockHash(), e.eventTime(),
                ingestedAt, true, e.fields());
    }
}
