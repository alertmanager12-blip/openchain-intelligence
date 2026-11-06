package io.openchain.alerting.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Network;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The published JSON Schema must stay in sync with the Java model enforced by RuleValidator. */
class SchemaConsistencyTest {

    static JsonNode schema() throws Exception {
        try (InputStream in = SchemaConsistencyTest.class.getResourceAsStream("/schema/rule-v1.schema.json")) {
            assertThat(in).as("schema resource").isNotNull();
            return JsonMapper.builder().build().readTree(in);
        }
    }

    static List<String> enumOf(JsonNode n) {
        return StreamSupport.stream(n.get("enum").spliterator(), false).map(JsonNode::asString).toList();
    }

    static <E extends Enum<E>> List<String> names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    @Test void schemaMatchesModel() throws Exception {
        JsonNode s = schema();
        JsonNode p = s.get("properties");
        JsonNode defs = s.get("$defs");
        assertThat(s.get("$schema").asString()).contains("2020-12");
        assertThat(p.get("apiVersion").get("const").asString()).isEqualTo(Rule.API_VERSION);
        assertThat(p.get("id").get("pattern").asString()).isEqualTo(RuleValidator.SLUG.pattern());
        assertThat(p.get("version").get("pattern").asString()).isEqualTo(RuleValidator.SEMVER.pattern());
        assertThat(defs.get("fieldName").get("pattern").asString()).isEqualTo(FieldCatalog.NAME.pattern());
        assertThat(enumOf(defs.get("leaf").get("properties").get("operator"))).containsExactlyElementsOf(names(Operator.class));
        assertThat(enumOf(defs.get("group").get("properties").get("logic"))).containsExactlyElementsOf(names(Condition.Logic.class));
        assertThat(enumOf(p.get("aggregation").get("properties").get("function"))).containsExactlyElementsOf(names(AggregationFunction.class));
        assertThat(enumOf(p.get("actions").get("items").get("properties").get("type"))).containsExactlyElementsOf(names(ActionType.class));
        assertThat(enumOf(p.get("severity"))).containsExactlyElementsOf(names(Severity.class));
        assertThat(enumOf(p.get("evidenceLevel"))).containsExactlyElementsOf(names(EvidenceLevel.class));
        assertThat(enumOf(defs.get("eventType"))).containsExactlyElementsOf(names(EventType.class));
        assertThat(enumOf(p.get("scope").get("properties").get("networks").get("items")))
                .containsExactlyElementsOf(Arrays.stream(Network.values()).map(Network::id).toList());
        // top-level properties == validator whitelist
        assertThat(p.propertyNames()).containsExactlyInAnyOrder("apiVersion", "id", "version", "name", "description", "author",
                "enabled", "scope", "conditions", "aggregation", "sequence", "severity", "evidenceLevel", "actions", "cooldown",
                "dedupKey", "documentation", "expectedConfidence", "tags", "examples", "tests");
        assertThat(s.get("additionalProperties").asBoolean()).isFalse();
    }
}
