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
            // TransferEventMapper
            "transaction.hash", "transaction.from", "transaction.to", "transaction.amount", "transaction.token.symbol",
            "transaction.token.contract", "transaction.token.decimals",
            // enrichment
            "wallet.address", "wallet.balance", "wallet.risk_score", "wallet.risk_level", "wallet.age_days", "wallet.tx_count",
            "wallet.labels", "wallet.sanctioned", "counterparty.address", "counterparty.risk_score", "counterparty.labels",
            "counterparty.age_days", "counterparty.sanctioned",
            // DETECTION_SIGNAL events
            "signal.type", "signal.severity", "signal.confidence", "signal.evidence_level",
            // INTERNAL_EVENT connector
            "internal.hop", "internal.origin_chain", "alert.id", "alert.rule_id", "alert.severity", "alert.group_key");

    public static final Set<EventType> EVENT_TYPES = EnumSet.of(EventType.TOKEN_TRANSFER, EventType.DETECTION_SIGNAL);
}
