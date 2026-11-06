package io.openchain.app.persistence;

import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.delivery.DeliveryRecord;
import io.openchain.alerting.notify.delivery.DeliveryStatus;
import io.openchain.alerting.notify.delivery.DeliveryStore;
import io.openchain.alerting.rule.ActionType;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * PostgreSQL outbox ({@code deliveries}). {@link #findDue} claims rows atomically: due PENDING rows are selected with
 * {@code FOR UPDATE SKIP LOCKED} and leased to this instance ({@code claimed_by}, {@code claimed_until}) in the same
 * statement, so two instances never attempt the same delivery concurrently. The lease is released by the next
 * {@link #save} of the row (attempt recorded) or expires after {@code claimLease} (crashed instance), after which the
 * delivery is picked up again (at-least-once; receivers deduplicate on the delivery id).
 */
public final class JdbcDeliveryStore implements DeliveryStore {

    private static final String COLUMNS = "id, alert_id, connector_id, action_type, notification, status, attempts, "
            + "next_attempt_at, last_error, last_status_code, created_at, updated_at, delivered_at";

    private final NamedParameterJdbcTemplate jdbc;
    private final String owner;
    private final Duration claimLease;
    private final java.time.Clock clock;

    public JdbcDeliveryStore(NamedParameterJdbcTemplate jdbc, String owner, Duration claimLease, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.owner = owner;
        this.claimLease = claimLease;
        this.clock = clock;
    }

    @Override
    public DeliveryRecord save(DeliveryRecord r) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", r.id()).addValue("alertId", r.alertId()).addValue("connectorId", r.connectorId())
                .addValue("actionType", r.notification().actionType().name()).addValue("notification", encode(r.notification()))
                .addValue("status", r.status().name()).addValue("attempts", r.attempts())
                .addValue("nextAttemptAt", Sql.ts(r.nextAttemptAt())).addValue("lastError", truncate(r.lastError()))
                .addValue("lastStatusCode", r.lastStatusCode()).addValue("createdAt", Sql.ts(r.createdAt()))
                .addValue("updatedAt", Sql.ts(r.updatedAt())).addValue("deliveredAt", Sql.ts(r.deliveredAt()));
        // the notification snapshot is immutable: only the delivery state changes; saving releases the claim
        jdbc.update("insert into deliveries (" + COLUMNS + ") values (:id, :alertId, :connectorId, :actionType, "
                + "cast(:notification as jsonb), :status, :attempts, :nextAttemptAt, :lastError, :lastStatusCode, :createdAt, "
                + ":updatedAt, :deliveredAt) on conflict (id) do update set status = excluded.status, attempts = excluded.attempts, "
                + "next_attempt_at = excluded.next_attempt_at, last_error = excluded.last_error, "
                + "last_status_code = excluded.last_status_code, updated_at = excluded.updated_at, "
                + "delivered_at = excluded.delivered_at, claimed_by = null, claimed_until = null", p);
        return r;
    }

    @Override public Optional<DeliveryRecord> find(String id) {
        return jdbc.query("select " + COLUMNS + " from deliveries where id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    /** Claims (leases) up to {@code limit} due deliveries for this instance; rows locked or leased elsewhere are skipped. */
    @Override public List<DeliveryRecord> findDue(Instant now, int limit) {
        Instant wall = clock.instant();
        List<DeliveryRecord> claimed = new ArrayList<>(jdbc.query("update deliveries d set claimed_by = :owner, "
                + "claimed_until = :until where d.id in (select id from deliveries where status = 'PENDING' "
                + "and next_attempt_at <= :now and (claimed_until is null or claimed_until < :wall) "
                + "order by next_attempt_at, id limit :limit for update skip locked) returning " + prefixed(),
                new MapSqlParameterSource().addValue("owner", owner).addValue("until", Sql.ts(wall.plus(claimLease)))
                        .addValue("now", Sql.ts(now)).addValue("wall", Sql.ts(wall)).addValue("limit", limit), MAPPER));
        claimed.sort(Comparator.comparing(DeliveryRecord::nextAttemptAt).thenComparing(DeliveryRecord::id));
        return claimed;
    }

    @Override public List<DeliveryRecord> findByStatus(DeliveryStatus status) {
        return jdbc.query("select " + COLUMNS + " from deliveries where status = :s order by created_at, id",
                Map.of("s", status.name()), MAPPER);
    }

    @Override public List<DeliveryRecord> findByAlert(String alertId) {
        return jdbc.query("select " + COLUMNS + " from deliveries where alert_id = :a order by created_at, id",
                Map.of("a", alertId), MAPPER);
    }

    private static String prefixed() {
        StringBuilder sb = new StringBuilder();
        for (String c : COLUMNS.split(",\\s*")) sb.append(sb.isEmpty() ? "" : ", ").append("d.").append(c);
        return sb.toString();
    }

    private static final RowMapper<DeliveryRecord> MAPPER = (rs, n) -> new DeliveryRecord(rs.getString("id"),
            rs.getString("alert_id"), rs.getString("connector_id"), decode(rs.getString("notification")),
            DeliveryStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), Sql.instant(rs, "next_attempt_at"),
            rs.getString("last_error"), rs.getInt("last_status_code"), Sql.instant(rs, "created_at"),
            Sql.instant(rs, "updated_at"), Sql.instant(rs, "delivered_at"));

    private static String truncate(String s) { return s == null || s.length() <= 2000 ? s : s.substring(0, 2000); }

    // ---- notification snapshot ----------------------------------------------------------------------------------

    static String encode(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deliveryId", n.deliveryId());
        m.put("actionType", n.actionType().name());
        m.put("connectorId", n.connectorId());
        m.put("alertId", n.alertId());
        m.put("ruleId", n.ruleId());
        m.put("ruleVersion", n.ruleVersion());
        m.put("title", n.title());
        m.put("severity", n.severity().name());
        m.put("priority", n.priority().name());
        m.put("evidenceLevel", n.evidenceLevel() == null ? null : n.evidenceLevel().name());
        m.put("network", n.network() == null ? null : n.network().id());
        m.put("groupKey", n.groupKey());
        m.put("dedupKey", n.dedupKey());
        m.put("occurrenceCount", n.occurrenceCount());
        m.put("firstSeen", n.firstSeen());
        m.put("lastSeen", n.lastSeen());
        m.put("matchedEventIds", n.matchedEventIds());
        m.put("evidence", n.evidence());
        m.put("params", n.params());
        m.put("originChain", n.originChain());
        m.put("hop", n.hop());
        m.put("createdAt", n.createdAt());
        return TypedJson.write(m);
    }

    @SuppressWarnings("unchecked")
    static Notification decode(String json) {
        Map<String, Object> m = TypedJson.readMap(json);
        String ev = (String) m.get("evidenceLevel");
        String net = (String) m.get("network");
        Map<String, String> params = new LinkedHashMap<>();
        ((Map<String, Object>) m.getOrDefault("params", Map.of())).forEach((k, v) -> params.put(k, String.valueOf(v)));
        return new Notification((String) m.get("deliveryId"), ActionType.valueOf((String) m.get("actionType")),
                (String) m.get("connectorId"), (String) m.get("alertId"), (String) m.get("ruleId"), (String) m.get("ruleVersion"),
                (String) m.get("title"), Severity.valueOf((String) m.get("severity")), Priority.valueOf((String) m.get("priority")),
                ev == null ? null : EvidenceLevel.valueOf(ev), net == null ? null : Network.fromId(net),
                (String) m.get("groupKey"), (String) m.get("dedupKey"), ((Number) m.get("occurrenceCount")).longValue(),
                (Instant) m.get("firstSeen"), (Instant) m.get("lastSeen"), (List<String>) m.get("matchedEventIds"),
                (Map<String, Object>) m.get("evidence"), params, (List<String>) m.get("originChain"),
                ((Number) m.get("hop")).intValue(), (Instant) m.get("createdAt"));
    }
}
