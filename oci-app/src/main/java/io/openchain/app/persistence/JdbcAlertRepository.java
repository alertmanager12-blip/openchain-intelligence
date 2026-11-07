package io.openchain.app.persistence;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertRepository;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.alerting.alert.AlertSubject;
import io.openchain.alerting.alert.ConcurrentAlertModificationException;
import io.openchain.alerting.alert.Priority;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * PostgreSQL {@link AlertRepository}: {@code alerts} + append-only {@code alert_history} and {@code alert_comments}.
 * A save writes the alert row and its new history/comment rows in one transaction, with optimistic locking on
 * {@code row_version} ({@link Alert#version()}). Evidence is stored with {@link TypedJson}.
 */
public final class JdbcAlertRepository implements AlertRepository {

    private static final String COLUMNS = "id, rule_id, rule_version, title, severity, priority, status, evidence_level, network, "
            + "group_key, dedup_key, matched_event_ids, evidence, first_seen, last_seen, occurrence_count, assignee, created_at, "
            + "updated_at, escalation_level, last_escalated_at, origin_chain, hop, row_version, subject_event_id, subject_event_type, "
            + "subject_address, subject_counterparty, subject_tx_hash, subject_block, subject_time, subject_fields";
    private static final String TERMINAL = "('" + AlertStatus.RESOLVED.name() + "','" + AlertStatus.FALSE_POSITIVE.name() + "')";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner tx;

    public JdbcAlertRepository(NamedParameterJdbcTemplate jdbc, TransactionRunner tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Alert save(Alert a) {
        return tx.inTransaction(() -> {
            long next = a.version() + 1;
            MapSqlParameterSource p = params(a).addValue("expected", a.version()).addValue("next", next);
            int rows;
            if (a.version() == 0) {
                rows = jdbc.update("insert into alerts (" + COLUMNS + ") values (:id, :ruleId, :ruleVersion, :title, :severity, "
                        + ":priority, :status, :evidenceLevel, :network, :groupKey, :dedupKey, cast(:eventIds as jsonb), "
                        + "cast(:evidence as jsonb), :firstSeen, :lastSeen, :occurrences, :assignee, :createdAt, :updatedAt, "
                        + ":escalationLevel, :lastEscalatedAt, cast(:originChain as jsonb), :hop, :next, :sEventId, :sEventType, "
                        + ":sAddress, :sCounterparty, :sTxHash, :sBlock, :sTime, cast(:sFields as jsonb)) on conflict (id) do nothing", p);
            } else {
                rows = jdbc.update("update alerts set rule_version = :ruleVersion, title = :title, severity = :severity, "
                        + "priority = :priority, status = :status, evidence_level = :evidenceLevel, network = :network, "
                        + "group_key = :groupKey, dedup_key = :dedupKey, matched_event_ids = cast(:eventIds as jsonb), "
                        + "evidence = cast(:evidence as jsonb), first_seen = :firstSeen, last_seen = :lastSeen, "
                        + "occurrence_count = :occurrences, assignee = :assignee, updated_at = :updatedAt, "
                        + "escalation_level = :escalationLevel, last_escalated_at = :lastEscalatedAt, "
                        + "origin_chain = cast(:originChain as jsonb), hop = :hop, row_version = :next, "
                        + "subject_event_id = :sEventId, subject_event_type = :sEventType, subject_address = :sAddress, "
                        + "subject_counterparty = :sCounterparty, subject_tx_hash = :sTxHash, subject_block = :sBlock, "
                        + "subject_time = :sTime, subject_fields = cast(:sFields as jsonb) "
                        + "where id = :id and row_version = :expected", p);
            }
            if (rows != 1) throw new ConcurrentAlertModificationException(a.id(), a.version());
            appendChildren(a);
            return a.toBuilder().version(next).build();
        });
    }

    /** History and comments are append-only in the domain: only entries beyond the stored ones are inserted. */
    private void appendChildren(Alert a) {
        Map<String, Object> id = Map.of("id", a.id());
        int storedHistory = jdbc.queryForObject("select count(*) from alert_history where alert_id = :id", id, Integer.class);
        List<SqlParameterSource> h = new ArrayList<>();
        for (int i = storedHistory; i < a.history().size(); i++) {
            Alert.HistoryEntry e = a.history().get(i);
            h.add(new MapSqlParameterSource().addValue("id", a.id()).addValue("seq", i)
                    .addValue("from", e.from() == null ? null : e.from().name()).addValue("to", e.to().name())
                    .addValue("actor", e.actor()).addValue("at", Sql.ts(e.at())).addValue("reason", e.reason()));
        }
        if (!h.isEmpty()) {
            jdbc.batchUpdate("insert into alert_history (alert_id, seq, from_status, to_status, actor, at, reason) "
                    + "values (:id, :seq, :from, :to, :actor, :at, :reason)", h.toArray(SqlParameterSource[]::new));
        }
        int storedComments = jdbc.queryForObject("select count(*) from alert_comments where alert_id = :id", id, Integer.class);
        List<SqlParameterSource> c = new ArrayList<>();
        for (int i = storedComments; i < a.comments().size(); i++) {
            Alert.Comment e = a.comments().get(i);
            c.add(new MapSqlParameterSource().addValue("id", a.id()).addValue("seq", i).addValue("author", e.author())
                    .addValue("at", Sql.ts(e.at())).addValue("body", e.text()));
        }
        if (!c.isEmpty()) {
            jdbc.batchUpdate("insert into alert_comments (alert_id, seq, author, at, body) values (:id, :seq, :author, :at, :body)",
                    c.toArray(SqlParameterSource[]::new));
        }
    }

    @Override public Optional<Alert> findById(String id) {
        return query("where id = :id", Map.of("id", id)).stream().findFirst();
    }

    @Override public Optional<Alert> findOpenByDedupKey(String ruleId, String dedupKey) {
        return query("where rule_id = :ruleId and dedup_key is not distinct from :dedupKey and status not in " + TERMINAL
                + " order by created_at desc, id desc limit 1", mapOf("ruleId", ruleId, "dedupKey", dedupKey)).stream().findFirst();
    }

    @Override public List<Alert> findByStatus(AlertStatus status) {
        return query("where status = :status order by created_at, id", Map.of("status", status.name()));
    }

    @Override public List<Alert> findByMatchedEventId(String eventId) {
        return query("where matched_event_ids @> jsonb_build_array(cast(:eventId as text)) order by created_at, id",
                Map.of("eventId", eventId));
    }

    @Override public List<Alert> findAll() { return query("order by created_at, id", Map.of()); }

    // ---- reading ------------------------------------------------------------------------------------------------

    private List<Alert> query(String tail, Map<String, ?> params) {
        List<Alert.Builder> builders = new ArrayList<>();
        Map<String, Alert.Builder> byId = new LinkedHashMap<>();
        jdbc.query("select " + COLUMNS + " from alerts " + tail, params, rs -> {
            Alert.Builder b = row(rs);
            builders.add(b);
            byId.put(rs.getString("id"), b);
        });
        if (builders.isEmpty()) return List.of();
        Map<String, List<Alert.HistoryEntry>> history = new HashMap<>();
        Map<String, List<Alert.Comment>> comments = new HashMap<>();
        boolean all = params.isEmpty();
        List<String> ids = new ArrayList<>(byId.keySet());
        for (int from = 0; from < ids.size() || (all && from == 0); from += 1000) {
            String where = all ? "" : " where alert_id in (:ids)";
            Map<String, Object> p = all ? Map.of() : Map.of("ids", ids.subList(from, Math.min(ids.size(), from + 1000)));
            jdbc.query("select alert_id, from_status, to_status, actor, at, reason from alert_history" + where
                    + " order by alert_id, seq", p, rs -> {
                String f = rs.getString("from_status");
                history.computeIfAbsent(rs.getString("alert_id"), k -> new ArrayList<>()).add(new Alert.HistoryEntry(
                        f == null ? null : AlertStatus.valueOf(f), AlertStatus.valueOf(rs.getString("to_status")),
                        rs.getString("actor"), Sql.instant(rs, "at"), rs.getString("reason")));
            });
            jdbc.query("select alert_id, author, at, body from alert_comments" + where + " order by alert_id, seq", p, rs -> {
                comments.computeIfAbsent(rs.getString("alert_id"), k -> new ArrayList<>()).add(new Alert.Comment(
                        rs.getString("author"), Sql.instant(rs, "at"), rs.getString("body")));
            });
            if (all) break;
        }
        List<Alert> out = new ArrayList<>(builders.size());
        byId.forEach((id, b) -> out.add(b.history(history.getOrDefault(id, List.of())).comments(comments.getOrDefault(id, List.of())).build()));
        return out;
    }

    private static Alert.Builder row(ResultSet rs) throws SQLException {
        String ev = rs.getString("evidence_level");
        String net = rs.getString("network");
        return Alert.builder()
                .id(rs.getString("id")).ruleId(rs.getString("rule_id")).ruleVersion(rs.getString("rule_version"))
                .title(rs.getString("title")).severity(Severity.valueOf(rs.getString("severity")))
                .priority(Priority.valueOf(rs.getString("priority"))).status(AlertStatus.valueOf(rs.getString("status")))
                .evidenceLevel(ev == null ? null : EvidenceLevel.valueOf(ev)).network(net == null ? null : Network.fromId(net))
                .groupKey(rs.getString("group_key")).dedupKey(rs.getString("dedup_key"))
                .matchedEventIds(TypedJson.readStrings(rs.getString("matched_event_ids")))
                .evidence(TypedJson.readMap(rs.getString("evidence")))
                .firstSeen(Sql.instant(rs, "first_seen")).lastSeen(Sql.instant(rs, "last_seen"))
                .occurrenceCount(rs.getLong("occurrence_count")).assignee(rs.getString("assignee"))
                .createdAt(Sql.instant(rs, "created_at")).updatedAt(Sql.instant(rs, "updated_at"))
                .escalationLevel(rs.getInt("escalation_level")).lastEscalatedAt(Sql.instant(rs, "last_escalated_at"))
                .originChain(TypedJson.readStrings(rs.getString("origin_chain"))).hop(rs.getInt("hop"))
                .version(rs.getLong("row_version"))
                .subject(subject(rs));
    }

    /** Null when nothing was stored (alerts created before V3 that the migration could not back-fill). */
    private static AlertSubject subject(ResultSet rs) throws SQLException {
        String eventId = rs.getString("subject_event_id"), address = rs.getString("subject_address");
        String fields = rs.getString("subject_fields");
        if (eventId == null && address == null && fields == null) return null;
        long b = rs.getLong("subject_block");
        Long block = rs.wasNull() ? null : b;
        return new AlertSubject(eventId, rs.getString("subject_event_type"), address, rs.getString("subject_counterparty"),
                rs.getString("subject_tx_hash"), block, Sql.instant(rs, "subject_time"),
                fields == null ? null : TypedJson.readMap(fields));
    }

    private static MapSqlParameterSource params(Alert a) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", a.id()).addValue("ruleId", a.ruleId()).addValue("ruleVersion", a.ruleVersion())
                .addValue("title", a.title()).addValue("severity", a.severity().name()).addValue("priority", a.priority().name())
                .addValue("status", a.status().name())
                .addValue("evidenceLevel", a.evidenceLevel() == null ? null : a.evidenceLevel().name())
                .addValue("network", a.network() == null ? null : a.network().id())
                .addValue("groupKey", a.groupKey()).addValue("dedupKey", a.dedupKey())
                .addValue("eventIds", TypedJson.write(a.matchedEventIds())).addValue("evidence", TypedJson.write(a.evidence()))
                .addValue("firstSeen", Sql.ts(a.firstSeen())).addValue("lastSeen", Sql.ts(a.lastSeen()))
                .addValue("occurrences", a.occurrenceCount()).addValue("assignee", a.assignee())
                .addValue("createdAt", Sql.ts(a.createdAt())).addValue("updatedAt", Sql.ts(a.updatedAt()))
                .addValue("escalationLevel", a.escalationLevel()).addValue("lastEscalatedAt", Sql.ts(a.lastEscalatedAt()))
                .addValue("originChain", TypedJson.write(a.originChain())).addValue("hop", a.hop());
        AlertSubject s = a.subject();
        p.addValue("sEventId", s == null ? null : s.eventId()).addValue("sEventType", s == null ? null : s.eventType())
                .addValue("sAddress", s == null ? null : s.address()).addValue("sCounterparty", s == null ? null : s.counterparty())
                .addValue("sTxHash", s == null ? null : s.txHash()).addValue("sBlock", s == null ? null : s.blockNumber())
                .addValue("sTime", s == null ? null : Sql.ts(s.eventTime()))
                .addValue("sFields", s == null || s.eventId() == null && s.fields().isEmpty() ? null : TypedJson.write(s.fields()));
        return p;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
