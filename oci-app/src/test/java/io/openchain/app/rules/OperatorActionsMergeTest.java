package io.openchain.app.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.compile.RuleCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production need: an operator routes repository rules to Telegram through the API; a later rule pack update (new
 * thresholds) must keep that routing instead of reverting to the pack's {@code default-webhook}.
 */
class OperatorActionsMergeTest {

    static String rule(String threshold, String connector) {
        return """
                apiVersion: openchain.io/rule/v1
                id: big-transfer
                version: 1.0.0
                name: Big transfer
                author: test
                enabled: true
                scope:
                  eventTypes: [TOKEN_TRANSFER]
                conditions:
                  logic: AND
                  rules:
                    - field: transaction.amount
                      operator: GREATER_THAN
                      value: "%s"
                severity: HIGH
                evidenceLevel: ANOMALY
                cooldown: 10m
                actions:
                  - type: WEBHOOK
                    connectorId: %s
                # trailing comment kept
                documentation: |
                  Large transfer.
                examples:
                  - name: big
                    expect: MATCH
                    event: { type: TOKEN_TRANSFER, network: tron, fields: { transaction.amount: "999999999" } }
                """.formatted(threshold, connector);
    }

    final InMemoryRuleRepository repo = new InMemoryRuleRepository();
    final RuleRegistry registry = new RuleRegistry(new RuleLoader(), new RuleCompiler(), new RuleEngine(),
            Clock.fixed(Instant.parse("2026-11-07T23:00:00Z"), ZoneOffset.UTC), repo);

    @Test void operatorRoutingSurvivesARulePackUpdateWithoutVersionSpam(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big-transfer.yaml");
        Files.writeString(file, rule("1000", "default-webhook"));
        registry.loadDirectory(dir);

        // the operator routes it to Telegram (PUT through the API)
        String telegram = registry.get("big-transfer").current().source()
                .replace("  - type: WEBHOOK\n    connectorId: default-webhook", "  - type: TELEGRAM\n    connectorId: wh-telegram");
        registry.update("big-transfer", telegram, Format.YAML);
        assertThat(repo.actionsOverride("big-transfer")).hasValueSatisfying(j -> assertThat(j).contains("TELEGRAM", "wh-telegram"));

        // the pack raises the threshold, still with its default action
        Files.writeString(file, rule("5000", "default-webhook"));
        registry.loadDirectory(dir);
        RuleRegistry.Version v = registry.get("big-transfer").current();
        assertThat(v.number()).isEqualTo(3);
        assertThat(v.source()).contains("value: \"5000\"", "wh-telegram", "# trailing comment kept", "Large transfer.")
                .doesNotContain("default-webhook");
        assertThat(v.rule().actions()).singleElement().satisfies(a -> assertThat(a.connectorId()).isEqualTo("wh-telegram"));

        // restarts do not create a version each time
        registry.loadDirectory(dir);
        registry.loadDirectory(dir);
        assertThat(registry.get("big-transfer").versions()).hasSize(3);
    }

    @Test void anUpdateThatDoesNotTouchActionsRecordsNothing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("big-transfer.yaml"), rule("1000", "default-webhook"));
        registry.loadDirectory(dir);
        registry.update("big-transfer", rule("2000", "default-webhook"), Format.YAML);
        assertThat(repo.actionsOverride("big-transfer")).isEmpty();
    }

    @Test void replacingActionsKeepsTheRestOfTheSourceVerbatim() {
        String src = rule("1000", "default-webhook");
        String out = RuleActions.replace(src, Format.YAML, "[{\"type\":\"TELEGRAM\",\"connectorId\":\"wh-x\"}]");
        assertThat(out).isEqualTo(src.replace("actions:\n  - type: WEBHOOK\n    connectorId: default-webhook",
                "actions: [{\"type\":\"TELEGRAM\",\"connectorId\":\"wh-x\"}]"));
    }
}
