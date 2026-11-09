package io.openchain.app.api;

import io.openchain.core.event.ChainEvent.EventType;
import java.util.EnumSet;
import java.util.Set;

/**
 * Catalog fields and event types that the current ingestion pipeline actually produces (TransferEventMapper +
 * ChainIngestionService enrichment + detection signals + INTERNAL_EVENT connector). Anything else in the
 * FieldCatalog is accepted by the validator but never populated yet, so a condition on it never matches.
 * Keep in sync with ChainIngestionService#enrich / #signalEvent.
 */
public final class PopulatedFields {

    private PopulatedFields() {}

    public static final Set<String> FIELDS = Set.of(
            // envelope (virtual)
            "network", "event.type", "event.block_number", "event.time",
            // TransferEventMapper: token and native transfers (fee/success/method/contract_interaction: native only)
            "transaction.hash", "transaction.from", "transaction.to", "transaction.amount", "transaction.token.symbol",
            "transaction.token.contract", "transaction.token.decimals", "transaction.fee", "transaction.success",
            "transaction.method", "transaction.contract_interaction",
            // TransferEventMapper: approvals; + spender_is_contract (provider code lookup: EVM eth_getCode, TRON
            // /wallet/getcontract; absent with simulated providers or on lookup failure) and spender_age_days (first seen
            // by the platform in an indexed flow; absent when never seen) from enrichment. spender_verified is NOT populated.
            "approval.owner", "approval.spender", "approval.amount", "approval.unlimited", "approval.spender_is_contract",
            "approval.spender_age_days",
            // TransferEventMapper: CONTRACT_ADMIN_CHANGE (OwnershipTransferred, EIP-1967 Upgraded / AdminChanged) of the
            // token registry's contracts and oci.chains.<network>.watched-contracts
            "contract.address", "contract.event", "contract.new_owner",
            // TransferEventMapper: issuer freezes, + freeze.known_counterparties from enrichment
            "freeze.address", "freeze.token", "freeze.token_contract", "freeze.action", "freeze.issuer", "freeze.event",
            "freeze.known_counterparties",
            // enrichment; wallet.balance: real chains = provider balance at block - 1 (EVM eth_getBalance / balanceOf),
            // ABSENT when unavailable (provider error, rate limit, TRON: TronGrid has no historical balance); demo = ledger
            "wallet.address", "wallet.balance", "wallet.risk_score", "wallet.risk_level", "wallet.age_days", "wallet.tx_count",
            "wallet.labels", "wallet.sanctioned", "counterparty.address", "counterparty.risk_score", "counterparty.labels",
            "counterparty.age_days", "counterparty.sanctioned",
            // operator watchlist (WatchlistService): every enriched transfer and approval
            "wallet.watchlisted", "counterparty.watchlisted", "watchlist.active",
            // DETECTION_SIGNAL events
            "signal.type", "signal.severity", "signal.confidence", "signal.evidence_level",
            // INTERNAL_EVENT connector
            "internal.hop", "internal.origin_chain", "alert.id", "alert.rule_id", "alert.severity", "alert.group_key");

    public static final Set<EventType> EVENT_TYPES = EnumSet.of(EventType.TOKEN_TRANSFER, EventType.NATIVE_TRANSFER,
            EventType.APPROVAL, EventType.CONTRACT_ADMIN_CHANGE, EventType.STABLECOIN_FREEZE, EventType.STABLECOIN_UNFREEZE, EventType.DETECTION_SIGNAL);
}
