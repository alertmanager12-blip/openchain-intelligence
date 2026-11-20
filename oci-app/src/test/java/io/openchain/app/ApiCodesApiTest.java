package io.openchain.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Every problem+json carries a stable code and its params next to the unchanged English detail. */
class ApiCodesApiTest extends ApiTestSupport {

    @Test
    void validationOnDisabledNetworkCarriesCodeAndParams() throws Exception {
        mvc.perform(get("/api/v1/wallets/TXyz/analysis?network=bsc").header("X-API-Key", READER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("network.not_enabled"))
                .andExpect(jsonPath("$.params.network").value("bsc"))
                .andExpect(jsonPath("$.detail").value("network 'bsc' is not enabled on this instance"))
                .andExpect(jsonPath("$.errors[0].code").value("field.unsupported_network"))
                .andExpect(jsonPath("$.errors[0].params").isMap());
    }

    @Test
    void badRequestAndNotFound() throws Exception {
        mvc.perform(get("/api/v1/wallets/TXyz/analysis?network=nope").header("X-API-Key", READER))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("network.unknown"))
                .andExpect(jsonPath("$.params.network").value("nope"));
        mvc.perform(get("/api/v1/alerts/does-not-exist").header("X-API-Key", READER))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("alert.not_found"))
                .andExpect(jsonPath("$.params.id").value("does-not-exist"));
    }

    @Test
    void missingKeyNameIsAFieldError() throws Exception {
        mvc.perform(post("/api/v1/api-keys").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"alerts:read\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("key.request_invalid"))
                .andExpect(jsonPath("$.errors[0].code").value("field.required"))
                .andExpect(jsonPath("$.errors[0].params.field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("name is required (max 100 chars)"));
    }

    @Test
    void authenticationAndUnknownRoute() throws Exception {
        mvc.perform(get("/api/v1/alerts")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.missing_credentials")).andExpect(jsonPath("$.params").isMap());
        mvc.perform(get("/api/v1/nothing-here").header("X-API-Key", READER)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("http.no_endpoint")).andExpect(jsonPath("$.params.method").value("GET"));
    }
}
