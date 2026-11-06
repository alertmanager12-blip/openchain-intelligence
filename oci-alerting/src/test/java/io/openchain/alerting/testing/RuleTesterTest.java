package io.openchain.alerting.testing;

import static io.openchain.alerting.Fx.T0;
import static io.openchain.alerting.Fx.header;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.Fx;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.core.event.ChainEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleTesterTest {

    final RuleTester tester = new RuleTester(Fx.LOADER);

    /** 24h of traffic: one transfer per minute from 50 senders round-robin, plus a burst of 25 tx from TBURST. */
    static List<ChainEvent> history() {
        List<ChainEvent> out = new ArrayList<>();
        for (int i = 0; i < 1440; i++) out.add(Fx.transfer("h" + i, T0.plus(Duration.ofMinutes(i)), "TS" + (i % 50), "TR", i % 100 == 0 ? "250000" : "10"));
        for (int i = 0; i < 25; i++) out.add(Fx.transfer("burst" + i, T0.plus(Duration.ofHours(12)).plusSeconds(i * 5L), "TBURST", "TR" + i, "1"));
        out.sort(java.util.Comparator.comparing(ChainEvent::eventTime));
        return out;
    }

    @Test void validateReportsSyntaxAndFieldErrors() {
        assertThat(tester.validate("apiVersion: [", Format.YAML).valid()).isFalse();
        assertThat(tester.validate(header("x") + "conditions: { logic: AND, rules: [ { field: nope, operator: EXISTS } ] }\n", Format.YAML).errors())
                .anyMatch(e -> e.path().equals("$.conditions.rules[0].field"));
    }

    @Test void dryRunCountsMatchesAndEstimatesVolume() {
        CompiledRule burst = Fx.rule(header("burst") + """
                conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 0 } ] }
                aggregation: { window: 5m, groupBy: [transaction.from], function: COUNT, operator: GREATER_THAN, threshold: 20 }
                actions: [ { type: WEBHOOK, connectorId: would-fail-if-called } ]
                """);
        DryRunReport r = tester.dryRun(burst, history(), DryRunOptions.defaults());
        assertThat(r.totalEvents()).isEqualTo(1465);
        assertThat(r.eventsInScope()).isEqualTo(1465);
        assertThat(r.triggers()).isEqualTo(5);            // 21st..25th burst events
        assertThat(r.estimatedAlerts()).isEqualTo(1);     // grouped by cooldown
        assertThat(r.matchedEventIdsSample()).containsExactly("burst20", "burst21", "burst22", "burst23", "burst24");
        assertThat(r.observedSpan()).isEqualTo(Duration.ofMinutes(1439));
        assertThat(r.estimatedAlertsPerDay()).isBetween(0.9, 1.1);
        // filter is permissive (every event passes), but the aggregation provides a numeric threshold
        assertThat(r.warnings()).extracting(DryRunReport.Warning::code).contains("PERMISSIVE_MATCH_RATIO").doesNotContain("NO_NUMERIC_THRESHOLD");
        assertThat(r.engineMetrics()).containsEntry("oci.alerting.engine.triggers", 5L);
    }

    @Test void dryRunFlagsNoisyRules() {
        CompiledRule noisy = Fx.rule(header("noisy") + """
                conditions: { logic: AND, rules: [ { field: transaction.token.symbol, operator: EQUALS, value: USDT } ] }
                cooldown: { duration: 0, unit: SECONDS }
                """);
        DryRunReport r = tester.dryRun(noisy, history(), DryRunOptions.defaults());
        assertThat(r.triggers()).isEqualTo(1465);
        assertThat(r.permissive()).isTrue();
        assertThat(r.warnings()).extracting(DryRunReport.Warning::code)
                .contains("PERMISSIVE_MATCH_RATIO", "NO_NUMERIC_THRESHOLD", "HIGH_ALERT_VOLUME");

        CompiledRule precise = Fx.rule(header("precise") + """
                conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN_OR_EQUAL, value: 100000 } ] }
                dedupKey: "{{rule.id}}:{{event.transaction.from}}"
                """);
        DryRunReport p = tester.dryRun(precise, history(), DryRunOptions.defaults());
        assertThat(p.conditionMatches()).isEqualTo(15);
        assertThat(p.warnings()).isEmpty();
        assertThat(p.permissive()).isFalse();
    }

    @Test void dryRunWithNothingInScope() {
        CompiledRule eth = Fx.rule(header("eth") + "scope: { networks: [ethereum] }\nconditions: { logic: AND, rules: [ { field: transaction.amount, operator: EXISTS } ] }\n");
        assertThat(tester.dryRun(eth, history(), DryRunOptions.defaults()).warnings()).extracting(DryRunReport.Warning::code).contains("NO_EVENTS_IN_SCOPE");
    }

    @Test void embeddedExamplesAndTestsAreExecuted() {
        String rule = header("embedded") + """
                scope: { networks: [tron], eventTypes: [TOKEN_TRANSFER] }
                conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 0 } ] }
                aggregation: { window: 5m, groupBy: [transaction.from], function: COUNT, operator: GREATER_THAN, threshold: 2 }
                examples:
                  - { name: positive amount, expect: MATCH, event: { fields: { transaction.amount: "1" } } }
                  - { name: wrong expectation, expect: NO_MATCH, event: { fields: { transaction.amount: "1" } } }
                tests:
                  - name: reorg removes one event
                    events:
                      - { repeat: 3, every: 10s, fields: { transaction.from: TA, transaction.amount: "1" } }
                      - { eventId: test-0-0-1, removed: true, fields: { transaction.from: TA, transaction.amount: "1" }, offset: 10s }
                      - { offset: 30s, eventId: late-dup, fields: { transaction.from: TA, transaction.amount: "1" } }
                      - { offset: 30s, eventId: late-dup, fields: { transaction.from: TA, transaction.amount: "1" } }
                    expectTriggers: 2
                    expectAlerts: 1
                  - name: wrong count
                    events: [ { fields: { transaction.from: TA, transaction.amount: "1" } } ]
                    expectTriggers: 3
                """;
        EmbeddedTestReport report = tester.runEmbeddedTests(Fx.rule(rule));
        assertThat(report.results()).hasSize(4);
        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).extracting(EmbeddedTestReport.CaseResult::name).containsExactly("wrong expectation", "wrong count");
        assertThat(report.failures().get(1).message()).contains("expected triggers=3 but got triggers=0");
    }
}
