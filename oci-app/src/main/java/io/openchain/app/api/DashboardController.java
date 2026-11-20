package io.openchain.app.api;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.app.alerts.AlertFacade;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.chain.EventHistory;
import io.openchain.app.rules.RuleRegistry;
import io.openchain.app.security.Scopes;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.risk.RiskLevel;
import io.openchain.intelligence.IntelligencePipeline;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-call overview for the console (permission wallet:read). Sections that need another permission
 * ({@code alerts} needs alerts:read, {@code signals} needs risk:read) are {@code null} when the caller lacks it,
 * and listed in {@code omittedSections}. Everything is computed from live in-memory state: no sample data.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private static final int BUCKETS = 24;

    private final ChainIngestionService chains;
    private final IntelligencePipeline intelligence;
    private final AlertService alerts;
    private final AlertFacade alertFacade;
    private final EventHistory history;
    private final RuleRegistry rules;
    private final InvestigationController investigation;
    private final io.openchain.app.alerts.NotificationRouting routing;
    private final Clock clock;

    public DashboardController(ChainIngestionService chains, IntelligencePipeline intelligence, AlertService alerts, AlertFacade alertFacade,
                               EventHistory history, RuleRegistry rules, InvestigationController investigation,
                               io.openchain.app.alerts.NotificationRouting routing, Clock clock) {
        this.chains = chains;
        this.intelligence = intelligence;
        this.alerts = alerts;
        this.alertFacade = alertFacade;
        this.history = history;
        this.rules = rules;
        this.investigation = investigation;
        this.routing = routing;
        this.clock = clock;
    }

    @GetMapping
    public Map<String, Object> dashboard(Authentication auth) {
        Set<String> scopes = Scopes.of(auth.getAuthorities());
        boolean canAlerts = scopes.contains(Scopes.ALERTS_READ), canRisk = scopes.contains(Scopes.RISK_READ);
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofHours(BUCKETS)).truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));

        Set<Address> wallets = chains.trackedWallets();
        Map<String, Long> riskDist = new LinkedHashMap<>();
        for (RiskLevel l : RiskLevel.values()) riskDist.put(l.name(), 0L);
        for (Address a : wallets) {
            String lvl = intelligence.scoring().latest(a).map(r -> r.level().name()).orElse(RiskLevel.UNKNOWN.name());
            riskDist.merge(lvl, 1L, Long::sum);
        }

        // aggregates computed by the repository: the dashboard must not load every alert (code review I24)
        var alertCounts = alerts.repository().countByStatusAndSeverity();
        long alertTotal = alertCounts.stream().mapToLong(c -> c.count()).sum();
        long alertOpen = alertCounts.stream().filter(c -> AlertFacade.apiStatus(c.status()).equals("OPEN")).mapToLong(c -> c.count()).sum();
        List<Signal> signals = chains.recentSignals();
        long signals24h = signals.stream().filter(s -> s.detectedAt() != null && !s.detectedAt().isBefore(now.minus(Duration.ofHours(24)))).count();

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("walletsTracked", wallets.size());
        totals.put("eventsIndexed", chains.eventsIndexed());
        totals.put("transfersIndexed", chains.transfersIndexed());
        totals.put("eventsRetracted", chains.eventsRetracted());
        totals.put("rules", rules.list().size());
        totals.put("rulesEnabled", rules.list().stream().filter(RuleRegistry.StoredRule::enabled).count());
        totals.put("alerts", canAlerts ? alertTotal : null);
        totals.put("openAlerts", canAlerts ? alertOpen : null);
        totals.put("signals24h", canRisk ? signals24h : null);

        // hourly buckets over the last 24 h: indexed events (event time), alerts (creation), signals (detection)
        List<Map<String, Object>> activity = new ArrayList<>();
        long[] ev = new long[BUCKETS], al = new long[BUCKETS], sg = new long[BUCKETS];
        for (ChainEvent e : history.range(since, now, null, Integer.MAX_VALUE)) {
            if (e.type() == ChainEvent.EventType.DETECTION_SIGNAL) continue;
            bump(ev, since, e.eventTime());
        }
        for (Instant created : alerts.repository().createdAtSince(since)) bump(al, since, created);
        for (Signal s : signals) bump(sg, since, s.detectedAt());
        for (int i = 0; i < BUCKETS; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("start", since.plus(Duration.ofHours(i)).toString());
            b.put("events", ev[i]);
            b.put("alerts", canAlerts ? al[i] : null);
            b.put("signals", canRisk ? sg[i] : null);
            activity.add(b);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now.toString());
        out.put("totals", totals);
        out.put("riskDistribution", riskDist);
        List<Dto.NetworkDto> networks = investigation.networks(false);
        out.put("networks", networks);
        out.put("coverage", Map.of("gaps", gaps(networks)));
        out.put("activity", activity);
        List<String> omitted = new ArrayList<>();
        if (canAlerts) {
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (String s : List.of("OPEN", "ACKNOWLEDGED", "INVESTIGATING", "RESOLVED", "FALSE_POSITIVE")) byStatus.put(s, 0L);
            Map<String, Long> bySeverity = new LinkedHashMap<>(), openBySeverity = new LinkedHashMap<>();
            for (Severity s : List.of(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO)) {
                bySeverity.put(s.name(), 0L);
                openBySeverity.put(s.name(), 0L);
            }
            for (var c : alertCounts) {
                String st = AlertFacade.apiStatus(c.status());
                byStatus.merge(st, c.count(), Long::sum);
                bySeverity.merge(c.severity().name(), c.count(), Long::sum);
                if (st.equals("OPEN")) openBySeverity.merge(c.severity().name(), c.count(), Long::sum);
            }
            Map<String, Object> alertSection = new LinkedHashMap<>();
            alertSection.put("byStatus", byStatus);
            alertSection.put("bySeverity", bySeverity);
            alertSection.put("openBySeverity", openBySeverity);
            alertSection.put("recent", alerts.repository().findPage(io.openchain.alerting.alert.AlertRepository.AlertQuery.any(), 0, 5)
                    .stream().map(alertFacade::dto).toList());
            // the overview's "handle now": the most severe open alerts, not merely the latest ones (spec 2026-11-19 §3)
            alertSection.put("attention", alerts.repository().findTopOpen(AlertFacade.OPEN_STATUSES, 5).stream().map(alertFacade::dto).toList());
            alertSection.put("last24h", last24h(now));
            out.put("alerts", alertSection);
        } else {
            out.put("alerts", null);
            omitted.add("alerts (needs alerts:read)");
        }
        if (scopes.contains(Scopes.RULES_READ) && scopes.contains(Scopes.WEBHOOKS_READ)) {
            List<String> unrouted = routing.unroutedRules();
            Set<String> un = Set.copyOf(unrouted);
            long openUnrouted = alerts.repository().openAndRecentByRule(AlertFacade.OPEN_STATUSES, now).stream()
                    .filter(r -> un.contains(r.ruleId())).mapToLong(r -> r.open()).sum();
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("unroutedRules", unrouted);
            n.put("openAlertsUnrouted", openUnrouted);
            out.put("notifications", n);
        } else {
            out.put("notifications", null);
            omitted.add("notifications (needs rules:read and webhooks:read)");
        }
        if (canRisk) {
            out.put("recentSignals", signals.stream().limit(10).map(ApiMapper::signal).toList());
        } else {
            out.put("recentSignals", null);
            omitted.add("recentSignals (needs risk:read)");
        }
        out.put("omittedSections", omitted);
        return out;
    }

    /** Indexing gaps of the enabled networks, newest detection first (blocks never analysed: spec 2026-11-19 §3). */
    private List<Map<String, Object>> gaps(List<Dto.NetworkDto> networks) {
        List<io.openchain.app.chain.IndexingCoverage.Gap> all = new ArrayList<>();
        for (Dto.NetworkDto n : networks) {
            if (n.enabled()) all.addAll(chains.indexingCoverage().gaps(io.openchain.core.model.Network.fromId(n.network())));
        }
        all.sort(Comparator.comparing(io.openchain.app.chain.IndexingCoverage.Gap::detectedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var g : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("network", g.network().id());
            m.put("fromBlock", g.fromBlock());
            m.put("toBlock", g.toBlock());
            m.put("blocks", g.toBlock() - g.fromBlock() + 1);
            m.put("cause", g.cause().name());
            m.put("detectedAt", g.detectedAt() == null ? null : g.detectedAt().toString());
            out.add(m);
        }
        return out;
    }

    /** Alerts created in the last 24 hours, split by rule, largest first. */
    private Map<String, Object> last24h(Instant now) {
        var per = alerts.repository().openAndRecentByRule(AlertFacade.OPEN_STATUSES, now.minus(Duration.ofHours(24))).stream()
                .filter(r -> r.createdSince() > 0)
                .sorted(Comparator.comparingLong((io.openchain.alerting.alert.AlertRepository.RuleOpenAndRecent r) -> r.createdSince())
                        .reversed().thenComparing(r -> r.ruleId()))
                .toList();
        List<Map<String, Object>> byRule = new ArrayList<>();
        long total = 0;
        for (var r : per) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", r.ruleId());
            m.put("ruleName", rules.find(r.ruleId()).map(s -> s.current().rule().name()).orElse(r.ruleId()));
            m.put("translations", rules.find(r.ruleId()).map(s -> s.current().rule().translations()).orElse(Map.of()));
            m.put("count", r.createdSince());
            byRule.add(m);
            total += r.createdSince();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("byRule", byRule);
        return out;
    }

    private static void bump(long[] buckets, Instant since, Instant t) {
        if (t == null || t.isBefore(since)) return;
        int i = (int) Duration.between(since, t).toHours();
        if (i >= 0 && i < buckets.length) buckets[i]++;
    }
}
