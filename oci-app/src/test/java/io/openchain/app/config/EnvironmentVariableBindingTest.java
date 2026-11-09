package io.openchain.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** The environment variable names documented in installation.md really bind (Spring relaxed binding). */
class EnvironmentVariableBindingTest {

    static OciProperties bind(Map<String, Object> env) {
        StandardEnvironment e = new StandardEnvironment();
        // replace the real environment: Spring Boot applies its environment-variable mapping to the source with this name
        e.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        ConfigurationPropertySources.attach(e);
        return Binder.get(e).bind("oci", OciProperties.class).orElseGet(OciProperties::new);
    }

    @Test void documentedChainAndIngestionVariablesBind() {
        OciProperties p = bind(Map.of(
                "OCI_CHAINS_TRON_ENABLED", "true",
                "OCI_CHAINS_TRON_API_KEY_HEADER", "TRON-PRO-API-KEY",
                "OCI_CHAINS_ETHEREUM_FALLBACK_URLS", "https://a.example,https://b.example",
                "OCI_CHAINS_ETHEREUM_REAL_BALANCE_MAX_LAG", "64",
                "OCI_CHAINS_ETHEREUM_MAX_BLOCKS_PER_POLL", "4",
                "OCI_INGESTION_AGE_WARMUP", "7d"));
        assertThat(p.getChains().get("tron").getApiKeyHeader()).isEqualTo("TRON-PRO-API-KEY");
        assertThat(p.getChains().get("ethereum").getFallbackUrls()).containsExactly("https://a.example", "https://b.example");
        assertThat(p.getChains().get("ethereum").getRealBalanceMaxLag()).isEqualTo(64);
        assertThat(p.getChains().get("ethereum").getMaxBlocksPerPoll()).isEqualTo(4);
        assertThat(p.getIngestion().getAgeWarmup()).isEqualTo(Duration.ofDays(7));
    }
}
