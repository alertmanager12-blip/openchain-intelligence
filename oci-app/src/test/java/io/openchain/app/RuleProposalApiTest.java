package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.ClientScopeDirectory;
import io.openchain.app.security.Scopes;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/** ADR-0022 amendment 4 through the API: drafts are checked and dry-run, never stored, and adopting one creates it disabled. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // adds scopes, keys, labels, flows and rules
class RuleProposalApiTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-17T06:00:00Z");
    static int seq;

    @Autowired IntelligencePipeline intel;
    @Autowired ClientScopeDirectory scopes;
    @Autowired ApiKeyService keys;
    @Autowired ApiTestSupport.StubHttp outbound;

    static Address tron(String tag) { return new Address(Network.TRON, ("TPro" + tag + "000000000000000000000000000000000").substring(0, 34)); }

    void flow(Address from, Address to, String amount, int minute) {
        String tx = "%064x".formatted(0xF0_0000L + (++seq));
        intel.flows().add(ValueFlow.of(new TokenTransfer(Network.TRON, tx, 0, 9_000 + seq, "0xpro" + seq, T0.plusSeconds(60L * minute), from, to,
                USDT, new BigDecimal(amount).movePointRight(6).toBigIntegerExact())));
    }

    String body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String investigate(Address me) throws Exception {
        String id = JsonPath.read(body(mvc.perform(post("/api/v1/investigations").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                .content("{\"network\":\"tron\",\"address\":\"" + me.value() + "\"}")).andExpect(status().isAccepted())), "$.id");
        for (int i = 0; i < 100; i++) {
            String s = JsonPath.read(body(mvc.perform(get("/api/v1/investigations/" + id).header("X-API-Key", ANALYST))), "$.status");
            if (s.equals("DONE")) return id;
            Thread.sleep(50);
        }
        throw new AssertionError("investigation did not finish");
    }

    @Test void draftsAreCheckedDryRunAndAnAdoptedOneIsCreatedDisabled() throws Exception {
        Address me = tron("subject"), s = tron("listed"), x = tron("next");
        intel.labels().save(new AddressLabel(s, LabelCategory.SANCTIONED, "test", "test-list", 1.0, T0.minusSeconds(86_400),
                EvidenceLevel.CONFIRMED_BY_SOURCE, "test://s"));
        flow(s, me, "1000", 1);
        flow(me, x, "600", 2);
        String id = investigate(me);

        int sentBefore = outbound.calls.size();
        String r = body(mvc.perform(post("/api/v1/investigations/" + id + "/rule-proposals").header("X-API-Key", READER_INV)).andExpect(status().isOk()));
        assertThat(outbound.calls).as("the dry run never notifies").hasSize(sentBefore);
        // a POST, like POST /rules/test: computing a dry run on request; investigation:read is enough, there is no GET
        mvc.perform(get("/api/v1/investigations/" + id + "/rule-proposals").header("X-API-Key", READER_INV)).andExpect(status().isMethodNotAllowed());
        assertThat((String) JsonPath.read(r, "$.proposalVersion")).isEqualTo("proposal@1");
        List<String> templates = JsonPath.read(r, "$.proposals[*].template");
        assertThat(templates).contains("NEXT_HOP", "LISTED_COUNTERPARTY");
        List<Boolean> disabled = JsonPath.read(r, "$.proposals[*].enabled");
        assertThat(disabled).isNotEmpty().allMatch(e -> !e);
        List<Integer> examples = JsonPath.read(r, "$.proposals[*].checks.examplesPassed");
        assertThat(examples).allMatch(n -> n >= 2);
        List<Map<String, Object>> dry = JsonPath.read(r, "$.proposals[*].dryRun");
        assertThat(dry).allSatisfy(d -> assertThat(d).containsKeys("totalEvents", "eventsInScope", "triggers", "from", "to"));

        // proposals are not stored: listing rules shows none of them
        String ruleId = JsonPath.read(r, "$.proposals[0].ruleId");
        mvc.perform(get("/api/v1/rules/" + ruleId).header("X-API-Key", ADMIN)).andExpect(status().isNotFound());

        // adopting one goes through the rules API (rules:write): it is created disabled
        String yaml = JsonPath.read(r, "$.proposals[0].yaml");
        mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType("application/yaml").content(yaml)).andExpect(status().isCreated());
        String created = body(mvc.perform(get("/api/v1/rules/" + ruleId).header("X-API-Key", ADMIN)).andExpect(status().isOk()));
        assertThat((Boolean) JsonPath.read(created, "$.enabled")).isFalse();
        // and asking for proposals again changes nothing in the rules
        mvc.perform(post("/api/v1/investigations/" + id + "/rule-proposals").header("X-API-Key", ANALYST)).andExpect(status().isOk());
        assertThat((Boolean) JsonPath.read(body(mvc.perform(get("/api/v1/rules/" + ruleId).header("X-API-Key", ADMIN))), "$.enabled")).isFalse();
    }

    @Test void aRejectedAssertionGivesNoProposalAndAnotherClientGets404() throws Exception {
        Address me = tron("rejecting"), x = tron("rejNext");
        flow(me, x, "700", 1);
        String id = investigate(me);
        String r = body(mvc.perform(post("/api/v1/investigations/" + id + "/rule-proposals").header("X-API-Key", ANALYST)).andExpect(status().isOk()));
        List<String> ids = JsonPath.read(r, "$.proposals[?(@.template == 'NEXT_HOP')].assertionIds[0]");
        assertThat(ids).isNotEmpty();
        mvc.perform(post("/api/v1/investigations/" + id + "/assertions/" + ids.getFirst() + "/reviews").header("X-API-Key", ANALYST)
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED\"}")).andExpect(status().isCreated());
        String after = body(mvc.perform(post("/api/v1/investigations/" + id + "/rule-proposals").header("X-API-Key", ANALYST)));
        assertThat((List<String>) JsonPath.read(after, "$.proposals[*].assertionIds[*]")).doesNotContain(ids.getFirst());
        assertThat((List<String>) JsonPath.read(after, "$.skipped[?(@.assertionId == '" + ids.getFirst() + "')].reason")).containsExactly("rejected by a review");

        scopes.register("client-p", "P");
        String keyP = keys.create("p", List.of(Scopes.INVESTIGATION_READ), Duration.ofHours(1), "test", "client-p").plaintext();
        mvc.perform(post("/api/v1/investigations/" + id + "/rule-proposals").header("X-API-Key", keyP)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/investigations/" + id + "/rule-proposals").header("X-API-Key", READER)).andExpect(status().isForbidden());
    }

    static final String READER_INV = "oci_test_invread1_TestInvReaderKeyOnlyForUnitTests00000001";
}
