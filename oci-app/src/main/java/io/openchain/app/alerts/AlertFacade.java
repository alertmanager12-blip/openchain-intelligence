package io.openchain.app.alerts;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.alerting.alert.AlertSubject;
import io.openchain.alerting.alert.IllegalAlertTransitionException;
import io.openchain.app.api.ApiCode;
import io.openchain.app.api.ApiException;
import io.openchain.app.api.ApiMapper;
import io.openchain.app.api.Dto;
import io.openchain.app.chain.EventHistory;
import io.openchain.app.demo.DemoScenarios;
import org.springframework.beans.factory.ObjectProvider;
import io.openchain.core.event.ChainEvent;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * API view of oci-alerting alerts. The engine lifecycle (DETECTED, PENDING, NOTIFIED, ...) is exposed as the
 * analyst-facing status of the SPA: DETECTED/PENDING/NOTIFIED are all {@code OPEN}; the raw value is in
 * {@code lifecycleStatus}. Analyst transitions that the engine reaches only through intermediate states (e.g. an
 * alert without notification action is DETECTED and must pass PENDING to be acknowledged) are applied step by
 * step through {@link AlertService#transition}, each step recorded in the history with the analyst as actor.
 * NOTIFIED is never used as an intermediate step (it would claim a delivery that did not happen).
 */
@Service
public class AlertFacade {

    /** Engine statuses shown as API status OPEN: the one definition of an open alert (overview, filters, counts). */
    public static final Set<AlertStatus> OPEN_STATUSES = java.util.Collections.unmodifiableSet(
            EnumSet.of(AlertStatus.DETECTED, AlertStatus.PENDING, AlertStatus.NOTIFIED));
    private static final Set<AlertStatus> OPEN = OPEN_STATUSES;
    private static final Set<AlertStatus> INTERMEDIATE = EnumSet.of(AlertStatus.PENDING, AlertStatus.ACKNOWLEDGED);

    public record Filter(String status, String severity, String ruleId, String address, String network, Instant from, Instant to) {}

    private final AlertService alerts;
    private final EventHistory history;
    private final ObjectProvider<DemoScenarios> demo;

    public AlertFacade(AlertService alerts, EventHistory history, ObjectProvider<DemoScenarios> demo) {
        this.alerts = alerts;
        this.history = history;
        this.demo = demo;
    }

    public static String apiStatus(AlertStatus s) { return OPEN.contains(s) ? "OPEN" : s.name(); }

    /** Parses an API status; {@code IN_PROGRESS} is an alias of INVESTIGATING (docs/api). */
    public static AlertStatus parseTarget(String s) {
        if (s == null || s.isBlank()) throw ApiException.badRequest(ApiCode.ALERT_STATUS_REQUIRED);
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "ACKNOWLEDGED" -> AlertStatus.ACKNOWLEDGED;
            case "INVESTIGATING", "IN_PROGRESS" -> AlertStatus.INVESTIGATING;
            case "RESOLVED" -> AlertStatus.RESOLVED;
            case "FALSE_POSITIVE" -> AlertStatus.FALSE_POSITIVE;
            case "OPEN" -> throw ApiException.conflict(ApiCode.ALERT_REOPEN_UNSUPPORTED);
            default -> throw ApiException.badRequest(ApiCode.ALERT_STATUS_UNKNOWN, Map.of("status", s));
        };
    }

    /**
     * One page of alerts, newest first, filtered and limited in the repository (the whole table was loaded and paged in
     * memory at every request, code review I24). The address filter uses the subject stored with the alert.
     */
    public List<Dto.AlertDto> list(Filter f, int offset, int limit) {
        Optional<io.openchain.alerting.alert.AlertRepository.AlertQuery> q = query(f);
        if (q.isEmpty()) return List.of(); // a filter value that no alert can have (unknown status, severity or network)
        return alerts.repository().findPage(q.get(), offset, limit).stream().map(this::dto).toList();
    }

    /** Every alert matching the filter (tests and small exports); prefer the paged form. */
    public List<Dto.AlertDto> list(Filter f) { return list(f, 0, Integer.MAX_VALUE); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /** Translates the API filter; empty when a value matches no alert by construction. */
    static Optional<io.openchain.alerting.alert.AlertRepository.AlertQuery> query(Filter f) {
        Set<AlertStatus> statuses = null;
        if (!blank(f.status())) {
            String s = f.status().trim().toUpperCase(Locale.ROOT);
            statuses = EnumSet.noneOf(AlertStatus.class);
            if (s.equals("OPEN")) statuses.addAll(OPEN);
            else if (s.equals("IN_PROGRESS")) statuses.add(AlertStatus.INVESTIGATING);
            else {
                try { statuses.add(AlertStatus.valueOf(s)); } catch (IllegalArgumentException e) { return Optional.empty(); }
            }
        }
        io.openchain.core.detection.Severity severity = null;
        if (!blank(f.severity())) {
            try { severity = io.openchain.core.detection.Severity.valueOf(f.severity().trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return Optional.empty(); }
        }
        io.openchain.core.model.Network network = null;
        if (!blank(f.network())) {
            try { network = io.openchain.core.model.Network.fromId(f.network().trim().toLowerCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return Optional.empty(); }
        }
        return Optional.of(new io.openchain.alerting.alert.AlertRepository.AlertQuery(statuses, severity, blank(f.ruleId()) ? null : f.ruleId(),
                network, f.from(), f.to(), blank(f.address()) ? null : f.address().trim()));
    }

    public Alert get(String id) {
        return alerts.find(id).orElseThrow(() -> ApiException.notFound(ApiCode.ALERT_NOT_FOUND, Map.of("id", id)));
    }

    public Dto.AlertDto acknowledge(String id, String actor) { return moveTo(id, AlertStatus.ACKNOWLEDGED, actor, "acknowledged"); }

    public synchronized Dto.AlertDto moveTo(String id, AlertStatus target, String actor, String reason) {
        Alert a = get(id);
        if (a.status() == target) throw ApiException.conflict(ApiCode.ALERT_ALREADY_IN_STATUS, Map.of("status", target));
        List<AlertStatus> path = path(a.status(), target);
        if (path == null) throw new IllegalAlertTransitionException(id, a.status(), target);
        Alert cur = a;
        for (AlertStatus step : path) {
            String why = step == target ? reason : "intermediate step towards " + target + (reason == null ? "" : ": " + reason);
            cur = alerts.transition(id, step, actor, why);
        }
        return dto(cur);
    }

    /** Shortest path in the lifecycle graph using only analyst-safe intermediate states; null when unreachable. */
    static List<AlertStatus> path(AlertStatus from, AlertStatus to) {
        Map<AlertStatus, AlertStatus> prev = new EnumMap<>(AlertStatus.class);
        Deque<AlertStatus> q = new ArrayDeque<>(List.of(from));
        Set<AlertStatus> seen = EnumSet.of(from);
        while (!q.isEmpty()) {
            AlertStatus s = q.poll();
            for (AlertStatus n : s.allowedTransitions()) {
                if (!seen.add(n)) continue;
                prev.put(n, s);
                if (n == to) {
                    List<AlertStatus> path = new ArrayList<>();
                    for (AlertStatus x = to; x != from; x = prev.get(x)) path.addFirst(x);
                    return path;
                }
                if (INTERMEDIATE.contains(n)) q.add(n);
            }
        }
        return null;
    }

    public Dto.AlertDto assign(String id, String assignee, String actor) {
        get(id);
        String who = assignee == null || assignee.isBlank() ? null : assignee.trim();
        if (who != null && who.length() > 200) throw ApiException.badRequest(ApiCode.ALERT_ASSIGNEE_TOO_LONG);
        return dto(alerts.assign(id, who, actor));
    }

    public Dto.AlertDto comment(String id, String body, String actor) {
        get(id);
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) throw ApiException.badRequest(ApiCode.ALERT_COMMENT_EMPTY);
        if (text.length() > 4000) throw ApiException.badRequest(ApiCode.ALERT_COMMENT_TOO_LONG, Map.of("max", 4000));
        // stored and returned as plain text; clients must render it as text (never as HTML)
        return dto(alerts.comment(id, actor, text));
    }

    public Dto.AlertDto dto(Alert a) {
        // the subject stored with the alert (V3) is authoritative; the in-memory event history is only a fallback for
        // alerts stored before subjects existed (it is empty after a restart)
        AlertSubject s = a.subject();
        boolean stored = s != null && s.eventId() != null;
        Optional<ChainEvent> trigger = stored || a.matchedEventIds().isEmpty() ? Optional.empty() : history.find(a.matchedEventIds().getLast());
        AlertSubject subject = stored ? s : trigger.map(AlertSubject::of).orElse(s);
        Map<String, Object> fields = subject == null ? Map.of() : subject.fields();
        String address = subject == null ? null : subject.address();
        String txHash = subject == null ? null : subject.txHash();
        Map<String, Object> matched = new LinkedHashMap<>();
        if (subject != null && subject.eventId() != null) {
            matched.put("eventId", subject.eventId());
            matched.put("type", subject.eventType());
            matched.put("network", a.network() == null ? null : a.network().id());
            matched.put("eventTime", subject.eventTime() == null ? null : subject.eventTime().toString());
            matched.putAll(ApiMapper.jsonSafe(fields));
        }
        List<Dto.CommentDto> comments = new ArrayList<>();
        for (int i = 0; i < a.comments().size(); i++) {
            Alert.Comment c = a.comments().get(i);
            comments.add(new Dto.CommentDto(a.id() + "-c" + (i + 1), c.author(), c.text(), c.at()));
        }
        List<Dto.HistoryDto> hist = new ArrayList<>();
        for (Alert.HistoryEntry h : a.history()) {
            String action = h.from() == h.to() ? "ANNOTATED" : h.to().name();
            hist.addFirst(new Dto.HistoryDto(h.at(), h.actor(), action, h.from() == null ? null : h.from().name(), h.to().name(), h.reason()));
        }
        List<String> allowed = new ArrayList<>();
        for (AlertStatus t : List.of(AlertStatus.ACKNOWLEDGED, AlertStatus.INVESTIGATING, AlertStatus.RESOLVED, AlertStatus.FALSE_POSITIVE)) {
            if (t != a.status() && path(a.status(), t) != null) allowed.add(t.name());
        }
        return new Dto.AlertDto(a.id(), a.ruleId(), a.ruleVersion(), a.title(), a.severity().name(), a.priority().name(),
                apiStatus(a.status()), a.status().name(), allowed, a.network() == null ? null : a.network().id(), address, txHash,
                summary(a, fields), a.evidenceLevel() == null ? null : a.evidenceLevel().name(), a.assignee(), a.occurrenceCount(),
                a.firstSeen(), a.lastSeen(), a.createdAt(), a.updatedAt(), a.matchedEventIds(), matched,
                ApiMapper.jsonSafe(a.evidence()), comments, hist, demoScenario(a), subject == null ? null : subject.counterparty(),
                subject == null ? null : subject.blockNumber(), AlertNarrative.explain(a, fields), a.evidenceState().name(),
                a.withdrawnEventIds());
    }

    private String demoScenario(Alert a) {
        DemoScenarios d = demo.getIfAvailable();
        if (d == null) return null;
        for (String id : a.matchedEventIds()) {
            var s = d.scenarioOf(id);
            if (s.isPresent()) return s.get();
        }
        return null;
    }

    private static String summary(Alert a, Map<String, Object> f) {
        StringBuilder sb = new StringBuilder(a.title() == null ? a.ruleId() : a.title());
        Object kind = a.evidence().get("kind");
        if ("AGGREGATION".equals(kind)) {
            sb.append(": ").append(a.evidence().get("function")).append(" = ").append(a.evidence().get("value"))
                    .append(" (threshold ").append(a.evidence().get("threshold")).append(", window ").append(a.evidence().get("window")).append(")");
            if (a.groupKey() != null && !a.groupKey().isBlank()) sb.append(" for ").append(a.groupKey());
        } else if (f.get("transaction.amount") != null) {
            sb.append(": ").append(ApiMapper.jsonValue(f.get("transaction.amount"))).append(' ')
                    .append(f.getOrDefault("transaction.token.symbol", "")).append(" from ").append(f.get("transaction.from"))
                    .append(" to ").append(f.get("transaction.to"));
        } else if (f.get("signal.type") != null) {
            sb.append(": signal ").append(f.get("signal.type")).append(" on ").append(f.get("wallet.address"));
        }
        if (a.occurrenceCount() > 1) sb.append(" (x").append(a.occurrenceCount()).append(")");
        return sb.toString();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
