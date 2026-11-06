package io.openchain.core.event;

import io.openchain.core.model.Network;
import java.time.Instant;
import java.util.Map;

/**
 * Canonical event flowing through the pipeline (indexer -> detection -> alerting).
 * {@code fields} is a flat, dot-notation view (e.g. "transaction.amount", "transaction.token.symbol",
 * "wallet.risk_score") consumed by the rule engine. Values are String, Boolean, Long, BigDecimal or Instant.
 *
 * @param eventId     deterministic id; duplicates with the same id MUST be ignored downstream
 * @param eventTime   on-chain time (event time, used for windows), not ingestion time
 * @param removed     true when the event is retracted by a chain reorg
 */
public record ChainEvent(
        String eventId,
        EventType type,
        Network network,
        long blockNumber,
        String blockHash,
        Instant eventTime,
        Instant ingestedAt,
        boolean removed,
        Map<String, Object> fields) {

    public enum EventType { TOKEN_TRANSFER, NATIVE_TRANSFER, CONTRACT_CALL, CONTRACT_ADMIN_CHANGE, APPROVAL, RISK_SCORE_CHANGED, DETECTION_SIGNAL }

    public ChainEvent { fields = Map.copyOf(fields); }

    public Object field(String path) { return fields.get(path); }
}
