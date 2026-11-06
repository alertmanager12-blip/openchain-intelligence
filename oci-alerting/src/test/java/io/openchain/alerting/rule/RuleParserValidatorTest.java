package io.openchain.alerting.rule;

import static io.openchain.alerting.Fx.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.alerting.Fx;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.ValidationError.Code;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.core.detection.Severity;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleParserValidatorTest {

    private final RuleLoader loader = Fx.LOADER;

    private List<ValidationError> errors(String yaml) {
        RuleLoader.LoadResult r = loader.tryLoad(yaml, Format.YAML);
        assertThat(r.valid()).as("expected invalid rule").isFalse();
        return r.errors();
    }

    private static boolean has(List<ValidationError> errs, String path, Code code) {
        return errs.stream().anyMatch(e -> e.path().equals(path) && e.code() == code);
    }

    private static final String SIMPLE = header("simple") + """
            conditions:
              logic: AND
              rules:
                - field: transaction.amount
                  operator: GREATER_THAN
                  value: "100"
            """;

    @Nested class Valid {
        @Test void minimalYamlRule() {
            CompiledRule r = Fx.rule(SIMPLE);
            assertThat(r.id()).isEqualTo("simple");
            assertThat(r.rule().severity()).isEqualTo(Severity.HIGH);
            assertThat(r.rule().cooldown()).isEqualTo(Duration.ofMinutes(15));
            assertThat(r.rule().dedupKey()).isEqualTo(Rule.DEFAULT_DEDUP_KEY);
            assertThat(r.kind()).isEqualTo(CompiledRule.Kind.STATELESS);
        }

        @Test void equivalentJsonRule() {
            String json = """
                    {"apiVersion":"openchain.io/rule/v1","id":"json-rule","version":"2.1.0-beta.1","name":"n","author":"a",
                     "severity":"LOW","scope":{"networks":["ethereum"],"eventTypes":["APPROVAL"]},
                     "conditions":{"logic":"OR","rules":[{"field":"approval.amount","operator":"BETWEEN","value":["0.5", 10]}]},
                     "aggregation":{"window":{"duration":5,"unit":"MINUTES"},"groupBy":"approval.owner","function":"COUNT",
                                    "operator":"GREATER_THAN","threshold":3},
                     "cooldown":{"duration":0,"unit":"SECONDS"}}
                    """;
            CompiledRule r = loader.load(json, Format.JSON);
            assertThat(r.kind()).isEqualTo(CompiledRule.Kind.AGGREGATION);
            assertThat(r.rule().aggregation().window()).isEqualTo(Duration.ofMinutes(5));
            assertThat(r.rule().aggregation().groupBy()).containsExactly("approval.owner");
            assertThat(r.rule().aggregation().threshold()).isEqualByComparingTo("3");
            assertThat(r.rule().cooldown()).isZero();
        }

        @Test void decimalsAreExact() {
            CompiledRule r = Fx.rule(header("exact") + """
                    conditions: { logic: AND, rules: [ { field: transaction.amount, operator: EQUALS, value: 0.1 } ] }
                    """);
            Condition.Group g = (Condition.Group) r.rule().conditions();
            assertThat(((Condition.Leaf) g.children().getFirst()).value()).isEqualTo(new BigDecimal("0.1"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"1m", "5m", "15m", "1h", "24h", "7d", "{ preset: 15m }", "{ duration: 90, unit: SECONDS }"})
        void windowPresetsAndCustom(String window) {
            CompiledRule r = Fx.rule(SIMPLE + """
                    aggregation: { window: %s, function: COUNT, operator: GREATER_THAN, threshold: 1 }
                    """.formatted(window));
            assertThat(r.rule().aggregation().window()).isPositive();
        }
    }

    @Nested class Invalid {
        @Test void missingAndWrongApiVersion() {
            assertThat(has(errors(SIMPLE.replace("apiVersion: openchain.io/rule/v1\n", "")), "$.apiVersion", Code.REQUIRED)).isTrue();
            assertThat(has(errors(SIMPLE.replace("rule/v1", "rule/v2")), "$.apiVersion", Code.UNSUPPORTED_API_VERSION)).isTrue();
        }

        @Test void unknownCatalogFieldIsRejectedWithJsonPath() {
            List<ValidationError> e = errors(SIMPLE.replace("transaction.amount", "transaction.secret_field"));
            assertThat(has(e, "$.conditions.rules[0].field", Code.UNKNOWN_FIELD)).isTrue();
        }

        @Test void unknownPropertyIsRejected() {
            assertThat(has(errors(SIMPLE + "script: \"rm -rf /\"\n"), "$.script", Code.UNKNOWN_PROPERTY)).isTrue();
            assertThat(has(errors(SIMPLE.replace("operator: GREATER_THAN", "operator: GREATER_THAN\n      expression: \"T(java.lang.Runtime)\"")),
                    "$.conditions.rules[0].expression", Code.UNKNOWN_PROPERTY)).isTrue();
        }

        @Test void operatorTypeMismatch() {
            List<ValidationError> e = errors(header("t") + """
                    conditions: { logic: AND, rules: [ { field: transaction.success, operator: GREATER_THAN, value: 1 },
                                                       { field: transaction.from, operator: BETWEEN, value: [a, b] } ] }
                    """);
            assertThat(has(e, "$.conditions.rules[0].operator", Code.INVALID_OPERATOR)).isTrue();
            assertThat(has(e, "$.conditions.rules[1].operator", Code.INVALID_OPERATOR)).isTrue();
        }

        @Test void valueTypeErrors() {
            List<ValidationError> e = errors(header("t") + """
                    conditions:
                      logic: AND
                      rules:
                        - { field: transaction.amount, operator: GREATER_THAN, value: "lots" }
                        - { field: wallet.age_days, operator: EQUALS, value: 1.5 }
                        - { field: transaction.from, operator: EQUALS, value: 12 }
                        - { field: transaction.amount, operator: BETWEEN, value: [10, 1] }
                        - { field: transaction.from, operator: IN, value: [] }
                    """);
            assertThat(has(e, "$.conditions.rules[0].value", Code.TYPE)).isTrue();
            assertThat(has(e, "$.conditions.rules[1].value", Code.TYPE)).isTrue();
            assertThat(has(e, "$.conditions.rules[2].value", Code.TYPE)).isTrue();
            assertThat(has(e, "$.conditions.rules[3].value", Code.INVALID_VALUE)).isTrue();
            assertThat(has(e, "$.conditions.rules[4].value", Code.TYPE)).isTrue();
            assertThat(e).hasSizeGreaterThanOrEqualTo(5); // all errors collected, not just the first
        }

        @Test void depthBombIsRejectedByValidator() {
            StringBuilder sb = new StringBuilder(header("deep") + "conditions:\n");
            String indent = "  ";
            for (int i = 0; i < 8; i++) {
                sb.append(indent).append("logic: AND\n").append(indent).append("groups:\n").append(indent).append("  -\n");
                indent += "    ";
            }
            sb.append(indent).append("logic: AND\n").append(indent).append("rules: [ { field: transaction.amount, operator: EXISTS } ]\n");
            List<ValidationError> e = errors(sb.toString());
            assertThat(e).anyMatch(x -> x.code() == Code.LIMIT_EXCEEDED && x.message().contains("deeper"));
        }

        @Test void nestingBombIsRejectedByParser() {
            String json = "{\"a\":".repeat(100) + "1" + "}".repeat(100);
            List<ValidationError> e = loader.tryLoad(json, Format.JSON).errors();
            assertThat(e).singleElement().satisfies(x -> assertThat(x.code()).isEqualTo(Code.SYNTAX));
            String yaml = "a:\n" + "  ".repeat(1) + "[".repeat(60) + "]".repeat(60);
            assertThat(loader.tryLoad(yaml, Format.YAML).valid()).isFalse();
        }

        @Test void tooManyConditions() {
            StringBuilder sb = new StringBuilder(header("many") + "conditions:\n  logic: OR\n  rules:\n");
            for (int i = 0; i < 60; i++) sb.append("    - { field: transaction.amount, operator: EQUALS, value: ").append(i).append(" }\n");
            assertThat(errors(sb.toString())).anyMatch(x -> x.code() == Code.LIMIT_EXCEEDED && x.message().contains("too many conditions"));
        }

        @Test void badAndCatastrophicRegex() {
            String base = header("re") + "conditions: { logic: AND, rules: [ { field: transaction.method, operator: MATCHES, value: '%s' } ] }\n";
            assertThat(has(errors(base.formatted("[unclosed")), "$.conditions.rules[0].value", Code.INVALID_REGEX)).isTrue();
            assertThat(has(errors(base.formatted("(a+)+$")), "$.conditions.rules[0].value", Code.UNSAFE_REGEX)).isTrue();
            assertThat(has(errors(base.formatted("((ab)*c)*")), "$.conditions.rules[0].value", Code.UNSAFE_REGEX)).isTrue();
            assertThat(has(errors(base.formatted("(a|ab)*")), "$.conditions.rules[0].value", Code.UNSAFE_REGEX)).isTrue();
            assertThat(has(errors(base.formatted("(x)\\1")), "$.conditions.rules[0].value", Code.UNSAFE_REGEX)).isTrue();
            assertThat(has(errors(base.formatted("a".repeat(300))), "$.conditions.rules[0].value", Code.INVALID_REGEX)).isTrue();
            assertThat(loader.tryLoad(base.formatted("^(transfer|approve)[A-Za-z]*$"), Format.YAML).valid()).isTrue();
        }

        @Test void unsafeYamlConstructsAreRejected() {
            assertThat(errors(SIMPLE + "description: !!java.lang.Runtime x\n")).anyMatch(e -> e.code() == Code.UNSAFE_DOCUMENT);
            assertThat(errors(SIMPLE + "description: !custom x\n")).anyMatch(e -> e.code() == Code.UNSAFE_DOCUMENT);
            String laughs = SIMPLE + "tags: &a [x, x]\ndocumentation: *a\n";
            assertThat(errors(laughs)).anyMatch(e -> e.code() == Code.UNSAFE_DOCUMENT);
            assertThat(errors(SIMPLE + "---\n" + SIMPLE)).anyMatch(e -> e.code() == Code.UNSAFE_DOCUMENT);
            assertThat(errors(SIMPLE + "name: duplicate\n")).anyMatch(e -> e.code() == Code.SYNTAX);
            assertThat(errors("- just\n- a list\n")).anyMatch(e -> e.code() == Code.TYPE);
            assertThat(errors("key: [unclosed\n")).anyMatch(e -> e.code() == Code.SYNTAX);
        }

        @Test void oversizedDocument() {
            String big = SIMPLE + "documentation: \"" + "x".repeat(300 * 1024) + "\"\n";
            assertThat(errors(big)).anyMatch(e -> e.code() == Code.LIMIT_EXCEEDED);
        }

        @Test void structuralErrors() {
            assertThat(has(errors(SIMPLE.replace("version: 1.0.0", "version: v1")), "$.version", Code.PATTERN)).isTrue();
            assertThat(has(errors(SIMPLE.replace("id: simple", "id: Bad_Id")), "$.id", Code.PATTERN)).isTrue();
            assertThat(has(errors(SIMPLE.replace("severity: HIGH", "severity: APOCALYPTIC")), "$.severity", Code.INVALID_VALUE)).isTrue();
            assertThat(has(errors(SIMPLE.replace("logic: AND", "logic: XOR")), "$.conditions.logic", Code.INVALID_VALUE)).isTrue();
            assertThat(has(errors(SIMPLE + "scope: { networks: [dogechain] }\n"), "$.scope.networks", Code.INVALID_VALUE)).isTrue();
            assertThat(has(errors(header("noc")), "$.conditions", Code.REQUIRED)).isTrue();
        }

        @Test void groupLogicIsMandatory() {
            assertThat(has(errors(SIMPLE.replace("  logic: AND\n", "")), "$.conditions.logic", Code.REQUIRED)).isTrue();
        }

        @Test void notGroupNeedsExactlyOneChild() {
            assertThat(has(errors(header("not") + """
                    conditions:
                      logic: NOT
                      rules:
                        - { field: transaction.amount, operator: EXISTS }
                        - { field: transaction.from, operator: EXISTS }
                    """), "$.conditions", Code.INVALID_VALUE)).isTrue();
        }

        @Test void aggregationLimitsAndConflicts() {
            List<ValidationError> e = errors(SIMPLE + """
                    aggregation: { window: 8d, groupBy: [a.b, transaction.from], function: SUM, operator: LESS_THAN, threshold: -1 }
                    """);
            assertThat(has(e, "$.aggregation.window", Code.LIMIT_EXCEEDED)).isTrue();
            assertThat(has(e, "$.aggregation.groupBy[0]", Code.UNKNOWN_FIELD)).isTrue();
            assertThat(has(e, "$.aggregation.field", Code.REQUIRED)).isTrue();
            assertThat(has(e, "$.aggregation.operator", Code.INVALID_OPERATOR)).isTrue();
            assertThat(has(e, "$.aggregation.threshold", Code.INVALID_VALUE)).isTrue();

            assertThat(has(errors(SIMPLE + """
                    aggregation: { window: 5m, function: SUM, field: transaction.from, operator: GREATER_THAN, threshold: 1 }
                    """), "$.aggregation.field", Code.TYPE)).isTrue();
            assertThat(has(errors(SIMPLE + """
                    aggregation: { window: 5m, groupBy: [transaction.from, transaction.to, wallet.address, transaction.hash], function: COUNT, operator: GREATER_THAN, threshold: 1 }
                    """), "$.aggregation.groupBy", Code.LIMIT_EXCEEDED)).isTrue();
            assertThat(has(errors(SIMPLE + """
                    aggregation: { window: 5m, function: COUNT, operator: GREATER_THAN, threshold: 1 }
                    sequence: { key: transaction.from, within: 5m, steps: [ { name: a, conditions: { logic: AND, rules: [ { field: transaction.amount, operator: EXISTS } ] } }, { name: b, conditions: { logic: AND, rules: [ { field: transaction.amount, operator: EXISTS } ] } } ] }
                    """), "$", Code.CONFLICT)).isTrue();
        }

        @Test void evidenceLevelConfirmedRequiresSourceBackedField() {
            assertThat(has(errors(SIMPLE + "evidenceLevel: CONFIRMED_BY_SOURCE\n"), "$.evidenceLevel", Code.CONFLICT)).isTrue();
            assertThat(loader.tryLoad(header("ok") + """
                    evidenceLevel: CONFIRMED_BY_SOURCE
                    conditions: { logic: AND, rules: [ { field: counterparty.sanctioned, operator: EQUALS, value: true } ] }
                    """, Format.YAML).valid()).isTrue();
        }

        @Test void dedupTemplateIsValidated() {
            assertThat(has(errors(SIMPLE + "dedupKey: \"{{rule.id}}:{{event.not.a.field}}\"\n"), "$.dedupKey", Code.INVALID_TEMPLATE)).isTrue();
            assertThat(has(errors(SIMPLE + "dedupKey: \"{{T(java.lang.Runtime)}}\"\n"), "$.dedupKey", Code.INVALID_TEMPLATE)).isTrue();
            assertThat(has(errors(SIMPLE + "dedupKey: \"{{rule.id\"\n"), "$.dedupKey", Code.INVALID_TEMPLATE)).isTrue();
            assertThat(has(errors(SIMPLE + "dedupKey: \"a}}b{{rule.id}}\"\n"), "$.dedupKey", Code.INVALID_TEMPLATE)).isTrue();
        }

        @Test void actionsAndTests() {
            List<ValidationError> e = errors(SIMPLE + """
                    actions:
                      - { type: WEBHOOK, connectorId: "https://evil.example/hook" }
                      - { type: CARRIER_PIGEON, connectorId: x }
                      - { type: SLACK, connectorId: ok, params: { nested: { a: 1 } } }
                    examples:
                      - { name: e, expect: MAYBE, event: { network: tron, type: TOKEN_TRANSFER, fields: { nope.field: 1 } } }
                    tests:
                      - { name: t, events: [ { network: tron, type: TOKEN_TRANSFER, repeat: 20000 } ], expectTriggers: 1 }
                    """);
            assertThat(has(e, "$.actions[0].connectorId", Code.PATTERN)).isTrue();
            assertThat(has(e, "$.actions[1].type", Code.INVALID_VALUE)).isTrue();
            assertThat(has(e, "$.actions[2].params.nested", Code.TYPE)).isTrue();
            assertThat(has(e, "$.examples[0].expect", Code.INVALID_VALUE)).isTrue();
            assertThat(has(e, "$.examples[0].event.fields.nope.field", Code.UNKNOWN_FIELD)).isTrue();
            assertThat(has(e, "$.tests[0].events[0].repeat", Code.LIMIT_EXCEEDED)).isTrue();
        }

        @Test void loadThrowsWithAllErrors() {
            assertThatThrownBy(() -> Fx.rule(SIMPLE.replace("transaction.amount", "bogus.field")))
                    .isInstanceOf(RuleValidationException.class)
                    .satisfies(t -> assertThat(((RuleValidationException) t).errors()).isNotEmpty());
        }
    }
}
