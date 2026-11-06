package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.engine.RuleMatch;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AlertAndWebhookApiTest extends ApiTestSupport {

    @Autowired AlertService alerts;
    @Autowired StubHttp http;

    /** A fresh DETECTED alert (rule without actions), created through the module API. */
    private String newAlert() {
        String key = UUID.randomUUID().toString();
        RuleMatch m = new RuleMatch("test-rule", "1.0.0", "Test rule", Severity.HIGH, EvidenceLevel.ANOMALY, Network.TRON, key, key,
                "tron:0xabc:" + key, Instant.now(), List.of("tron:0xabc:" + key), Map.of("kind", "STATELESS"), List.of(), 0);
        return alerts.onMatch(m, Duration.ofMinutes(5)).alert().id();
    }

    @Test
    void acknowledgeTransition() throws Exception {
        String id = newAlert();
        mvc.perform(get("/api/v1/alerts/" + id).header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.lifecycleStatus").value("DETECTED"))
                .andExpect(jsonPath("$.allowedTransitions", hasItem("ACKNOWLEDGED")));
        mvc.perform(post("/api/v1/alerts/" + id + "/acknowledge").header("X-API-Key", ANALYST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.history[0].action").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.history[0].actor").value(org.hamcrest.Matchers.containsString("analyst")));
        mvc.perform(post("/api/v1/alerts/" + id + "/acknowledge").header("X-API-Key", ANALYST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/conflict"));
    }

    @Test
    void statusWorkflowAssignCommentAndIllegalTransition() throws Exception {
        String id = newAlert();
        mvc.perform(post("/api/v1/alerts/" + id + "/status").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"reason\":\"looking\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVESTIGATING"));
        mvc.perform(post("/api/v1/alerts/" + id + "/assign").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"analyst@example.invalid\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignee").value("analyst@example.invalid"));
        mvc.perform(post("/api/v1/alerts/" + id + "/comments").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"<b>checked</b>\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comments[0].body").value("<b>checked</b>"));
        mvc.perform(post("/api/v1/alerts/" + id + "/comments").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/alerts/" + id + "/status").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
        mvc.perform(post("/api/v1/alerts/" + id + "/status").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INVESTIGATING\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/alerts?status=RESOLVED").header("X-API-Key", READER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].id", hasItem(id)));
    }

    @Test
    void unknownAlertIs404() throws Exception {
        mvc.perform(get("/api/v1/alerts/" + UUID.randomUUID()).header("X-API-Key", READER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/not-found"));
    }

    @Test
    void webhookSecretOnceTestAndDelete() throws Exception {
        mvc.perform(post("/api/v1/webhooks").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"soc\",\"target\":\"http://insecure.example/hook\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].pointer").value("/target"));
        String body = mvc.perform(post("/api/v1/webhooks").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"soc\",\"url\":\"https://hooks.example.test/oci\",\"events\":[\"alert.opened\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").exists())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/api/v1/webhooks").header("X-API-Key", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].secret").doesNotExist());
        int before = http.calls.size();
        mvc.perform(post("/api/v1/webhooks/" + id + "/test").header("X-API-Key", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true))
                .andExpect(jsonPath("$.statusCode").value(204));
        assertThat(http.calls).hasSize(before + 1);
        assertThat(http.headers.getLast()).containsKey("X-OCI-Signature").containsKey("X-OCI-Delivery-Id").containsEntry("X-OCI-Event", "alert.opened");
        mvc.perform(delete("/api/v1/webhooks/" + id).header("X-API-Key", ADMIN)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/webhooks/" + id + "/test").header("X-API-Key", ADMIN)).andExpect(status().isNotFound());
    }

    @Test
    void slackDiscordConnectorsDeliverAndTargetsAreMasked() throws Exception {
        String slack = mvc.perform(post("/api/v1/webhooks").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SLACK\",\"name\":\"soc slack\",\"target\":\"https://hooks.slack.com/services/T000/B000/SECRETTOKEN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SLACK"))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.target").value("https://hooks.slack.com/…"))
                .andReturn().getResponse().getContentAsString();
        String slackId = JsonPath.read(slack, "$.id");
        assertThat(slackId).matches("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");
        // the token-bearing path never appears in listings
        String list = mvc.perform(get("/api/v1/webhooks").header("X-API-Key", ADMIN)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("SECRETTOKEN");
        // delivery goes through the real SlackConnector to the full URL
        int before = http.calls.size();
        mvc.perform(post("/api/v1/webhooks/" + slackId + "/test").header("X-API-Key", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivered").value(true));
        assertThat(http.calls).hasSize(before + 1);
        assertThat(http.calls.getLast().toString()).isEqualTo("https://hooks.slack.com/services/T000/B000/SECRETTOKEN");

        mvc.perform(post("/api/v1/webhooks").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DISCORD\",\"name\":\"soc discord\",\"target\":\"https://discord.com/api/webhooks/1/abc\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("DISCORD"));
        // a Slack connector refuses a non-Slack host
        mvc.perform(post("/api/v1/webhooks").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SLACK\",\"name\":\"x\",\"target\":\"https://evil.example/services/x\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].code").value("invalid_target"));
    }

    @Test
    void unavailableChannelsAreRefusedExplicitly() throws Exception {
        mvc.perform(post("/api/v1/webhooks").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EMAIL\",\"name\":\"mail\",\"target\":\"soc@example.com\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].pointer").value("/type"))
                .andExpect(jsonPath("$.errors[0].code").value("unsupported"));
        // no OCI_TELEGRAM_BOT_TOKEN in the test profile
        mvc.perform(post("/api/v1/webhooks").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TELEGRAM\",\"name\":\"tg\",\"target\":\"-1001234567890\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("not_configured"));
    }
}
