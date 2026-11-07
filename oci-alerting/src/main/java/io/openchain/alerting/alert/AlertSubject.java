package io.openchain.alerting.alert;

import io.openchain.core.event.ChainEvent;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an alert is about, captured from the triggering event when the alert is created (and refreshed from the latest
 * trigger when an occurrence is grouped into it): the wallet, its counterparty, the transaction and a snapshot of the
 * event fields the rule saw. Stored with the alert, so the alert stays readable after a restart even though the
 * in-memory event history is gone.
 *
 * @param address      wallet the alert is about: {@code wallet.address}, else {@code transaction.from}, else
 *                     {@code freeze.address} / {@code approval.owner} / {@code admin.contract} (null when the event has none)
 * @param counterparty other side: {@code counterparty.address}, else {@code transaction.to} (null when none)
 * @param fields       the (enriched) event fields as the rule engine saw them (values: String, Boolean, Long, BigDecimal, Instant)
 */
public record AlertSubject(String eventId, String eventType, String address, String counterparty, String txHash,
                           Long blockNumber, Instant eventTime, Map<String, Object> fields) {

    public AlertSubject {
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** Subject of a triggering event. */
    public static AlertSubject of(ChainEvent e) {
        Map<String, Object> f = e.fields();
        String address = first(f, "wallet.address", "transaction.from", "freeze.address", "approval.owner", "admin.contract", "contract.address");
        String counterparty = first(f, "counterparty.address", "transaction.to", "approval.spender");
        if (counterparty != null && counterparty.equals(address)) counterparty = null;
        return new AlertSubject(e.eventId(), e.type() == null ? null : e.type().name(), address, counterparty,
                first(f, "transaction.hash"), e.blockNumber() < 0 ? null : e.blockNumber(), e.eventTime(), f);
    }

    private static String first(Map<String, Object> f, String... keys) {
        for (String k : keys) {
            Object v = f.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return null;
    }
}
