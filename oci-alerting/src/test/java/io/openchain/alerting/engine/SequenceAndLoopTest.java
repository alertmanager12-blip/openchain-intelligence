package io.openchain.alerting.engine;

import static io.openchain.alerting.Fx.T0;
import static io.openchain.alerting.Fx.header;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.Fx;
import io.openchain.alerting.engine.EngineResult.Disposition;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SequenceAndLoopTest {

    /** Approval to a spender then a transfer by the same owner within 10 minutes. */
    static final CompiledRule APPROVE_THEN_DRAIN = Fx.rule(header("approve-then-drain") + """
            scope: { networks: [ethereum] }
            sequence:
              key: wallet.address
              within: 10m
              steps:
                - name: approval
                  conditions: { logic: AND, rules: [ { field: event.type, operator: EQUALS, value: APPROVAL } ] }
                - name: drain
                  conditions:
                    logic: AND
                    rules:
                      - { field: event.type, operator: EQUALS, value: TOKEN_TRANSFER }
                      - { field: transaction.amount, operator: GREATER_THAN, value: 1000 }
            """);

    static ChainEvent approval(String id, long sec, String wallet) {
        return Fx.event(id, Network.ETHEREUM, EventType.APPROVAL, T0.plusSeconds(sec), Map.of("wallet.address", wallet));
    }

    static ChainEvent drain(String id, long sec, String wallet) {
        return Fx.event(id, Network.ETHEREUM, EventType.TOKEN_TRANSFER, T0.plusSeconds(sec),
                Map.of("wallet.address", wallet, "transaction.amount", new BigDecimal("5000")));
    }

    static RuleEngine engine(Duration lateness, CompiledRule... rules) {
        RuleEngine e = new RuleEngine(EngineConfig.defaults().withAllowedLateness(lateness));
        for (CompiledRule r : rules) e.register(r);
        return e;
    }

    @Test void aThenBWithinTSameKeyTriggers() {
        RuleEngine e = engine(Duration.ofMinutes(2), APPROVE_THEN_DRAIN);
        assertThat(e.process(approval("a1", 0, "0xw")).matches()).isEmpty();
        assertThat(e.process(drain("d-other", 60, "0xother")).matches()).isEmpty(); // different key
        RuleMatch m = e.process(drain("d1", 300, "0xw")).matches().getFirst();
        assertThat(m.matchedEventIds()).containsExactly("a1", "d1");
        assertThat(m.groupKey()).isEqualTo("ethereum|0xw");
        assertThat((List<?>) m.evidence().get("steps")).hasSize(2);
        assertThat(m.evidence()).containsEntry("kind", "SEQUENCE").containsEntry("span", "PT5M");
        // the chain is consumed: a second drain needs a new approval
        assertThat(e.process(drain("d2", 320, "0xw")).matches()).isEmpty();
    }

    @Test void wrongOrderOrTooLateDoesNotTrigger() {
        RuleEngine e = engine(Duration.ofMinutes(2), APPROVE_THEN_DRAIN);
        e.process(drain("d1", 0, "0xw"));
        assertThat(e.process(approval("a1", 10, "0xw")).matches()).isEmpty(); // B before A
        assertThat(e.process(drain("d2", 10 + 601, "0xw")).matches()).isEmpty(); // 10m01s after A
    }

    @Test void outOfOrderArrivalWithinLatenessStillFormsTheSequence() {
        RuleEngine e = engine(Duration.ofMinutes(5), APPROVE_THEN_DRAIN);
        assertThat(e.process(drain("d1", 200, "0xw")).matches()).isEmpty();
        // the approval happened earlier on-chain (t=100) but arrives later
        RuleMatch m = e.process(approval("a1", 100, "0xw")).matches().getFirst();
        assertThat(m.matchedEventIds()).containsExactly("a1", "d1");
        assertThat(m.eventTime()).isEqualTo(T0.plusSeconds(200));
    }

    @Test void retractedStepDoesNotCount() {
        RuleEngine e = engine(Duration.ofMinutes(2), APPROVE_THEN_DRAIN);
        ChainEvent a = approval("a1", 0, "0xw");
        e.process(a);
        assertThat(e.process(Fx.removed(a)).disposition()).isEqualTo(Disposition.RETRACTED);
        assertThat(e.process(drain("d1", 30, "0xw")).matches()).isEmpty();
    }

    @Test void oneEventCannotSatisfyTwoSteps() {
        CompiledRule twice = Fx.rule(header("twice") + """
                sequence:
                  key: transaction.from
                  within: 5m
                  steps:
                    - { name: first, conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 10 } ] } }
                    - { name: second, conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 10 } ] } }
                """);
        RuleEngine e = engine(Duration.ofMinutes(2), twice);
        assertThat(e.process(Fx.transfer("x1", T0, "TA", "TB", "50")).matches()).isEmpty();
        assertThat(e.process(Fx.transfer("x2", T0.plusSeconds(1), "TA", "TB", "50")).matches()).hasSize(1);
    }

    // ---------------------------------------------------------------- notification loop protection

    static ChainEvent internal(String id, String originChain, long hop) {
        return Fx.event(id, Network.TRON, EventType.DETECTION_SIGNAL, T0, Map.of(
                "alert.rule_id", "origin", "alert.severity", "HIGH",
                RuleEngine.FIELD_ORIGIN_CHAIN, originChain, RuleEngine.FIELD_HOP, hop));
    }

    static final CompiledRule ON_ALERTS = Fx.rule(header("on-alerts") + """
            scope: { eventTypes: [DETECTION_SIGNAL] }
            conditions: { logic: AND, rules: [ { field: alert.severity, operator: EQUALS, value: HIGH } ] }
            """);

    @Test void ruleCannotRetriggerOnItsOwnInternalEvent() {
        RuleEngine e = engine(Duration.ofMinutes(2), ON_ALERTS);
        assertThat(e.process(internal("i1", "on-alerts", 1)).matches()).isEmpty();
        assertThat(e.metrics().loopSuppressed()).isEqualTo(1);
    }

    @Test void otherRuleMayReactAtDepthOneButNotBeyond() {
        RuleEngine e = engine(Duration.ofMinutes(2), ON_ALERTS);
        RuleMatch m = e.process(internal("i1", "origin", 1)).matches().getFirst();
        assertThat(m.hop()).isEqualTo(1);
        assertThat(m.originChain()).containsExactly("origin");
        assertThat(e.process(internal("i2", "origin,x", 2)).disposition()).isEqualTo(Disposition.LOOP_SUPPRESSED);
    }
}
