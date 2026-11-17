package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.app.config.OciProperties;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.ClientScopeDirectory;
import io.openchain.app.security.Scopes;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/** ADR-0022 amendment 3 through the API: a review sits next to an assertion and changes nothing it states. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // adds scopes, keys and flows, toggles four-eyes
class AssertionReviewApiTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");
    static int seq;

    @Autowired IntelligencePipeline intel;
    @Autowired ClientScopeDirectory scopes;
    @Autowired ApiKeyService keys;
    @Autowired OciProperties props;

    static Address tron(String tag) { return new Address(Network.TRON, ("TRev" + tag + "000000000000000000000000000000000").substring(0, 34)); }

    void flow(Address from, Address to, String amount, int minute) {
        String tx = "%064x".formatted(0xE0_0000L + (++seq));
        intel.flows().add(ValueFlow.of(new TokenTransfer(Network.TRON, tx, 0, 7_000 + seq, "0xrev" + seq, T0.plusSeconds(60L * minute), from, to,
                USDT, new BigDecimal(amount).movePointRight(6).toBigIntegerExact())));
    }

    String body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String await(String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            String r = body(mvc.perform(get("/api/v1/investigations/" + id).header("X-API-Key", ANALYST)).andExpect(status().isOk()));
            String s = JsonPath.read(r, "$.status");
            if (s.equals("DONE") || s.equals("FAILED")) return r;
            Thread.sleep(50);
        }
        throw new AssertionError("investigation did not finish");
    }

    String investigate(String tag) throws Exception {
        Address me = tron(tag);
        flow(tron(tag + "In"), me, "500", 1);
        flow(me, tron(tag + "Out"), "300", 2);
        String id = JsonPath.read(body(mvc.perform(post("/api/v1/investigations").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                .content("{\"network\":\"tron\",\"address\":\"" + me.value() + "\"}")).andExpect(status().isAccepted())), "$.id");
        assertThat((String) JsonPath.read(await(id), "$.status")).isEqualTo("DONE");
        return id;
    }

    org.springframework.test.web.servlet.ResultActions review(String key, String id, String assertion, String json) throws Exception {
        return mvc.perform(post("/api/v1/investigations/" + id + "/assertions/" + assertion + "/reviews").header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test void aReviewIsRecordedNextToTheAssertionAndChangesNothingItStates() throws Exception {
        String id = investigate("same");
        String before = await(id);
        String assertion = JsonPath.read(before, "$.assertions[0].id");

        String r1 = body(review(ANALYST, id, assertion, "{\"decision\":\"NEEDS_EVIDENCE\",\"comment\":\"which blocks?\"}").andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(r1, "$.decision")).isEqualTo("NEEDS_EVIDENCE");
        assertThat((String) JsonPath.read(r1, "$.actor")).startsWith("apikey:analyst:");
        review(ANALYST, id, assertion, "{\"decision\":\"ACCEPTED\"}").andExpect(status().isCreated());

        String after = await(id);
        assertThat((Object) JsonPath.read(after, "$.assertions")).as("assertions and levels unchanged").isEqualTo(JsonPath.read(before, "$.assertions"));
        assertThat((Object) JsonPath.read(after, "$.report")).as("report text unchanged").isEqualTo(JsonPath.read(before, "$.report"));

        String all = body(mvc.perform(get("/api/v1/investigations/" + id + "/reviews").header("X-API-Key", READER_INV)).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(all, "$.history[*].decision")).containsExactly("NEEDS_EVIDENCE", "ACCEPTED");
        Map<String, Object> current = JsonPath.read(all, "$.current." + assertion);
        assertThat(current).containsEntry("decision", "ACCEPTED").containsEntry("version", 1).containsEntry("inherited", false);
        assertThat((Integer) JsonPath.read(all, "$.decided")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(all, "$.total")).isEqualTo(((List<?>) JsonPath.read(before, "$.assertions")).size());
    }

    @Test void aDecisionOnOneVersionIsShownOnTheNextForTheSameAssertionNotCopied() throws Exception {
        String v1 = investigate("versions");
        String assertion = JsonPath.read(await(v1), "$.assertions[0].id");
        review(ANALYST, v1, assertion, "{\"decision\":\"REJECTED\",\"comment\":\"wrong token\"}").andExpect(status().isCreated());
        String v2 = JsonPath.read(body(mvc.perform(post("/api/v1/investigations/" + v1 + "/rerun").header("X-API-Key", ANALYST))
                .andExpect(status().isAccepted())), "$.id");
        String d2 = await(v2);
        assertThat((List<String>) JsonPath.read(d2, "$.assertions[*].id")).contains(assertion);

        String all = body(mvc.perform(get("/api/v1/investigations/" + v2 + "/reviews").header("X-API-Key", ANALYST)).andExpect(status().isOk()));
        Map<String, Object> current = JsonPath.read(all, "$.current." + assertion);
        assertThat(current).containsEntry("decision", "REJECTED").containsEntry("version", 1).containsEntry("inherited", true);
        // the version-1 decision is shown on version 2, never copied: v1 still has exactly one review
        String one = body(mvc.perform(get("/api/v1/investigations/" + v1 + "/reviews").header("X-API-Key", ANALYST)));
        assertThat((List<?>) JsonPath.read(one, "$.history")).hasSize(1);
    }

    @Test void refusals() throws Exception {
        String id = investigate("refusals");
        String assertion = JsonPath.read(await(id), "$.assertions[0].id");
        review(ANALYST, id, "as_not_in_this_investigation", "{\"decision\":\"ACCEPTED\"}").andExpect(status().isNotFound());
        review(ANALYST, "no-such-id", assertion, "{\"decision\":\"ACCEPTED\"}").andExpect(status().isNotFound());
        review(ANALYST, id, assertion, "{\"decision\":\"MAYBE\"}").andExpect(status().isBadRequest());
        review(ANALYST, id, assertion, "{}").andExpect(status().isBadRequest());
        review(ANALYST, id, assertion, "{\"decision\":\"ACCEPTED\",\"comment\":\"" + "x".repeat(2001) + "\"}").andExpect(status().isBadRequest());
        // reading reviews needs investigation:read only; reviewing needs investigation:review
        review(READER_INV, id, assertion, "{\"decision\":\"ACCEPTED\"}").andExpect(status().isForbidden());
        String writer = keys.create("writer-only", List.of(Scopes.INVESTIGATION_READ, Scopes.INVESTIGATION_WRITE), Duration.ofHours(1), "test", "default")
                .plaintext();
        review(writer, id, assertion, "{\"decision\":\"ACCEPTED\"}").andExpect(status().isForbidden());
    }

    @Test void anotherClientCannotReadNorReview() throws Exception {
        String id = investigate("isolated");
        String assertion = JsonPath.read(await(id), "$.assertions[0].id");
        review(ANALYST, id, assertion, "{\"decision\":\"ACCEPTED\"}").andExpect(status().isCreated());
        scopes.register("client-r", "R");
        String keyR = keys.create("r", List.of(Scopes.INVESTIGATION_READ, Scopes.INVESTIGATION_REVIEW), Duration.ofHours(1), "test", "client-r").plaintext();
        mvc.perform(get("/api/v1/investigations/" + id + "/reviews").header("X-API-Key", keyR)).andExpect(status().isNotFound());
        review(keyR, id, assertion, "{\"decision\":\"REJECTED\"}").andExpect(status().isNotFound());
    }

    @Test void withFourEyesTheCreatorCannotReviewItsOwnInvestigation() throws Exception {
        String id = investigate("foureyes");
        String assertion = JsonPath.read(await(id), "$.assertions[0].id");
        String other = keys.create("second-analyst", List.of(Scopes.INVESTIGATION_READ, Scopes.INVESTIGATION_REVIEW), Duration.ofHours(1), "test", "default")
                .plaintext();
        props.getInvestigation().setFourEyes(true);
        try {
            review(ANALYST, id, assertion, "{\"decision\":\"ACCEPTED\"}").andExpect(status().isForbidden());
            review(other, id, assertion, "{\"decision\":\"ACCEPTED\"}").andExpect(status().isCreated());
        } finally {
            props.getInvestigation().setFourEyes(false);
        }
        review(ANALYST, id, assertion, "{\"decision\":\"ACCEPTED\"}").andExpect(status().isCreated());
    }

    /** investigation:read only. */
    static final String READER_INV = "oci_test_invread1_TestInvReaderKeyOnlyForUnitTests00000001";
}
