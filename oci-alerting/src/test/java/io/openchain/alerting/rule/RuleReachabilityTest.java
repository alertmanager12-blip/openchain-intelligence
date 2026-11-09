package io.openchain.alerting.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.core.event.ChainEvent.EventType;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleReachabilityTest {
    static final Set<String> FIELDS = Set.of("network", "event.type", "transaction.amount", "transaction.to", "contract.address",
            "contract.event");
    static final Set<EventType> TYPES = EnumSet.of(EventType.TOKEN_TRANSFER, EventType.CONTRACT_ADMIN_CHANGE);
    final RuleLoader loader = new RuleLoader();

    Rule rule(String conditions) {
        return loader.load("""
                apiVersion: openchain.io/rule/v1
                id: r
                version: 1.0.0
                name: R
                author: t
                enabled: true
                scope:
                  eventTypes: [TOKEN_TRANSFER]
                conditions:
                %s
                severity: LOW
                evidenceLevel: ANOMALY
                cooldown: 1m
                actions:
                  - { type: WEBHOOK, connectorId: x }
                documentation: d
                examples:
                  - { name: e, expect: NO_MATCH, event: { type: TOKEN_TRANSFER, network: tron, fields: {} } }
                """.formatted(conditions.indent(2).stripTrailing()), Format.YAML).rule();
    }

    @Test void andWithAnUnpopulatedFieldIsUnreachable() {
        Rule r = rule("""
                logic: AND
                rules:
                  - { field: transaction.amount, operator: GREATER_THAN, value: "1" }
                  - { field: transaction.amount_usd, operator: GREATER_THAN, value: "1000" }""");
        assertThat(RuleReachability.unreachable(r, FIELDS, TYPES)).singleElement().asString().contains("transaction.amount_usd");
    }

    @Test void orWithOneReachableBranchNotNotAndExistsFalseStayReachable() {
        assertThat(RuleReachability.unreachable(rule("""
                logic: OR
                rules:
                  - { field: transaction.amount_usd, operator: GREATER_THAN, value: "1000" }
                  - { field: transaction.amount, operator: GREATER_THAN, value: "1" }"""), FIELDS, TYPES)).isEmpty();
        assertThat(RuleReachability.unreachable(rule("""
                logic: AND
                rules:
                  - { field: transaction.amount_usd, operator: EXISTS, value: false }"""), FIELDS, TYPES)).isEmpty();
        assertThat(RuleReachability.unreachable(rule("""
                logic: AND
                groups:
                  - logic: NOT
                    rules:
                      - { field: transaction.amount_usd, operator: GREATER_THAN, value: "1" }"""), FIELDS, TYPES)).isEmpty();
    }

    @Test void theShippedContractExploitRuleIsReportedWithItsReasons() {
        Rule r = loader.load(Path.of("..", "rules", "ethereum", "contract-exploit.yaml")).rule();
        assertThat(RuleReachability.unreachable(r, FIELDS, TYPES)).anySatisfy(w -> assertThat(w).contains("large-outflow")
                .contains("CONTRACT_CALL"));
        assertThat(r.enabled()).isFalse();
    }
}
