package io.openchain.app;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class DashboardAndRoutingApiTest extends ApiTestSupport {

    @Test
    void unknownRoutesAreProblemJson() throws Exception {
        for (var req : new org.springframework.test.web.servlet.RequestBuilder[]{
                get("/api/v1/does-not-exist").header("X-API-Key", READER),
                post("/api/v1/does-not-exist").header("X-API-Key", READER),
                get("/api/v1/wallets").header("X-API-Key", READER)}) {
            mvc.perform(req)
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://openchain.io/problems/not-found"))
                    .andExpect(jsonPath("$.title").value("Resource not found"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.instance", startsWith("/api/v1/")))
                    .andExpect(jsonPath("$.traceId", isA(String.class)));
        }
        // unknown route without credentials: authentication comes first
        mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/unauthenticated"));
    }

    @Test
    void dashboardWithPermissionAwareSections() throws Exception {
        mvc.perform(get("/api/v1/dashboard").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.walletsTracked", greaterThan(0)))
                .andExpect(jsonPath("$.totals.eventsIndexed", greaterThan(0)))
                .andExpect(jsonPath("$.totals.alerts", greaterThan(0)))
                .andExpect(jsonPath("$.alerts.byStatus.OPEN").exists())
                .andExpect(jsonPath("$.alerts.openBySeverity.CRITICAL").exists())
                .andExpect(jsonPath("$.alerts.recent.length()").value(5))
                .andExpect(jsonPath("$.riskDistribution.HIGH").exists())
                .andExpect(jsonPath("$.networks[0].network").value("tron"))
                .andExpect(jsonPath("$.activity.length()").value(24))
                .andExpect(jsonPath("$.recentSignals").doesNotExist())
                .andExpect(jsonPath("$.omittedSections", hasItem(startsWith("recentSignals"))));
        mvc.perform(get("/api/v1/dashboard").header("X-API-Key", ANALYST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentSignals").isArray())
                .andExpect(jsonPath("$.totals.signals24h", greaterThan(0)));
    }

    @Test
    void dashboardSectionsOfTheOverview() throws Exception {
        String body = mvc.perform(get("/api/v1/dashboard").header("X-API-Key", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.gaps").isArray())
                .andExpect(jsonPath("$.notifications.unroutedRules").isArray())
                .andExpect(jsonPath("$.notifications.openAlertsUnrouted").isNumber())
                .andExpect(jsonPath("$.alerts.attention").isArray())
                .andExpect(jsonPath("$.alerts.last24h.total").isNumber())
                .andExpect(jsonPath("$.alerts.last24h.byRule").isArray())
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> sev = com.jayway.jsonpath.JsonPath.read(body, "$.alerts.attention[*].severity");
        java.util.List<String> st = com.jayway.jsonpath.JsonPath.read(body, "$.alerts.attention[*].status");
        java.util.List<String> order = java.util.List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
        org.assertj.core.api.Assertions.assertThat(sev).hasSizeLessThanOrEqualTo(5).isNotEmpty()
                .isSortedAccordingTo(java.util.Comparator.comparingInt(order::indexOf));
        org.assertj.core.api.Assertions.assertThat(st).containsOnly("OPEN");
        Integer total = com.jayway.jsonpath.JsonPath.read(body, "$.alerts.last24h.total");
        java.util.List<Integer> counts = com.jayway.jsonpath.JsonPath.read(body, "$.alerts.last24h.byRule[*].count");
        org.assertj.core.api.Assertions.assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(total);
        org.assertj.core.api.Assertions.assertThat(counts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        java.util.List<String> names = com.jayway.jsonpath.JsonPath.read(body, "$.alerts.last24h.byRule[*].ruleName");
        org.assertj.core.api.Assertions.assertThat(names).doesNotContainNull();
        // reader has rules:read but not webhooks:read: notifications are omitted, and said so
        mvc.perform(get("/api/v1/dashboard").header("X-API-Key", READER))
                .andExpect(jsonPath("$.notifications").doesNotExist())
                .andExpect(jsonPath("$.omittedSections", hasItem(startsWith("notifications"))))
                .andExpect(jsonPath("$.coverage.gaps").isArray());
    }

    @Test
    void networksCarryTheLastIndexedBlockTime() throws Exception {
        String body = mvc.perform(get("/api/v1/dashboard").header("X-API-Key", READER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String t = com.jayway.jsonpath.JsonPath.read(body, "$.networks[0].lastIndexedBlockTime");
        org.assertj.core.api.Assertions.assertThat(java.time.Instant.parse(t)).isNotNull();
    }

    @Test
    void networksCarryLimitationCodesInTheSameOrderAsTheirText() throws Exception {
        String body = mvc.perform(get("/api/v1/dashboard").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.networks[0].limitationCodes").isArray())
                .andReturn().getResponse().getContentAsString();
        int texts = com.jayway.jsonpath.JsonPath.<java.util.List<?>>read(body, "$.networks[0].limitations").size();
        int codes = com.jayway.jsonpath.JsonPath.<java.util.List<?>>read(body, "$.networks[0].limitationCodes").size();
        org.assertj.core.api.Assertions.assertThat(codes).isEqualTo(texts);
    }

    @Test
    void demoScenariosProduceEvidenceThroughTheRealPipeline() throws Exception {
        var r = mvc.perform(get("/api/v1/demo/scenarios").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fictitious").value(true))
                .andExpect(jsonPath("$.items.length()").value(6))
                .andReturn().getResponse().getContentAsString();
        for (int i = 0; i < 6; i++) {
            String p = "$.items[" + i + "].checks.";
            for (String c : new String[]{"eventsIndexed", "expectedSignalDetected", "riskScored", "expectedRuleTriggered", "notificationSimulated"}) {
                Object v = com.jayway.jsonpath.JsonPath.read(r, p + c);
                org.assertj.core.api.Assertions.assertThat(v).as("scenario %s %s", (Object) com.jayway.jsonpath.JsonPath.read(r, "$.items[" + i + "].id"), c)
                        .isEqualTo(true);
            }
        }
        mvc.perform(get("/api/v1/demo/scenarios")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/alerts?limit=200&ruleId=large-usdt-transfer").header("X-API-Key", READER))
                .andExpect(jsonPath("$.items[*].demoScenario", hasItem("A")));
    }
}
