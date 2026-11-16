package io.openchain.app.chain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openchain.app.ApiTestSupport;
import org.junit.jupiter.api.Test;

/** Step 3 diagnostic endpoint: admin only, read-only report of orphan profile keys and instance sharing. */
class ProfileKeyDiagnosticTest extends ApiTestSupport {
    @Test void adminOnlyReport() throws Exception {
        mvc.perform(get("/api/v1/admin/working-set/profile-keys")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/working-set/profile-keys").header("X-API-Key", READER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/working-set/profile-keys").header("X-API-Key", ANALYST)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/working-set/profile-keys").header("X-API-Key", ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.orphanKeys.profiles").isNumber())
                .andExpect(jsonPath("$.orphanKeys.orphanFlowKeysByProfile.length()").value(7))
                .andExpect(jsonPath("$.canonicalInstances.tokenCapacity").value(4096));
    }
}
