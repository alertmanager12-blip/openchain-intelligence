package io.openchain.alerting;

import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Test fixtures. */
public final class Fx {
    private Fx() {}

    public static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    public static final String USDT_TRON = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    public static final RuleLoader LOADER = new RuleLoader();

    public static String header(String id) {
        return """
                apiVersion: openchain.io/rule/v1
                id: %s
                version: 1.0.0
                name: test rule %s
                author: tests
                severity: HIGH
                """.formatted(id, id);
    }

    public static CompiledRule rule(String yaml) { return LOADER.load(yaml, Format.YAML); }

    public static ChainEvent event(String id, Network n, EventType t, Instant time, Map<String, Object> fields) {
        return new ChainEvent(id, t, n, 1L, "0xblock", time, time, false, fields);
    }

    /** TRON USDT transfer. */
    public static ChainEvent transfer(String id, Instant time, String from, String to, String amount) {
        Map<String, Object> f = new HashMap<>();
        f.put("transaction.from", from);
        f.put("transaction.to", to);
        f.put("transaction.amount", new BigDecimal(amount));
        f.put("transaction.token.symbol", "USDT");
        f.put("transaction.token.contract", USDT_TRON);
        return event(id, Network.TRON, EventType.TOKEN_TRANSFER, time, f);
    }

    public static ChainEvent with(ChainEvent e, String field, Object value) {
        Map<String, Object> f = new HashMap<>(e.fields());
        f.put(field, value);
        return new ChainEvent(e.eventId(), e.type(), e.network(), e.blockNumber(), e.blockHash(), e.eventTime(), e.ingestedAt(), e.removed(), f);
    }

    public static ChainEvent removed(ChainEvent e) {
        return new ChainEvent(e.eventId(), e.type(), e.network(), e.blockNumber(), e.blockHash(), e.eventTime(), e.ingestedAt(), true, e.fields());
    }
}
