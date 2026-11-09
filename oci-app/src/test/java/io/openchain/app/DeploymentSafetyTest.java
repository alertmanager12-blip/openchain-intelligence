package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Phase 0 deployment defaults: demo secrets never meet real chain data; API docs follow demo mode. */
class DeploymentSafetyTest extends ApiTestSupport {

    @Test void demoModeRefusesToStartWithARealChain() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(OciApplication.class, TestBeans.class).profiles("test")
                .run("--server.port=0", "--oci.chains.tron.enabled=true", "--oci.chains.tron.provider=trongrid",
                        "--oci.chains.tron.url=http://127.0.0.1:9").close())
                .hasStackTraceContaining("enabled while OCI_DEMO_MODE=true");
    }

    @Test void apiDocsAreServedInDemoMode() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test void noiseReportListsAlertVolumePerRule() throws Exception {
        mvc.perform(get("/api/v1/rules/noise?hours=24").header("X-API-Key", ADMIN))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].ruleId").exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].windowHours").value(24));
        mvc.perform(get("/api/v1/rules/noise?hours=0").header("X-API-Key", ADMIN)).andExpect(status().isBadRequest());
    }

    @Test void prometheusMetricsAreScrapableFromPrivateAddressesOnly() throws Exception {
        String body = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("oci_events_indexed_total", "oci_indexer_lag_blocks",
                "oci_deliveries_total", "oci_rules_enabled");
        mvc.perform(get("/actuator/prometheus").with(r -> { r.setRemoteAddr("8.8.8.8"); return r; }))
                .andExpect(status().isUnauthorized());
    }

    @Test void privateSourceAddresses() {
        org.assertj.core.api.Assertions.assertThat(io.openchain.app.security.SecurityConfiguration.privateSource("127.0.0.1")).isTrue();
        org.assertj.core.api.Assertions.assertThat(io.openchain.app.security.SecurityConfiguration.privateSource("172.18.0.5")).isTrue();
        org.assertj.core.api.Assertions.assertThat(io.openchain.app.security.SecurityConfiguration.privateSource("::1")).isTrue();
        org.assertj.core.api.Assertions.assertThat(io.openchain.app.security.SecurityConfiguration.privateSource("fd12::1")).isTrue();
        org.assertj.core.api.Assertions.assertThat(io.openchain.app.security.SecurityConfiguration.privateSource("8.8.8.8")).isFalse();
        org.assertj.core.api.Assertions.assertThat(io.openchain.app.security.SecurityConfiguration.privateSource("example.com")).isFalse();
    }
}
