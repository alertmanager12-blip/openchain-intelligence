package io.openchain.alerting.testing;

import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.EscalationPolicy;
import io.openchain.alerting.alert.InMemoryAlertRepository;
import io.openchain.alerting.engine.EngineConfig;
import io.openchain.alerting.engine.EngineResult;
import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.engine.RuleMatch;
import io.openchain.alerting.rule.Rule;
import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.compile.CompiledCondition;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.alerting.testing.DryRunReport.Warning;
import io.openchain.alerting.testing.EmbeddedTestReport.CaseResult;
import io.openchain.alerting.testing.EmbeddedTestReport.Kind;
import io.openchain.core.event.ChainEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authoring tools: syntax + field validation, DRY_RUN simulation over historical events and execution of a rule's
 * embedded examples/tests. Nothing here can send a notification: it only uses an isolated engine and an in-memory
 * alert store, never connectors.
 */
public final class RuleTester {

    private final RuleLoader loader;

    public RuleTester(RuleLoader loader) { this.loader = loader; }

    public RuleTester() { this(new RuleLoader()); }

    /** Syntax, schema, field whitelist and limits. */
    public RuleLoader.LoadResult validate(String source, Format format) { return loader.tryLoad(source, format); }

    public DryRunReport dryRun(CompiledRule rule, List<ChainEvent> events, DryRunOptions opts) {
        RuleEngine engine = new RuleEngine(opts.engine(), loader.catalog());
        engine.register(rule);
        AlertService alerts = isolatedAlerts();
        int inScope = 0, filterMatches = 0, triggers = 0;
        List<String> sample = new ArrayList<>();
        Instant min = null, max = null;
        for (ChainEvent e : events) {
            if (e == null || e.eventTime() == null) continue;
            min = min == null || e.eventTime().isBefore(min) ? e.eventTime() : min;
            max = max == null || e.eventTime().isAfter(max) ? e.eventTime() : max;
            if (!e.removed() && rule.inScope(e)) {
                inScope++;
                if (rule.matchesFilter(e, CompiledCondition.EvalStats.NOOP)) filterMatches++;
            }
            for (RuleMatch m : engine.process(e).matches()) {
                triggers++;
                alerts.onMatch(m, rule.rule().cooldown());
                if (sample.size() < opts.sampleSize()) sample.add(m.triggeringEventId());
            }
        }
        int estimatedAlerts = alerts.repository().findAll().size();
        Duration span = min == null ? Duration.ZERO : Duration.between(min, max);
        double days = Math.max(span.toSeconds(), 1) / 86_400.0;
        double triggersPerDay = triggers / days, alertsPerDay = estimatedAlerts / days;

        List<Warning> warnings = new ArrayList<>();
        if (events.isEmpty() || inScope == 0) warnings.add(new Warning("NO_EVENTS_IN_SCOPE", "no event matched the rule scope"));
        double ratio = inScope == 0 ? 0 : (double) filterMatches / inScope;
        if (inScope > 0 && ratio > opts.maxMatchRatio())
            warnings.add(new Warning("PERMISSIVE_MATCH_RATIO", String.format("%.1f%% of in-scope events pass the conditions (limit %.1f%%)",
                    ratio * 100, opts.maxMatchRatio() * 100)));
        if (!rule.hasNumericThreshold())
            warnings.add(new Warning("NO_NUMERIC_THRESHOLD", "rule has no aggregation and no numeric comparison: it may be very noisy"));
        if (alertsPerDay > opts.maxAlertsPerDay())
            warnings.add(new Warning("HIGH_ALERT_VOLUME", String.format("~%.0f alerts/day estimated (limit %.0f)", alertsPerDay, opts.maxAlertsPerDay())));
        if (span.compareTo(Duration.ofHours(1)) < 0 && !events.isEmpty())
            warnings.add(new Warning("SHORT_SAMPLE", "sample covers less than 1 hour: per-day estimates are unreliable"));
        return new DryRunReport(rule.rule().ref(), events.size(), inScope, filterMatches, triggers, estimatedAlerts, sample,
                span, round(triggersPerDay), round(alertsPerDay), warnings, engine.metrics().snapshot());
    }

    public EmbeddedTestReport runEmbeddedTests(CompiledRule rule) {
        List<CaseResult> results = new ArrayList<>();
        for (Rule.RuleExample ex : rule.rule().examples()) {
            boolean matched = rule.matchesFilter(toEvent(ex.event()), CompiledCondition.EvalStats.NOOP);
            boolean ok = matched == ex.expectMatch();
            results.add(new CaseResult(Kind.EXAMPLE, ex.name(), ok,
                    ok ? "ok" : "expected " + (ex.expectMatch() ? "MATCH" : "NO_MATCH") + " but got " + (matched ? "MATCH" : "NO_MATCH")));
        }
        for (Rule.RuleTestCase t : rule.rule().tests()) {
            RuleEngine engine = new RuleEngine(EngineConfig.defaults(), loader.catalog());
            engine.register(rule);
            AlertService alerts = isolatedAlerts();
            int triggers = 0;
            for (Rule.TestEvent te : t.events()) {
                EngineResult r = engine.process(toEvent(te));
                for (RuleMatch m : r.matches()) { triggers++; alerts.onMatch(m, rule.rule().cooldown()); }
            }
            int alertCount = alerts.repository().findAll().size();
            boolean ok = triggers == t.expectTriggers() && (t.expectAlerts() == null || alertCount == t.expectAlerts());
            results.add(new CaseResult(Kind.TEST, t.name(), ok, ok ? "ok" : "expected triggers=" + t.expectTriggers()
                    + (t.expectAlerts() == null ? "" : ", alerts=" + t.expectAlerts()) + " but got triggers=" + triggers + ", alerts=" + alertCount));
        }
        return new EmbeddedTestReport(rule.rule().ref(), results);
    }

    public static ChainEvent toEvent(Rule.TestEvent t) {
        return new ChainEvent(t.eventId(), t.type(), t.network(), t.blockNumber(), "0xtest" + t.blockNumber(), t.time(), t.time(),
                t.removed(), t.fields());
    }

    private static AlertService isolatedAlerts() {
        AtomicLong n = new AtomicLong();
        return new AlertService(new InMemoryAlertRepository(), Clock.systemUTC(), EscalationPolicy.disabled(),
                () -> "dry-run-" + n.incrementAndGet());
    }

    private static double round(double d) { return Math.round(d * 100) / 100.0; }
}
