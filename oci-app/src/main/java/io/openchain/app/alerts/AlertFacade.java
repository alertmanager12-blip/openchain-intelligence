package io.openchain.app.alerts;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.alerting.alert.IllegalAlertTransitionException;
import io.openchain.app.api.ApiException;
import io.openchain.app.api.ApiMapper;
import io.openchain.app.api.Dto;
import io.openchain.app.chain.EventHistory;
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

    private static final Set<AlertStatus> OPEN = EnumSet.of(AlertStatus.DETECTED, AlertStatus.PENDING, AlertStatus.NOTIFIED);
    private static final Set<AlertStatus> INTERMEDIATE = EnumSet.of(AlertStatus.PENDING, AlertStatus.ACKNOWLEDGED);

    public record Filter(String status, String severity, String ruleId, String address, String network, Instant from, Instant to) {}

    private final AlertService alerts;
    private final EventHistory history;

    public AlertFacade(AlertService alerts, EventHistory history) {
        this.alerts = alerts;
        this.history = history;
    }

    public static String apiStatus(AlertStatus s) { return OPEN.contains(s) ? "OPEN" : s.name(); }

    /** Parses an API status; {@code IN_PROGRESS} is an alias of INVESTIGATING (docs/api). */
    public static AlertStatus parseTarget(String s) {
        if (s == null || s.isBlank()) throw ApiException.badRequest("status is required");
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "ACKNOWLEDGED" -> AlertStatus.ACKNOWLEDGED;
            case "INVESTIGATING", "IN_PROGRESS" -> AlertStatus.INVESTIGATING;
            case "RESOLVED" -> AlertStatus.RESOLVED;
            case "FALSE_POSITIVE" -> AlertStatus.FALSE_POSITIVE;
            case "OPEN" -> throw ApiException.conflict("re-opening alerts is not supported: RESOLVED and FALSE_POSITIVE are "
                    + "terminal; a new trigger opens a new alert");
            default -> throw ApiException.badRequest("unknown status '" + s + "'");
        };
    }

    public List<Dto.AlertDto> list(Filter f) {
        return alerts.repository().findAll().stream()
                .sorted(Comparator.comparing(Alert::createdAt).thenComparing(Alert::id).reversed())
                .filter(a -> f.status() == null || f.status().isBlank() || matchesStatus(a, f.status()))
                .filter(a -> f.severity() == null || f.severity().isBlank() || a.severity().name().equalsIgnoreCase(f.severity()))
                .filter(a -> f.ruleId() == null || f.ruleId().isBlank() || a.ruleId().equals(f.ruleId()))
                .filter(a -> f.network() == null || f.network().isBlank() || (a.network() != null && a.network().id().equalsIgnoreCase(f.network())))
                .filter(a -> f.from() == null || !a.createdAt().isBefore(f.from()))
                .filter(a -> f.to() == null || !a.createdAt().isAfter(f.to()))
                .map(this::dto)
                .filter(d -> f.address() == null || f.address().isBlank() || f.address().equalsIgnoreCase(d.address()))
                .toList();
    }

    private static boolean matchesStatus(Alert a, String status) {
        String s = status.trim().toUpperCase(Locale.ROOT);
        return apiStatus(a.status()).equals(s) || a.status().name().equals(s) || (s.equals("IN_PROGRESS") && a.status() == AlertStatus.INVESTIGATING);
    }

    public Alert get(String id) {
        return alerts.find(id).orElseThrow(() -> ApiException.notFound("alert " + id + " does not exist"));
    }

    public Dto.AlertDto acknowledge(String id, String actor) { return moveTo(id, AlertStatus.ACKNOWLEDGED, actor, "acknowledged"); }

    public synchronized Dto.AlertDto moveTo(String id, AlertStatus target, String actor, String reason) {
        Alert a = get(id);
        if (a.status() == target) throw ApiException.conflict("alert is already " + target);
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
        if (who != null && who.length() > 200) throw ApiException.badRequest("assignee too long");
        return dto(alerts.assign(id, who, actor));
    }

    public Dto.AlertDto comment(String id, String body, String actor) {
        get(id);
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) throw ApiException.badRequest("comment body must not be empty");
        if (text.length() > 4000) throw ApiException.badRequest("comment body is limited to 4000 characters");
        // stored and returned as plain text; clients must render it as text (never as HTML)
        return dto(alerts.comment(id, actor, text));
    }

    public Dto.AlertDto dto(Alert a) {
        Optional<ChainEvent> trigger = a.matchedEventIds().isEmpty() ? Optional.empty() : history.find(a.matchedEventIds().getLast());
        Map<String, Object> fields = trigger.map(ChainEvent::fields).orElse(Map.of());
        String address = str(fields.get("wallet.address"));
        if (address == null) address = str(fields.get("transaction.from"));
        String txHash = str(fields.get("transaction.hash"));
        Map<String, Object> matched = new LinkedHashMap<>();
        trigger.ifPresent(e -> {
            matched.put("eventId", e.eventId());
            matched.put("type", e.type().name());
            matched.put("network", e.network().id());
            matched.put("eventTime", e.eventTime() == null ? null : e.eventTime().toString());
            matched.putAll(ApiMapper.jsonSafe(e.fields()));
        });
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
                ApiMapper.jsonSafe(a.evidence()), comments, hist);
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
