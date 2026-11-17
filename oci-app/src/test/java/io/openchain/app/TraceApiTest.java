package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Stored traces through the API (ADR-0022 amendment 1, step 3-2), and the negative tests N1-N12 of
 * docs/FUND_FLOW_TRACE_TENANT_ISOLATION.md § 3 (N8: JwtClientScopeTest; N10: TraceArtifactStoreTest).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // adds scopes, keys and flows
class TraceApiTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-16T08:00:00Z");

    @Autowired IntelligencePipeline intel;
    @Autowired ClientScopeDirectory scopes;
    @Autowired ApiKeyService keys;

    static String keyB;
    static int seq;

    static Address tron(String tag) { return new Address(Network.TRON, ("TTrace" + tag + "0000000000000000000000000000000").substring(0, 34)); }

    @BeforeEach synchronized void clientB() {
        if (keyB != null) return;
        scopes.register("client-b", "Client B");
        keyB = keys.create("analyst of B", List.of(Scopes.INVESTIGATION_READ, Scopes.INVESTIGATION_WRITE, Scopes.WALLET_READ),
                Duration.ofHours(1), "test", "client-b").plaintext();
    }

    void flow(Address from, Address to, String amount, int minute) {
        String tx = "%064x".formatted(0xB0_0000L + (++seq));
        intel.flows().add(ValueFlow.of(new TokenTransfer(Network.TRON, tx, 0, 4_000 + seq, "0xblock" + seq, T0.plusSeconds(60L * minute), from, to,
                USDT, new BigDecimal(amount).movePointRight(6).toBigIntegerExact())));
    }

    String create(String key, Address seed) throws Exception {
        String body = "{\"network\":\"tron\",\"seed\":\"" + seed.value() + "\",\"direction\":\"out\",\"depth\":3}";
        String r = mvc.perform(post("/api/v1/traces").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(r, "$.id");
    }

    String awaitDone(String key, String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            String r = mvc.perform(get("/api/v1/traces/" + id).header("X-API-Key", key)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String s = JsonPath.read(r, "$.status");
            if (s.equals("DONE") || s.equals("FAILED")) return r;
            Thread.sleep(50);
        }
        throw new AssertionError("trace " + id + " did not finish");
    }

    static String withoutTrace(String problem) { return problem.replaceAll("\"traceId\":\"[^\"]*\"", "").replaceAll("\"instance\":\"[^\"]*\"", ""); }

    String notFound(String key, String path) throws Exception {
        return withoutTrace(mvc.perform(get(path).header("X-API-Key", key)).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
    }

    @Test void aTraceIsQueuedRunStoredAndReRunAsANewVersion() throws Exception {
        Address a = tron("runA"), b = tron("runB"), c = tron("runC");
        flow(a, b, "100", 1); flow(b, c, "60", 2);
        String id = create(ANALYST, a);
        String done = awaitDone(ANALYST, id);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("DONE");
        assertThat((Integer) JsonPath.read(done, "$.frozenFlows")).isEqualTo(2);
        List<String> attributed = JsonPath.read(done, "$.result.attributions[*].address.value");
        assertThat(attributed).contains(b.value(), c.value());
        mvc.perform(get("/api/v1/traces/" + id + "/evidence").header("X-API-Key", ANALYST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.flows.length()").value(2));

        String v2 = JsonPath.read(mvc.perform(post("/api/v1/traces/" + id + "/rerun").header("X-API-Key", ANALYST))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.id");
        String d2 = awaitDone(ANALYST, v2);
        assertThat((Integer) JsonPath.read(d2, "$.version")).isEqualTo(2);
        assertThat((String) JsonPath.read(d2, "$.previousId")).isEqualTo(id);
        assertThat((List<?>) JsonPath.read(d2, "$.diff.evidence")).isEmpty(); // same data, no change

        mvc.perform(post("/api/v1/traces").header("X-API-Key", READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"network\":\"tron\",\"seed\":\"" + a.value() + "\",\"direction\":\"out\"}")).andExpect(status().isForbidden());
    }

    /** N1, N2, N3, N4, N7, N9, N11, N12: another client's trace does not exist for B, by any route. */
    @Test void anotherClientsTraceIsInvisibleByEveryRoute() throws Exception {
        Address a = tron("isoA"), b = tron("isoB");
        flow(a, b, "100", 1);
        String idA = create(ANALYST, a);
        String unknown = notFound(keyB, "/api/v1/traces/" + java.util.UUID.randomUUID()); // N7
        // N12: right after creation (pending or running); N1: whatever its status
        assertThat(notFound(keyB, "/api/v1/traces/" + idA).replace(idA, "")).isEqualTo(unknown.replaceAll("[0-9a-f-]{36}", ""));
        awaitDone(ANALYST, idA);
        for (String p : List.of("", "/evidence", "/export")) notFound(keyB, "/api/v1/traces/" + idA + p); // N1, N2, N11
        mvc.perform(post("/api/v1/traces/" + idA + "/cancel").header("X-API-Key", keyB)).andExpect(status().isNotFound()); // N3
        mvc.perform(post("/api/v1/traces/" + idA + "/rerun").header("X-API-Key", keyB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/traces/" + idA).header("X-API-Key", ANALYST)).andExpect(jsonPath("$.status").value("DONE"));
        String listB = mvc.perform(get("/api/v1/traces").header("X-API-Key", keyB)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(listB, "$[*].id")).doesNotContain(idA); // N4
        // N9: naming A's scope in the request changes nothing
        mvc.perform(get("/api/v1/traces/" + idA + "?clientScope=default").header("X-API-Key", keyB).header("X-Client-Scope", "default"))
                .andExpect(status().isNotFound());
    }

    /** N5: an administrator is limited to its own scope too. */
    @Test void anAdministratorCannotReadAnotherClientsTrace() throws Exception {
        Address a = tron("admA"), b = tron("admB");
        flow(a, b, "100", 1);
        String idB = create(keyB, a);
        awaitDone(keyB, idB);
        notFound(ADMIN, "/api/v1/traces/" + idB);
    }

    /** N6: a trace by A does not change what B sees about the same address. */
    @Test void aTraceByOneClientDoesNotChangeWhatAnotherSees() throws Exception {
        Address a = tron("n6A"), b = tron("n6B");
        flow(a, b, "100", 1);
        String before = mvc.perform(get("/api/v1/wallets/" + a.value() + "/exposure?network=tron").header("X-API-Key", keyB))
                .andReturn().getResponse().getContentAsString();
        awaitDone(ANALYST, create(ANALYST, a));
        String after = mvc.perform(get("/api/v1/wallets/" + a.value() + "/exposure?network=tron").header("X-API-Key", keyB))
                .andReturn().getResponse().getContentAsString();
        assertThat(after).isEqualTo(before);
    }
}
