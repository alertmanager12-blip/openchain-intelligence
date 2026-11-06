package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Contract between the console's rule builder and the backend DSL: the YAML that the frontend serializer emits for
 * its fixture draft (asserted byte-for-byte by frontend/src/app/core/rules/rule-yaml.spec.ts) must validate, be
 * creatable and enable-able through the API.
 */
class FrontendRuleContractTest extends ApiTestSupport {

    static String fixtureYaml() throws Exception {
        Path f = Path.of("../frontend/src/app/core/rules/fixtures/builder-rule.fixture.ts");
        String ts = Files.readString(f);
        String start = "export const BUILDER_RULE_YAML = `";
        int a = ts.indexOf(start), b = ts.indexOf("`;", a);
        assertThat(a).as("fixture marker").isPositive();
        return ts.substring(a + start.length(), b);
    }

    @Test
    void builderYamlValidatesCreatesAndEnables() throws Exception {
        String yaml = fixtureYaml();
        assertThat(yaml).startsWith("apiVersion: openchain.io/rule/v1\n");
        mvc.perform(post("/api/v1/rules/validate").header("X-API-Key", READER).contentType("application/yaml").content(yaml))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.ruleId").value("builder-large-usdt-outflow"))
                .andExpect(jsonPath("$.kind").value("AGGREGATION"));
        String body = RuleApiTest.json("yaml", yaml);
        mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("builder-large-usdt-outflow"))
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/v1/rules/builder-large-usdt-outflow/enable").header("X-API-Key", ANALYST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.yaml").value(yaml));
    }
}
