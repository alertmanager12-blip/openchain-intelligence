package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.ClientScopeDirectory;
import io.openchain.app.security.Scopes;
import io.openchain.app.summary.SummaryWriter;
import io.openchain.app.trace.InvestigationSummaryService;
import io.openchain.app.trace.InvestigationSummaryStore;
import io.openchain.app.trace.TraceArtifactStore;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.investigation.SummaryCheck;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/**
 * ADR-0022 amendment 5 through the API, with stub providers (no network, no paid call): only an answer that passes the
 * check is shown; refusals and failures are stored with their reasons; the provider list never exposes keys.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(InvestigationSummaryApiTest.Providers.class)
class InvestigationSummaryApiTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-17T05:00:00Z");
    static int seq;
    /** What each stub provider received, and what it answers (a function of the user content it got). */
    static final List<String> received = new CopyOnWriteArrayList<>();
    static volatile Function<String, SummaryWriter.Draft> honest, inventive;

    @TestConfiguration
    static class Providers {
        @Bean @Primary
        InvestigationSummaryService testSummaryService(TraceArtifactStore artifacts, InvestigationSummaryStore store, Clock clock) {
            SummaryWriter h = (system, user) -> { received.add(user); return honest.apply(user); };
            SummaryWriter i = (system, user) -> { received.add(user); return inventive.apply(user); };
            return new InvestigationSummaryService(artifacts, store, List.of(
                    new InvestigationSummaryService.Provider("honest", "Honest stub", "claude", "stub-1", "this test", h),
                    new InvestigationSummaryService.Provider("inventive", "Inventive stub", "openai-compatible", "stub-2", "this test", i)),
                    true, 6, clock);
        }
    }

    @Autowired IntelligencePipeline intel;
    @Autowired ClientScopeDirectory scopes;
    @Autowired ApiKeyService keys;

    static Address tron(String tag) { return new Address(Network.TRON, ("TSum" + tag + "000000000000000000000000000000000").substring(0, 34)); }

    void flow(Address from, Address to, String amount, int minute) {
        String tx = "%064x".formatted(0xA10_0000L + (++seq));
        intel.flows().add(ValueFlow.of(new TokenTransfer(Network.TRON, tx, 0, 11_000 + seq, "0xsum" + seq, T0.plusSeconds(60L * minute), from, to,
                USDT, new BigDecimal(amount).movePointRight(6).toBigIntegerExact())));
    }

    String body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String investigate(Address me) throws Exception {
        String id = JsonPath.read(body(mvc.perform(post("/api/v1/investigations").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                .content("{\"network\":\"tron\",\"address\":\"" + me.value() + "\"}")).andExpect(status().isAccepted())), "$.id");
        for (int i = 0; i < 100; i++) {
            String d = body(mvc.perform(get("/api/v1/investigations/" + id).header("X-API-Key", ANALYST)));
            if ("DONE".equals(JsonPath.read(d, "$.status"))) return id;
            Thread.sleep(50);
        }
        throw new AssertionError("investigation did not finish");
    }

    org.springframework.test.web.servlet.ResultActions ask(String key, String id, String provider) throws Exception {
        return mvc.perform(post("/api/v1/investigations/" + id + "/summaries").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"" + provider + "\"}"));
    }

    /** The id of the first trace attribution, read from the investigation. */
    String traceId(String id) throws Exception {
        List<String> ids = JsonPath.read(body(mvc.perform(get("/api/v1/investigations/" + id).header("X-API-Key", ANALYST))),
                "$.assertions[?(@.type == 'TRACE_ATTRIBUTION')].id");
        return ids.getFirst();
    }

    @Test void anAnswerWithinTheAssertionsIsShownAndAnInventedOneNever() throws Exception {
        Address me = tron("subject"), x = tron("next");
        flow(tron("src"), me, "800", 1);
        flow(me, x, "600", 2);
        String id = investigate(me);
        String tr = traceId(id);
        honest = u -> new SummaryWriter.Draft(List.of(new SummaryCheck.Sentence("Value sent may have reached " + x.value() + ".", List.of(tr))), "stub-1", null);
        inventive = u -> new SummaryWriter.Draft(List.of(new SummaryCheck.Sentence("The subject is owned by " + x.value() + " and laundered 9999 USDT.",
                List.of(tr))), "stub-2", null);

        String ok = body(ask(ANALYST, id, "honest").andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(ok, "$.status")).isEqualTo("ACCEPTED");
        assertThat((String) JsonPath.read(ok, "$.provider")).isEqualTo("honest");
        assertThat((String) JsonPath.read(ok, "$.model")).isEqualTo("stub-1");
        assertThat((String) JsonPath.read(ok, "$.promptVersion")).isEqualTo("summary@1");
        List<String> texts = JsonPath.read(ok, "$.sentences[*].text");
        assertThat(texts).anyMatch(t -> t.contains(x.value()));
        assertThat(texts.getFirst()).as("partial coverage stated first").startsWith("Data covered:");

        String bad = body(ask(ANALYST, id, "inventive").andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(bad, "$.status")).isEqualTo("REFUSED");
        assertThat((Object) JsonPath.read(bad, "$.sentences")).as("nothing written by the model is returned").isNull();
        assertThat(bad).doesNotContain("laundered");
        assertThat((List<String>) JsonPath.read(bad, "$.reasons")).anyMatch(r -> r.contains("9999")).anyMatch(r -> r.contains("owned"));

        // only the assertions were sent, as data
        assertThat(received.getLast()).startsWith("Assertions of the investigation (JSON data):").contains(tr).doesNotContain("\"evidence\"");

        String list = body(mvc.perform(get("/api/v1/investigations/" + id + "/summaries").header("X-API-Key", READER_INV)).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(list, "$[*].status")).containsExactly("ACCEPTED", "REFUSED");
    }

    @Test void failuresUnknownProvidersPermissionsIsolationAndTheDailyCap() throws Exception {
        Address me = tron("failing");
        flow(me, tron("failOut"), "300", 1);
        String id = investigate(me);
        honest = u -> SummaryWriter.Draft.failed("stub-1", "provider error 503");
        String failed = body(ask(ANALYST, id, "honest").andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(failed, "$.status")).isEqualTo("FAILED");
        assertThat((List<String>) JsonPath.read(failed, "$.reasons")).containsExactly("provider error 503");

        ask(ANALYST, id, "nobody").andExpect(status().isBadRequest());
        ask(ANALYST, "no-such-id", "honest").andExpect(status().isNotFound());
        ask(READER_INV, id, "honest").andExpect(status().isForbidden()); // a paid call needs investigation:write

        String providers = body(mvc.perform(get("/api/v1/ai-summary/providers").header("X-API-Key", READER_INV)).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(providers, "$[*].id")).containsExactly("honest", "inventive");
        assertThat(providers).doesNotContainIgnoringCase("key").doesNotContain("baseUrl");

        scopes.register("client-s", "S");
        String keyS = keys.create("s", List.of(Scopes.INVESTIGATION_READ, Scopes.INVESTIGATION_WRITE), Duration.ofHours(1), "test", "client-s").plaintext();
        ask(keyS, id, "honest").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/investigations/" + id + "/summaries").header("X-API-Key", keyS)).andExpect(status().isNotFound());

        // the cap (6 per client and day, shared with the other test) answers 429 once reached
        int code = 201;
        for (int i = 0; i < 8 && code != 429; i++) code = ask(ANALYST, id, "honest").andReturn().getResponse().getStatus();
        assertThat(code).isEqualTo(429);
    }

    static final String READER_INV = "oci_test_invread1_TestInvReaderKeyOnlyForUnitTests00000001";
}
