package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SecurityApiTest extends ApiTestSupport {

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void noKeyIs401Problem() throws Exception {
        mvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/unauthenticated"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void invalidKeyIs401() throws Exception {
        mvc.perform(get("/api/v1/alerts").header("X-API-Key", "oci_test_admin001_WrongSecretWrongSecretWrongSecret00000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/unauthenticated"));
        mvc.perform(get("/api/v1/alerts").header("X-API-Key", "not-a-key")).andExpect(status().isUnauthorized());
        // a non-API-key bearer token without OIDC configured
        mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer eyJhbGciOiJub25lIn0.e30.")).andExpect(status().isUnauthorized());
    }

    @Test
    void validKeyViaHeaderOrBearer() throws Exception {
        mvc.perform(get("/api/v1/alerts").header("X-API-Key", READER)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer " + READER)).andExpect(status().isOk());
    }

    @Test
    void insufficientPermissionIs403() throws Exception {
        mvc.perform(post("/api/v1/alerts/any/acknowledge").header("X-API-Key", READER))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/forbidden"));
        mvc.perform(get("/api/v1/wallets/TXyz123/risk?network=tron").header("X-API-Key", READER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/api-keys").header("X-API-Key", ANALYST)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/webhooks").header("X-API-Key", ANALYST)).andExpect(status().isForbidden());
    }

    @Test
    void keyInQueryParamIsRejected() throws Exception {
        mvc.perform(get("/api/v1/alerts?api_key=" + READER)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/bad-request"));
        mvc.perform(get("/api/v1/alerts?apiKey=x").header("X-API-Key", READER)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alerts?key=x").header("X-API-Key", READER)).andExpect(status().isBadRequest());
    }

    @Test
    void createdKeyWorksSecretShownOnceAndRevokedKeyIs401() throws Exception {
        String body = mvc.perform(post("/api/v1/api-keys").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci\",\"scopes\":[\"alerts:read\"],\"expiresInDays\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").exists())
                .andExpect(jsonPath("$.prefix", containsString("oci_test_")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        String secret = JsonPath.read(body, "$.secret");
        assertThat(secret).matches("^oci_test_[A-Za-z0-9]{8}_[A-Za-z0-9]{43}$");

        mvc.perform(get("/api/v1/alerts").header("X-API-Key", secret)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/rules").header("X-API-Key", secret)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/api-keys").header("X-API-Key", ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(secret.substring(18)))))
                .andExpect(jsonPath("$[0].secret").doesNotExist());

        mvc.perform(post("/api/v1/api-keys/" + id + "/revoke").header("X-API-Key", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(get("/api/v1/alerts").header("X-API-Key", secret)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/api-keys/" + id).header("X-API-Key", ADMIN)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/api-keys/does-not-exist").header("X-API-Key", ADMIN)).andExpect(status().isNotFound());
    }

    @Test
    void keyCreationIsValidated() throws Exception {
        mvc.perform(post("/api/v1/api-keys").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"scopes\":[\"everything:please\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].pointer").value("/scopes"));
        mvc.perform(post("/api/v1/api-keys").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"scopes\":[\"alerts:read\"],\"expiresInDays\":5000}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void corsPreflightFromAngularDevServer() throws Exception {
        mvc.perform(options("/api/v1/alerts").header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "X-API-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
        mvc.perform(options("/api/v1/alerts").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalErrorsAreGeneric() throws Exception {
        mvc.perform(get("/api/v1/test-boom").header("X-API-Key", READER))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/internal-error"))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at io.openchain"))));
    }

    @Test
    void usersRequireKeysAdmin() throws Exception {
        mvc.perform(get("/api/v1/users").header("X-API-Key", ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").exists());
        mvc.perform(get("/api/v1/users").header("X-API-Key", ANALYST)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/u3")
                        .header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"GOD\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
