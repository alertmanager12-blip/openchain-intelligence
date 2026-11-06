package io.openchain.alerting.rule;

import static io.openchain.alerting.Fx.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.alerting.Fx;
import io.openchain.alerting.engine.EngineMetrics;
import io.openchain.alerting.rule.compile.CompiledCondition;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.alerting.rule.compile.SafeRegex;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OperatorsTest {

    private static boolean matches(String leafYaml, Map<String, Object> fields) {
        CompiledRule r = Fx.rule(header("op") + "conditions: { logic: AND, rules: [ " + leafYaml + " ] }\n");
        ChainEvent e = Fx.event("e1", Network.ETHEREUM, EventType.TOKEN_TRANSFER, Fx.T0, fields);
        return r.condition().test(e, CompiledCondition.EvalStats.NOOP);
    }

    private static final Map<String, Object> TX = Map.of(
            "transaction.amount", new BigDecimal("1500.50"),
            "transaction.from", "0xabc0000000000000000000000000000000000001",
            "transaction.method", "transferFrom",
            "transaction.success", true,
            "wallet.risk_score", 72L,
            "wallet.labels", "exchange,hot-wallet");

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "{ field: transaction.amount, operator: EQUALS, value: '1500.5' }                     | true",
            "{ field: transaction.amount, operator: EQUALS, value: 1500.500 }                     | true",
            "{ field: transaction.amount, operator: NOT_EQUALS, value: 1500 }                      | true",
            "{ field: transaction.amount, operator: GREATER_THAN, value: '1500.49' }              | true",
            "{ field: transaction.amount, operator: GREATER_THAN, value: '1500.50' }              | false",
            "{ field: transaction.amount, operator: GREATER_THAN_OR_EQUAL, value: '1500.50' }     | true",
            "{ field: transaction.amount, operator: LESS_THAN, value: 1501 }                      | true",
            "{ field: transaction.amount, operator: LESS_THAN_OR_EQUAL, value: '1500.5' }         | true",
            "{ field: transaction.amount, operator: BETWEEN, value: [1500.5, 2000] }              | true",
            "{ field: transaction.amount, operator: BETWEEN, value: [1, 1500.49] }                | false",
            "{ field: transaction.amount, operator: IN, value: [1, '1500.50000', 3] }             | true",
            "{ field: transaction.amount, operator: NOT_IN, value: [1, 2] }                       | true",
            "{ field: wallet.risk_score, operator: GREATER_THAN_OR_EQUAL, value: 65 }             | true",
            "{ field: transaction.from, operator: EQUALS, value: '0xABC0000000000000000000000000000000000001' } | false",
            "{ field: transaction.from, operator: EQUALS, value: '0xABC0000000000000000000000000000000000001', ignoreCase: true } | true",
            "{ field: transaction.from, operator: IN, value: ['0xABC0000000000000000000000000000000000001'], ignoreCase: true } | true",
            "{ field: transaction.from, operator: NOT_IN, value: ['0xdef'] }                      | true",
            "{ field: transaction.from, operator: STARTS_WITH, value: '0xabc' }                   | true",
            "{ field: transaction.method, operator: STARTS_WITH, value: 'TRANSFER', ignoreCase: true } | true",
            "{ field: wallet.labels, operator: CONTAINS, value: 'hot-wallet' }                    | true",
            "{ field: wallet.labels, operator: CONTAINS, value: 'mixer' }                         | false",
            "{ field: transaction.method, operator: MATCHES, value: '^transfer(From)?$' }         | true",
            "{ field: transaction.method, operator: MATCHES, value: 'transfer' }                  | false",
            "{ field: transaction.success, operator: EQUALS, value: true }                        | true",
            "{ field: transaction.success, operator: NOT_EQUALS, value: true }                    | false",
            "{ field: transaction.amount, operator: EXISTS }                                      | true",
            "{ field: transaction.amount, operator: EXISTS, value: false }                        | false",
            "{ field: transaction.to, operator: EXISTS, value: false }                            | true",
            // absent fields never match, whatever the operator (except EXISTS false)
            "{ field: transaction.to, operator: NOT_EQUALS, value: x }                            | false",
            "{ field: transaction.to, operator: NOT_IN, value: [x] }                              | false",
            "{ field: wallet.age_days, operator: LESS_THAN, value: 10 }                           | false",
    })
    void operators(String leaf, boolean expected) {
        assertThat(matches(leaf, TX)).isEqualTo(expected);
    }

    @Test void timestampsAndEnvelopeFields() {
        Map<String, Object> f = Map.of("transaction.amount", BigDecimal.ONE);
        assertThat(matches("{ field: event.time, operator: GREATER_THAN, value: '2026-02-01T00:00:00Z' }", f)).isTrue();
        assertThat(matches("{ field: event.time, operator: BETWEEN, value: ['2026-03-01T00:00:00Z', '2026-03-02T00:00:00Z'] }", f)).isTrue();
        assertThat(matches("{ field: network, operator: EQUALS, value: ethereum }", f)).isTrue();
        assertThat(matches("{ field: event.type, operator: IN, value: [TOKEN_TRANSFER, APPROVAL] }", f)).isTrue();
        assertThat(matches("{ field: event.block_number, operator: GREATER_THAN_OR_EQUAL, value: 1 }", f)).isTrue();
    }

    @Test void typeMismatchAtRuntimeNeverMatchesAndIsCounted() {
        CompiledRule r = Fx.rule(header("tm") + "conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 1 } ] }\n");
        EngineMetrics m = new EngineMetrics();
        // a double sneaking in is refused (no binary floating point for amounts)
        assertThat(r.condition().test(Fx.event("e", Network.TRON, EventType.TOKEN_TRANSFER, Fx.T0, Map.of("transaction.amount", 5.0d)), m)).isFalse();
        assertThat(r.condition().test(Fx.event("e", Network.TRON, EventType.TOKEN_TRANSFER, Fx.T0, Map.of("transaction.amount", "abc")), m)).isFalse();
        assertThat(m.typeMismatches()).isEqualTo(2);
        // numeric strings and longs are accepted exactly
        assertThat(r.condition().test(Fx.event("e", Network.TRON, EventType.TOKEN_TRANSFER, Fx.T0, Map.of("transaction.amount", "1.0000000000000000001")), m)).isTrue();
        assertThat(r.condition().test(Fx.event("e", Network.TRON, EventType.TOKEN_TRANSFER, Fx.T0, Map.of("transaction.amount", 2L)), m)).isTrue();
    }

    @Test void nestedAndOrNot() {
        // amount > 1000 AND (risk >= 80 OR labels contains hot-wallet) AND NOT (from IN [blocked])
        String rule = header("nested") + """
                conditions:
                  logic: AND
                  rules:
                    - { field: transaction.amount, operator: GREATER_THAN, value: 1000 }
                  groups:
                    - logic: OR
                      rules:
                        - { field: wallet.risk_score, operator: GREATER_THAN_OR_EQUAL, value: 80 }
                        - { field: wallet.labels, operator: CONTAINS, value: hot-wallet }
                    - logic: NOT
                      groups:
                        - logic: OR
                          rules:
                            - { field: transaction.from, operator: IN, value: ["0xexcluded"] }
                            - { field: wallet.watchlisted, operator: EQUALS, value: true }
                """;
        CompiledRule r = Fx.rule(rule);
        java.util.function.Function<Map<String, Object>, Boolean> test = f ->
                r.condition().test(Fx.event("e", Network.ETHEREUM, EventType.TOKEN_TRANSFER, Fx.T0, f), CompiledCondition.EvalStats.NOOP);
        assertThat(test.apply(TX)).isTrue();                                                               // labels hot-wallet
        assertThat(test.apply(Map.of("transaction.amount", new BigDecimal("5000"), "wallet.risk_score", 90L))).isTrue();
        assertThat(test.apply(Map.of("transaction.amount", new BigDecimal("5000"), "wallet.risk_score", 10L))).isFalse(); // OR fails
        assertThat(test.apply(Map.of("transaction.amount", new BigDecimal("500"), "wallet.risk_score", 90L))).isFalse();  // AND fails
        assertThat(test.apply(Map.of("transaction.amount", new BigDecimal("5000"), "wallet.risk_score", 90L,
                "transaction.from", "0xexcluded"))).isFalse();                                              // NOT
        assertThat(test.apply(Map.of("transaction.amount", new BigDecimal("5000"), "wallet.risk_score", 90L,
                "wallet.watchlisted", true))).isFalse();                                                    // NOT(OR)

        List<CompiledCondition.LeafOutcome> trace = new ArrayList<>();
        r.condition().explain(Fx.event("e", Network.ETHEREUM, EventType.TOKEN_TRANSFER, Fx.T0, TX), trace);
        assertThat(trace).hasSize(5);
        assertThat(trace.getFirst().toString()).isEqualTo("transaction.amount GREATER_THAN 1000 (actual 1500.5) -> true");
        assertThat(trace).anyMatch(o -> o.operator().equals("NOT IN") && o.matched());
    }

    @Test void safeRegexStaticAnalysis() {
        for (String bad : List.of("(a+)+", "(a*)*b", "(\\d+)*x", "((a)+b)+", "(a|a)*", "(a|ab)+", "(.|x)*", "(\\w)\\1", "(?<n>a)\\k<n>", "(a+){2,100}"))
            assertThatThrownBy(() -> SafeRegex.compile(bad, 256, 1024, 100_000)).as(bad).isInstanceOf(SafeRegex.UnsafePatternException.class);
        for (String ok : List.of("^0x[0-9a-f]{40}$", "(foo|bar)+", "(ab)?c+", "[a-z]+(\\.[a-z]+)?", "(?i)^swap.*", "(?:abc){3}", "a{2,5}b*", "(?<=x)y"))
            assertThat(SafeRegex.compile(ok, 256, 1024, 100_000).pattern()).isEqualTo(ok);
    }

    @Test void safeRegexRuntimeBudgetAndInputCap() {
        SafeRegex poly = SafeRegex.compile(".*.*.*.*x", 256, 10_000, 50_000); // polynomial, passes static check
        assertThatThrownBy(() -> poly.matches("a".repeat(5000))).isInstanceOf(SafeRegex.BudgetExceeded.class);
        SafeRegex capped = SafeRegex.compile("a+", 256, 10, 100_000);
        assertThat(capped.matches("aaaa")).isTrue();
        assertThat(capped.matches("a".repeat(11))).isFalse(); // longer than the input cap: never matches

        CompiledRule r = Fx.rule(header("budget") + "conditions: { logic: AND, rules: [ { field: transaction.method, operator: MATCHES, value: '.*.*.*.*.*.*x' } ] }\n");
        EngineMetrics m = new EngineMetrics();
        long start = System.nanoTime();
        boolean res = r.condition().test(Fx.event("e", Network.TRON, EventType.CONTRACT_CALL, Instant.EPOCH,
                Map.of("transaction.method", "a".repeat(1000))), m);
        assertThat(res).isFalse();
        assertThat(m.regexBudgetExceededCount()).isEqualTo(1);
        assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }
}
