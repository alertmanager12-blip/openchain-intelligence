package io.openchain.alerting.notify;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic JSON payload and plain-text summary for notifications. */
public final class NotificationFormatter {

    public static final String DISCLAIMER = "Automated monitoring alert based on observed on-chain behaviour. "
            + "It is not an accusation or a legal qualification of any person or address.";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private NotificationFormatter() {}

    /** Stable field order so that the same notification always produces the same bytes (signature, idempotency). */
    public static byte[] json(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "openchain.io/alert-notification/v1");
        m.put("deliveryId", n.deliveryId());
        m.put("alertId", n.alertId());
        m.put("rule", Map.of("id", n.ruleId(), "version", n.ruleVersion()));
        m.put("title", n.title());
        m.put("severity", n.severity().name());
        m.put("priority", n.priority().name());
        m.put("evidenceLevel", n.evidenceLevel() == null ? null : n.evidenceLevel().name());
        m.put("network", n.network() == null ? null : n.network().id());
        m.put("groupKey", n.groupKey());
        m.put("dedupKey", n.dedupKey());
        m.put("occurrenceCount", n.occurrenceCount());
        m.put("firstSeen", String.valueOf(n.firstSeen()));
        m.put("lastSeen", String.valueOf(n.lastSeen()));
        m.put("matchedEventIds", n.matchedEventIds());
        m.put("evidence", n.evidence());
        m.put("disclaimer", DISCLAIMER);
        return JSON.writeValueAsString(m).getBytes(StandardCharsets.UTF_8);
    }

    /** Human readable, single message, bounded length. */
    public static String text(Notification n, int maxLength) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(n.severity()).append('/').append(n.priority()).append("] ").append(n.title())
                .append(" (").append(n.ruleId()).append('@').append(n.ruleVersion()).append(")\n");
        sb.append("Network: ").append(n.network() == null ? "-" : n.network().id())
                .append(" | Group: ").append(n.groupKey()).append('\n');
        sb.append("Occurrences: ").append(n.occurrenceCount()).append(" | First seen: ").append(n.firstSeen())
                .append(" | Last seen: ").append(n.lastSeen()).append('\n');
        sb.append("Evidence level: ").append(n.evidenceLevel()).append('\n');
        Object kind = n.evidence().get("kind");
        if ("AGGREGATION".equals(kind)) {
            sb.append("Why: ").append(n.evidence().get("function")).append(' ')
                    .append(n.evidence().getOrDefault("field", "")).append(" = ").append(n.evidence().get("value"))
                    .append(' ').append(n.evidence().get("operator")).append(' ').append(n.evidence().get("threshold"))
                    .append(" within ").append(n.evidence().get("window")).append('\n');
        } else if (n.evidence().get("conditions") instanceof List<?> conds) {
            sb.append("Why: ");
            conds.stream().limit(5).forEach(c -> sb.append(c).append("; "));
            sb.append('\n');
        } else if (n.evidence().get("steps") instanceof List<?> steps) {
            sb.append("Sequence: ").append(steps).append('\n');
        }
        sb.append("Alert id: ").append(n.alertId()).append('\n').append(DISCLAIMER);
        return sb.length() > maxLength ? sb.substring(0, maxLength - 3) + "..." : sb.toString();
    }
}
