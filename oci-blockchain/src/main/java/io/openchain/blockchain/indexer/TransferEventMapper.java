package io.openchain.blockchain.indexer;

import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.BlockRef;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.ContractAdminChange;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenApproval;
import io.openchain.core.model.TokenFreeze;
import io.openchain.core.model.TokenTransfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Indexed items -> flat ChainEvents consumed by detection and the rule engine. Field names are a public contract
 * (see docs/architecture/data-model.md). Event ids: {@code network:txHash:logIndex} for logs (transfers, approvals,
 * freezes), {@code network:txHash:native} for the native value of a transaction.
 */
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

    /** Native value of a successful transaction (caller guarantees success and value > 0). */
    public ChainEvent mapNative(ChainTransaction tx, BlockRef block, Instant ingestedAt) {
        Token nat = Token.nativeOf(tx.network());
        Instant eventTime = tx.timestamp() != null ? tx.timestamp() : block.timestamp();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("network", tx.network().id());
        f.put("transaction.hash", tx.hash());
        f.put("transaction.from", tx.from().value());
        f.put("transaction.to", tx.to().value());
        f.put("transaction.amount", new BigDecimal(tx.value(), nat.decimals()));
        f.put("transaction.amount_raw", new BigDecimal(tx.value()));
        f.put("transaction.token.symbol", nat.symbol());
        f.put("transaction.token.decimals", (long) nat.decimals());
        f.put("transaction.token.known", true);
        if (tx.fee() != null) f.put("transaction.fee", new BigDecimal(tx.fee(), nat.decimals()));
        f.put("transaction.success", tx.success());
        f.put("transaction.contract_interaction", tx.contractInteraction());
        if (tx.methodSelector() != null) f.put("transaction.method", tx.methodSelector());
        block(f, block, eventTime);
        return new ChainEvent(nativeEventId(tx), ChainEvent.EventType.NATIVE_TRANSFER, tx.network(), block.number(), block.hash(),
                eventTime, ingestedAt, false, f);
    }

    public static String nativeEventId(ChainTransaction tx) { return tx.network().id() + ":" + tx.hash() + ":native"; }

    /** Fields read by UnusualApprovalDetector ({@code approval.owner/spender/amount_raw}, {@code token.*}) + rule fields. */
    public ChainEvent mapApproval(TokenApproval a, BlockRef block, Instant ingestedAt) {
        Instant eventTime = a.timestamp() != null ? a.timestamp() : block.timestamp();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("network", a.network().id());
        f.put("transaction.hash", a.txHash());
        f.put("transaction.log_index", (long) a.logIndex());
        f.put("approval.owner", a.owner().value());
        f.put("approval.spender", a.spender().value());
        f.put("approval.amount", a.amount());
        f.put("approval.amount_raw", new BigDecimal(a.rawAmount()));
        f.put("approval.unlimited", a.isUnlimited());
        token(f, a.token());
        block(f, block, eventTime);
        return new ChainEvent(a.eventId(), ChainEvent.EventType.APPROVAL, a.network(), block.number(), block.hash(),
                eventTime, ingestedAt, false, f);
    }

    public ChainEvent mapFreeze(TokenFreeze z, BlockRef block, Instant ingestedAt) {
        Instant eventTime = z.timestamp() != null ? z.timestamp() : block.timestamp();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("network", z.network().id());
        f.put("transaction.hash", z.txHash());
        f.put("transaction.log_index", (long) z.logIndex());
        f.put("freeze.address", z.target().value());
        f.put("freeze.token", z.token().symbol());
        f.put("freeze.token_contract", z.token().contract());
        f.put("freeze.action", z.action().name());
        f.put("freeze.issuer", z.issuer());
        f.put("freeze.event", z.eventName());
        token(f, z.token());
        block(f, block, eventTime);
        ChainEvent.EventType type = z.action() == TokenFreeze.Action.FREEZE ? ChainEvent.EventType.STABLECOIN_FREEZE
                : ChainEvent.EventType.STABLECOIN_UNFREEZE;
        return new ChainEvent(z.eventId(), type, z.network(), block.number(), block.hash(), eventTime, ingestedAt, false, f);
    }

    /**
     * CONTRACT_ADMIN_CHANGE: fields read by OwnershipChangeDetector ({@code admin.contract/previous/new/role}) and the
     * rule catalog's {@code contract.address/event/new_owner} (new owner, admin or implementation).
     */
    public ChainEvent mapAdminChange(ContractAdminChange c, BlockRef block, Instant ingestedAt) {
        Instant eventTime = c.timestamp() != null ? c.timestamp() : block.timestamp();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("network", c.network().id());
        f.put("transaction.hash", c.txHash());
        f.put("transaction.log_index", (long) c.logIndex());
        f.put("admin.contract", c.contract().value());
        if (c.previous() != null) f.put("admin.previous", c.previous().value());
        f.put("admin.new", c.newValue().value());
        f.put("admin.role", c.role());
        f.put("admin.renounced", c.newValue().isZero());
        f.put("contract.address", c.contract().value());
        f.put("contract.event", c.eventName());
        f.put("contract.new_owner", c.newValue().value());
        tokens.find(c.network(), c.contract().value()).ifPresent(t -> token(f, t));
        block(f, block, eventTime);
        return new ChainEvent(c.eventId(), ChainEvent.EventType.CONTRACT_ADMIN_CHANGE, c.network(), block.number(), block.hash(),
                eventTime, ingestedAt, false, f);
    }

    private void token(Map<String, Object> f, Token t) {
        f.put("token.symbol", t.symbol());
        if (t.contract() != null) f.put("token.contract", t.contract());
        f.put("transaction.token.symbol", t.symbol());
        if (t.contract() != null) f.put("transaction.token.contract", t.contract());
        f.put("transaction.token.decimals", (long) t.decimals());
        f.put("transaction.token.known", tokens.isKnown(t));
    }

    private static void block(Map<String, Object> f, BlockRef block, Instant eventTime) {
        f.put("block.number", block.number());
        f.put("block.hash", block.hash());
        if (eventTime != null) f.put("block.timestamp", eventTime);
    }

    public static ChainEvent retraction(ChainEvent e, Instant ingestedAt) {
        return new ChainEvent(e.eventId(), e.type(), e.network(), e.blockNumber(), e.blockHash(), e.eventTime(),
                ingestedAt, true, e.fields());
    }
}
