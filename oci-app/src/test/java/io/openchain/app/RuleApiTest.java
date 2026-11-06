package io.openchain.app;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

class RuleApiTest extends ApiTestSupport {

    static final String VALID = """
            apiVersion: openchain.io/rule/v1
            id: test-big-transfer
            version: 1.0.0
            name: Test big transfer
            author: test-suite
            enabled: true
            scope:
              networks: [tron]
              eventTypes: [TOKEN_TRANSFER]
            conditions:
              logic: AND
              rules:
                - field: transaction.amount
                  operator: GREATER_THAN_OR_EQUAL
                  value: "10000"
            severity: LOW
            evidenceLevel: ANOMALY
            cooldown: 10m
            documentation: test rule
            examples:
              - name: big
                expect: MATCH
                event: { fields: { transaction.amount: "20000" } }
            """;

    static String json(String field, String yaml) {
        return JsonMapper.builder().build().writeValueAsString(java.util.Map.of(field, yaml));
    }

    @Test
    void repositoryRulesAreLoaded() throws Exception {
        mvc.perform(get("/api/v1/rules").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem("large-usdt-transfer")))
                .andExpect(jsonPath("$.items[0].yaml", containsString("apiVersion")));
        mvc.perform(get("/api/v1/rules/fields").header("X-API-Key", READER))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].path", hasItem("transaction.amount")))
                .andExpect(jsonPath("$[?(@.path == 'transaction.amount')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'transaction.amount_usd')].available", hasItem(false)))
                .andExpect(jsonPath("$[?(@.path == 'network')].type", hasItem("enum")));
        mvc.perform(get("/api/v1/rules/event-types").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'TOKEN_TRANSFER')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.type == 'APPROVAL')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.type == 'CONTRACT_ADMIN_CHANGE')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.type == 'CONTRACT_CALL')].available", hasItem(false)));
    }

    @Test
    void validateValidRuleIs200() throws Exception {
        mvc.perform(post("/api/v1/rules/validate").header("X-API-Key", READER).contentType(MediaType.APPLICATION_JSON)
                        .content(json("yaml", VALID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.compiledHash", startsWith("sha256:")));
        mvc.perform(post("/api/v1/rules/validate").header("X-API-Key", READER).contentType("application/yaml").content(VALID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void validateInvalidRuleIs422WithPointers() throws Exception {
        String bad = VALID.replace("transaction.amount\n", "transaction.not_a_field\n").replace("severity: LOW", "severity: SEVERE");
        mvc.perform(post("/api/v1/rules/validate").header("X-API-Key", READER).contentType(MediaType.APPLICATION_JSON)
                        .content(json("source", bad)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/validation-error"))
                .andExpect(jsonPath("$.errors.length()", greaterThan(0)))
                .andExpect(jsonPath("$.errors[*].pointer", hasItem(startsWith("/"))));
        mvc.perform(post("/api/v1/rules/validate").header("X-API-Key", READER).contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createIsDisabledThenConflictEnableTestExportDelete() throws Exception {
        String yaml = VALID.replace("test-big-transfer", "test-lifecycle");
        mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON).content(json("yaml", yaml)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("test-lifecycle"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON).content(json("yaml", yaml)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/conflict"));
        mvc.perform(post("/api/v1/rules").header("X-API-Key", READER).contentType(MediaType.APPLICATION_JSON).content(json("yaml", yaml)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/rules/test-lifecycle/enable").header("X-API-Key", ANALYST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/api/v1/rules/test-lifecycle/test").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2000-01-01T00:00:00Z\",\"to\":\"2100-01-01T00:00:00Z\",\"network\":\"tron\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DRY_RUN"))
                .andExpect(jsonPath("$.evaluatedEvents", greaterThan(0)))
                .andExpect(jsonPath("$.matches").isArray());
        mvc.perform(get("/api/v1/rules/test-lifecycle/export").header("X-API-Key", READER))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id: test-lifecycle")));
        mvc.perform(delete("/api/v1/rules/test-lifecycle").header("X-API-Key", ANALYST)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/rules/test-lifecycle").header("X-API-Key", READER)).andExpect(status().isNotFound());
    }

    @Test
    void unknownRuleIs404Problem() throws Exception {
        mvc.perform(get("/api/v1/rules/no-such-rule").header("X-API-Key", READER))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/not-found"));
    }

    @Test
    void importIsAllOrNothing() throws Exception {
        String a = VALID.replace("test-big-transfer", "test-import-a");
        String b = VALID.replace("test-big-transfer", "test-import-b").replace("severity: LOW", "severity: NOPE");
        mvc.perform(post("/api/v1/rules/import").header("X-API-Key", ANALYST).contentType("application/yaml").content(a + "---\n" + b))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].pointer", startsWith("/documents/1")));
        mvc.perform(get("/api/v1/rules/test-import-a").header("X-API-Key", READER)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/rules/import").header("X-API-Key", ANALYST).contentType("application/yaml").content(a))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("test-import-a"));
    }
}
