package io.openchain.app.trace;

import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.testing.DryRunOptions;
import io.openchain.alerting.testing.DryRunReport;
import io.openchain.alerting.testing.EmbeddedTestReport;
import io.openchain.alerting.testing.RuleTester;
import io.openchain.app.chain.EventHistory;
import io.openchain.app.rules.RuleRegistry;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Network;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.investigation.RuleProposals;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule proposals of an investigation (ADR-0022 amendment 4): drafts from {@link RuleProposals}, each checked (valid for the
 * rule loader, its own examples and tests pass) and dry-run on the in-memory event history. Nothing is stored and nothing
 * here can create or enable a rule: adopting a draft is {@code POST /rules}, which creates it disabled.
 */
public final class RuleProposalService {
    private final AssertionReviewService reviews;
    private final RuleRegistry registry;
    private final RuleTester tester;
    private final EventHistory history;
    private final Clock clock;
    private final int maxDryRunEvents;

    public RuleProposalService(AssertionReviewService reviews, RuleRegistry registry, RuleTester tester, EventHistory history, Clock clock,
                               int maxDryRunEvents) {
        this.reviews = reviews;
        this.registry = registry;
        this.tester = tester;
        this.history = history;
        this.clock = clock;
        this.maxDryRunEvents = maxDryRunEvents;
    }

    /** Dry run on the events held in memory: the window actually covered is {@code from} to {@code to}, possibly short. */
    public record DryRun(int totalEvents, int eventsInScope, int conditionMatches, int triggers, int estimatedAlerts, Instant from, Instant to,
                         double estimatedAlertsPerDay, List<String> warnings) {}

    public record Checks(boolean valid, int examplesPassed, int testsPassed) {}

    public record Checked(String ruleId, RuleProposals.Template template, List<String> assertionIds, Map<String, String> reviews, String yaml,
                          boolean enabled, Checks checks, DryRun dryRun) {}

    public record View(String investigationId, int version, String proposalVersion, List<Checked> proposals, List<RuleProposals.Skipped> skipped) {}

    public View propose(String scope, String investigationId) {
        AssertionReviewService.View v = reviews.view(scope, investigationId);
        TraceArtifact inv = v.investigation();
        if (inv.resultJson() == null) throw new AssertionReviewService.NotFinished();
        Map<String, String> decisions = new LinkedHashMap<>();
        v.current().forEach((k, c) -> decisions.put(k, c.review().decision().name()));
        List<ValueFlow> flows = inv.evidenceJson() == null ? List.of() : TraceJson.evidence(inv.evidenceJson()).flows();
        RuleProposals.Result r = RuleProposals.propose(inv.id(), inv.version(), inv.network(), inv.seed(), TraceJson.assertions(inv.resultJson()),
                decisions, flows);

        List<ChainEvent> events = history.range(null, clock.instant(), Network.fromId(inv.network()), maxDryRunEvents);
        Instant from = events.isEmpty() ? null : events.getFirst().eventTime(), to = events.isEmpty() ? null : events.getLast().eventTime();
        List<Checked> out = new ArrayList<>();
        List<RuleProposals.Skipped> skipped = new ArrayList<>(r.skipped());
        for (RuleProposals.Proposal p : r.proposals()) {
            RuleLoader.LoadResult lr = registry.validate(p.yaml(), Format.YAML);
            if (!lr.valid()) {
                for (String a : p.assertionIds()) skipped.add(new RuleProposals.Skipped(a, "draft invalid: " + lr.errors().getFirst().message()));
                continue;
            }
            EmbeddedTestReport t = tester.runEmbeddedTests(lr.rule());
            if (!t.passed()) {
                for (String a : p.assertionIds()) skipped.add(new RuleProposals.Skipped(a, "draft fails its own case: " + t.failures().getFirst().name()));
                continue;
            }
            int ex = (int) t.results().stream().filter(c -> c.kind() == EmbeddedTestReport.Kind.EXAMPLE).count();
            DryRunReport d = tester.dryRun(registry.executable(lr.rule()), events, DryRunOptions.defaults());
            out.add(new Checked(p.ruleId(), p.template(), p.assertionIds(), p.reviews(), p.yaml(), lr.rule().rule().enabled(),
                    new Checks(true, ex, t.results().size() - ex),
                    new DryRun(d.totalEvents(), d.eventsInScope(), d.conditionMatches(), d.triggers(), d.estimatedAlerts(), from, to,
                            d.estimatedAlertsPerDay(), d.warnings().stream().map(DryRunReport.Warning::message).toList())));
        }
        return new View(inv.id(), inv.version(), r.version(), out, skipped);
    }
}
