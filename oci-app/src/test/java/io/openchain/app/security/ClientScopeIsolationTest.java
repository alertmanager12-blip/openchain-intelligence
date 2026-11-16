package io.openchain.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.app.ApiTestSupport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Decision D1 (docs/FUND_FLOW_TRACE_DECISIONS.md, docs/FUND_FLOW_TRACE_TENANT_ISOLATION.md): every authenticated caller
 * belongs to a client scope, taken from its credential only, never from the request. Key administration is limited to the
 * caller's scope. The trace-specific negative tests (N1-N7, N10-N12) come with the trace API.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // registers scopes and keys: never leak them to other classes
class ClientScopeIsolationTest extends ApiTestSupport {

    @Autowired ClientScopeDirectory scopes;
    @Autowired ApiKeyService keys;

    String adminB, keyIdA;

    @BeforeEach void twoScopes() {
        scopes.register("client-b", "Client B");
        adminB = keys.create("admin of B", List.of(Scopes.KEYS_ADMIN, Scopes.WALLET_READ), Duration.ofHours(1), "test", "client-b").plaintext();
        keyIdA = keys.create("reader of default", List.of(Scopes.WALLET_READ), Duration.ofHours(1), "test").key().id();
    }

    @Test void aKeyCreatedThroughTheApiBelongsToItsCreatorsScope() throws Exception {
        mvc.perform(post("/api/v1/api-keys").header("X-API-Key", adminB).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"made by B\",\"scopes\":[\"wallet:read\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.clientScope").value("client-b"));
        mvc.perform(post("/api/v1/api-keys").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"made by default\",\"scopes\":[\"wallet:read\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.clientScope").value("default"));
    }

    @Test void anAdministratorOnlySeesTheKeysOfItsScope() throws Exception {
        String listB = mvc.perform(get("/api/v1/api-keys").header("X-API-Key", adminB)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> scopesSeen = JsonPath.read(listB, "$[*].clientScope");
        assertThat(scopesSeen).isNotEmpty().containsOnly("client-b");
        List<String> idsSeen = JsonPath.read(listB, "$[*].id");
        assertThat(idsSeen).doesNotContain(keyIdA);
    }

    /** N9: a scope named in the request (header or parameter) is ignored; the credential decides. */
    @Test void aScopeNamedInTheRequestIsIgnored() throws Exception {
        String listB = mvc.perform(get("/api/v1/api-keys?clientScope=default&scope=default").header("X-API-Key", adminB)
                        .header("X-Client-Scope", "default").header("X-Scope", "default"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> scopesSeen = JsonPath.read(listB, "$[*].clientScope");
        assertThat(scopesSeen).containsOnly("client-b");
    }

    /** Like N3: another scope's key cannot be revoked, and the answer does not reveal that it exists. */
    @Test void aKeyOfAnotherScopeCannotBeRevokedAndLooksAbsent() throws Exception {
        String unknown = mvc.perform(post("/api/v1/api-keys/does-not-exist/revoke").header("X-API-Key", adminB))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String other = mvc.perform(post("/api/v1/api-keys/" + keyIdA + "/revoke").header("X-API-Key", adminB))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(withoutTrace(other.replace(keyIdA, "ID"))).isEqualTo(withoutTrace(unknown.replace("does-not-exist", "ID")));
        assertThat(keys.find(keyIdA).orElseThrow().revoked()).isFalse();
    }

    static String withoutTrace(String problem) { return problem.replaceAll("\"traceId\":\"[^\"]*\"", ""); }

    @Test void aKeyOfADisabledOrUnknownScopeIsRefused() throws Exception {
        scopes.register("client-c", "Client C");
        String keyC = keys.create("reader of C", List.of(Scopes.WALLET_READ), Duration.ofHours(1), "test", "client-c").plaintext();
        mvc.perform(get("/api/v1/networks").header("X-API-Key", keyC)).andExpect(status().isOk());
        scopes.disable("client-c");
        mvc.perform(get("/api/v1/networks").header("X-API-Key", keyC)).andExpect(status().isUnauthorized());
    }

    @Test void aKeyCannotBeCreatedInAScopeThatDoesNotExist() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        keys.create("x", List.of(Scopes.WALLET_READ), Duration.ofHours(1), "test", "no-such-client"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
