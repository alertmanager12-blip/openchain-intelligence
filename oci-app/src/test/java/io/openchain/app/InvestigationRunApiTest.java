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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/** ADR-0022 amendment 2 through the API: the plan runs, the report cites only stored assertions, isolation holds. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // adds scopes, keys, labels and flows
class InvestigationRunApiTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-16T08:00:00Z");
    static int seq;

    @Autowired IntelligencePipeline intel;
    @Autowired ClientScopeDirectory scopes;
    @Autowired ApiKeyService keys;

    static Address tron(String tag) { return new Address(Network.TRON, ("TInv" + tag + "000000000000000000000000000000000").substring(0, 34)); }

    void flow(Address from, Address to, String amount, int minute) {
        String tx = "%064x".formatted(0xD0_0000L + (++seq));
        intel.flows().add(ValueFlow.of(new TokenTransfer(Network.TRON, tx, 0, 5_000 + seq, "0xblock" + seq, T0.plusSeconds(60L * minute), from, to,
                USDT, new BigDecimal(amount).movePointRight(6).toBigIntegerExact())));
    }

    String await(String key, String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            String r = mvc.perform(get("/api/v1/investigations/" + id).header("X-API-Key", key)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String s = JsonPath.read(r, "$.status");
            if (s.equals("DONE") || s.equals("FAILED")) return r;
            Thread.sleep(50);
        }
        throw new AssertionError("investigation did not finish");
    }

    @Test void anInvestigationRunsThePlanAndItsReportCitesOnlyItsAssertions() throws Exception {
        Address me = tron("subject"), s = tron("listed"), x = tron("out");
        intel.labels().save(new AddressLabel(s, LabelCategory.SANCTIONED, "test", "test-list", 1.0, T0.minusSeconds(86_400),
                EvidenceLevel.CONFIRMED_BY_SOURCE, "test://s"));
        flow(s, me, "1000", 1);
        flow(me, x, "600", 2);
        String created = mvc.perform(post("/api/v1/investigations").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                .content("{\"network\":\"tron\",\"address\":\"" + me.value() + "\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String done = await(ANALYST, id);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(done, "$.plan")).startsWith("plan@1");

        Set<String> ids = new HashSet<>(JsonPath.read(done, "$.assertions[*].id"));
        List<String> cited = JsonPath.read(done, "$.report.sections[*].sentences[*].assertionId");
        assertThat(ids).isNotEmpty();
        assertThat(cited).isNotEmpty().allMatch(ids::contains);
        List<String> types = JsonPath.read(done, "$.assertions[*].type");
        assertThat(types).contains("COVERAGE", "DIRECT_EXPOSURE", "TRACE_ATTRIBUTION");
        assertThat((String) JsonPath.read(done, "$.report.sections[0].title")).isEqualTo("Coverage and limits");
        List<String> levels = JsonPath.read(done, "$.assertions[?(@.type == 'DIRECT_EXPOSURE')].level");
        assertThat(levels).containsExactly("INFERRED");

        mvc.perform(get("/api/v1/investigations/" + id + "/report").header("X-API-Key", ANALYST)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/traces/" + id).header("X-API-Key", ANALYST)).andExpect(status().isNotFound()); // not a trace

        String v2 = JsonPath.read(mvc.perform(post("/api/v1/investigations/" + id + "/rerun").header("X-API-Key", ANALYST))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.id");
        String d2 = await(ANALYST, v2);
        assertThat((Integer) JsonPath.read(d2, "$.version")).isEqualTo(2);
        assertThat((List<?>) JsonPath.read(d2, "$.diff.removed")).isEmpty();
    }

    @Test void anotherClientsInvestigationDoesNotExistForIt() throws Exception {
        scopes.register("client-c", "C");
        String keyC = keys.create("c", List.of(Scopes.INVESTIGATION_READ, Scopes.INVESTIGATION_WRITE), Duration.ofHours(1), "test", "client-c").plaintext();
        Address me = tron("isolated");
        flow(me, tron("isoOut"), "100", 1);
        String id = JsonPath.read(mvc.perform(post("/api/v1/investigations").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                .content("{\"network\":\"tron\",\"address\":\"" + me.value() + "\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        await(ANALYST, id);
        for (String p : List.of("", "/report")) {
            mvc.perform(get("/api/v1/investigations/" + id + p).header("X-API-Key", keyC)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/investigations/" + id + "/rerun").header("X-API-Key", keyC)).andExpect(status().isNotFound());
        String list = mvc.perform(get("/api/v1/investigations").header("X-API-Key", keyC)).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(list, "$[*].id")).doesNotContain(id);
        mvc.perform(post("/api/v1/investigations").header("X-API-Key", READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"network\":\"tron\",\"address\":\"" + me.value() + "\"}")).andExpect(status().isForbidden());
    }
}
