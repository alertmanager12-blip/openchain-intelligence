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
    private final Clock clock;

    public DashboardController(ChainIngestionService chains, IntelligencePipeline intelligence, AlertService alerts, AlertFacade alertFacade,
                               EventHistory history, RuleRegistry rules, InvestigationController investigation, Clock clock) {
        this.chains = chains;
        this.intelligence = intelligence;
        this.alerts = alerts;
        this.alertFacade = alertFacade;
        this.history = history;
        this.rules = rules;
        this.investigation = investigation;
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

        List<Alert> all = alerts.repository().findAll();
        List<Signal> signals = chains.recentSignals();
        long signals24h = signals.stream().filter(s -> s.detectedAt() != null && !s.detectedAt().isBefore(now.minus(Duration.ofHours(24)))).count();

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("walletsTracked", wallets.size());
        totals.put("eventsIndexed", chains.eventsIndexed());
        totals.put("transfersIndexed", chains.transfersIndexed());
        totals.put("eventsRetracted", chains.eventsRetracted());
        totals.put("rules", rules.list().size());
        totals.put("rulesEnabled", rules.list().stream().filter(RuleRegistry.StoredRule::enabled).count());
        totals.put("alerts", canAlerts ? all.size() : null);
        totals.put("openAlerts", canAlerts ? all.stream().filter(a -> AlertFacade.apiStatus(a.status()).equals("OPEN")).count() : null);
        totals.put("signals24h", canRisk ? signals24h : null);

        // hourly buckets over the last 24 h: indexed events (event time), alerts (creation), signals (detection)
        List<Map<String, Object>> activity = new ArrayList<>();
        long[] ev = new long[BUCKETS], al = new long[BUCKETS], sg = new long[BUCKETS];
        for (ChainEvent e : history.range(since, now, null, Integer.MAX_VALUE)) {
            if (e.type() == ChainEvent.EventType.DETECTION_SIGNAL) continue;
            bump(ev, since, e.eventTime());
        }
        for (Alert a : all) bump(al, since, a.createdAt());
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
        out.put("networks", investigation.networks(false));
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
            for (Alert a : all) {
                String st = AlertFacade.apiStatus(a.status());
                byStatus.merge(st, 1L, Long::sum);
                bySeverity.merge(a.severity().name(), 1L, Long::sum);
                if (st.equals("OPEN")) openBySeverity.merge(a.severity().name(), 1L, Long::sum);
            }
            Map<String, Object> alertSection = new LinkedHashMap<>();
            alertSection.put("byStatus", byStatus);
            alertSection.put("bySeverity", bySeverity);
            alertSection.put("openBySeverity", openBySeverity);
            alertSection.put("recent", all.stream().sorted(Comparator.comparing(Alert::createdAt).reversed()).limit(5)
                    .map(alertFacade::dto).toList());
            out.put("alerts", alertSection);
        } else {
            out.put("alerts", null);
            omitted.add("alerts (needs alerts:read)");
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

    private static void bump(long[] buckets, Instant since, Instant t) {
        if (t == null || t.isBefore(since)) return;
        int i = (int) Duration.between(since, t).toHours();
        if (i >= 0 && i < buckets.length) buckets[i]++;
    }
}
