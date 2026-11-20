package io.openchain.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.rule.RuleReachability;
import io.openchain.core.event.ChainEvent.EventType;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The reachability codes of the alerting module are ApiCodes, and their English text is the ApiCode template filled. */
class UnreachableCodesTest {

    private static final Set<String> FIELDS = Set.of("network", "event.type", "transaction.amount");
    private static final Set<EventType> TYPES = EnumSet.of(EventType.TOKEN_TRANSFER);

    private static io.openchain.alerting.rule.Rule rule(String yaml) {
        return new io.openchain.alerting.rule.RuleLoader().load(yaml, io.openchain.alerting.rule.RuleParser.Format.YAML).rule();
    }

    @Test
    void everyReasonCodeIsAnApiCodeWhoseTemplateRebuildsTheEnglishMessage() {
        var r = rule("""
                apiVersion: openchain.io/rule/v1
                id: t-unreachable
                version: 1.0.0
                name: T
                author: test
                enabled: true
                scope:
                  networks: [tron]
                  eventTypes: [APPROVAL]
                conditions:
                  logic: AND
                  rules:
                    - field: event.type
                      operator: EQUALS
                      value: STABLECOIN_FREEZE
                    - field: transaction.amount_usd
                      operator: GREATER_THAN
                      value: "1"
                severity: LOW
                evidenceLevel: ANOMALY
                cooldown: 10m
                actions:
                  - { type: WEBHOOK, connectorId: x }
                documentation: d
                examples:
                  - { name: e, expect: NO_MATCH, event: { type: APPROVAL, network: tron, fields: {} } }
                """);
        var reasons = RuleReachability.reasons(r, FIELDS, TYPES);
        assertThat(reasons).extracting(RuleReachability.Reason::code).containsExactly(
                "unreachable.event_types_not_produced", "unreachable.event_type_not_produced", "unreachable.field_not_produced");
        for (var x : reasons) assertThat(ApiCode.byId(x.code()).fill(x.params())).isEqualTo(x.message());
        assertThat(RuleReachability.unreachable(r, FIELDS, TYPES)).containsExactlyElementsOf(reasons.stream().map(RuleReachability.Reason::message).toList());
    }
}
