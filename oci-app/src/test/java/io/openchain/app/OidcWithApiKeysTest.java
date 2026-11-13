package io.openchain.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Code review I33 (2026-11-13): with OIDC enabled, an API key must still be accepted in both documented forms
 * ({@code X-API-Key} and {@code Authorization: Bearer oci_…}). The issuer is unreachable on purpose: an API key must
 * never be sent to the JWT decoder.
 */
@TestPropertySource(properties = "oci.security.oidc.issuer-uri=http://127.0.0.1:9/unreachable-issuer")
class OidcWithApiKeysTest extends ApiTestSupport {

    @Test void apiKeyHeaderIsAcceptedWhenOidcIsEnabled() throws Exception {
        mvc.perform(get("/api/v1/networks").header("X-API-Key", READER)).andExpect(status().isOk());
    }

    @Test void apiKeyAsBearerIsAcceptedWhenOidcIsEnabled() throws Exception {
        mvc.perform(get("/api/v1/networks").header("Authorization", "Bearer " + READER)).andExpect(status().isOk());
    }

    @Test void unknownApiKeyAsBearerIsRefusedWithoutReachingTheIssuer() throws Exception {
        mvc.perform(get("/api/v1/networks").header("Authorization", "Bearer oci_test_unknown1_NotAKeyNotAKeyNotAKeyNotAKey0000001"))
                .andExpect(status().isUnauthorized());
    }

    @Test void noCredentialIsRefused() throws Exception {
        mvc.perform(get("/api/v1/networks")).andExpect(status().isUnauthorized());
    }
}
