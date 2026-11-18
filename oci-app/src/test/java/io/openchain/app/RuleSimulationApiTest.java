package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.alerting.alert.AlertRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 4, ADR-0023 amendment 2: the impact of a rule change on the history held, before and after, SIMULATED and without
 * side effect (no alert, no notification, the stored rule unchanged).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RuleSimulationApiTest extends ApiTestSupport {
    static final String RULE = """
            apiVersion: openchain.io/rule/v1
            id: sim-threshold
            version: 1.0.0
            name: Simulation threshold
            author: test-suite
            enabled: false
            scope:
              networks: [tron]
              eventTypes: [TOKEN_TRANSFER]
            conditions:
              logic: AND
              rules:
                - field: transaction.amount
                  operator: GREATER_THAN_OR_EQUAL
                  value: "THRESHOLD"
            severity: LOW
            evidenceLevel: ANOMALY
            cooldown: 1m
            """;

    @Autowired AlertRepository alerts;
    @Autowired ApiTestSupport.StubHttp outbound;

    String body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    @Test void aStricterThresholdShowsWhatWouldStopMatchingAndChangesNothing() throws Exception {
        mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType("application/yaml").content(RULE.replace("THRESHOLD", "0")))
                .andExpect(status().isCreated());
        String stored = body(mvc.perform(get("/api/v1/rules/sim-threshold").header("X-API-Key", READER)));
        int alertsBefore = alerts.findAll().size(), sentBefore = outbound.calls.size();

        String proposed = RULE.replace("THRESHOLD", "1000000").replace("version: 1.0.0", "version: 1.1.0");
        String r = body(mvc.perform(post("/api/v1/rules/sim-threshold/simulate").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.builder().build().writeValueAsString(java.util.Map.of("source", proposed, "network", "tron"))))
                .andExpect(status().isOk()));
        assertThat((String) JsonPath.read(r, "$.level")).isEqualTo("SIMULATED");
        assertThat((Integer) JsonPath.read(r, "$.evaluatedEvents")).isPositive();
        int before = JsonPath.read(r, "$.before.conditionMatches"), after = JsonPath.read(r, "$.after.conditionMatches");
        assertThat(before).isPositive();
        assertThat(after).isLessThan(before);
        assertThat((Integer) JsonPath.read(r, "$.onlyBefore")).isEqualTo(before - after);
        assertThat((Integer) JsonPath.read(r, "$.onlyAfter")).isZero();
        assertThat((List<?>) JsonPath.read(r, "$.onlyBeforeSample")).isNotEmpty();
        assertThat((String) JsonPath.read(r, "$.window.note")).contains("in memory");

        // no side effect: no alert, no notification, the stored rule unchanged
        assertThat(alerts.findAll()).hasSize(alertsBefore);
        assertThat(outbound.calls).hasSize(sentBefore);
        assertThat(body(mvc.perform(get("/api/v1/rules/sim-threshold").header("X-API-Key", READER)))).isEqualTo(stored);
    }

    @Test void refusals() throws Exception {
        mvc.perform(post("/api/v1/rules/no-such-rule/simulate").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType("application/yaml")
                .content(RULE.replace("THRESHOLD", "5").replace("sim-threshold", "sim-other"))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/rules/sim-other/simulate").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/rules/sim-other/simulate").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.builder().build().writeValueAsString(java.util.Map.of("source", RULE.replace("THRESHOLD", "5")
                        .replace("sim-threshold", "another-id"))))).andExpect(status().isBadRequest()); // the proposal must keep the rule id
    }
}
