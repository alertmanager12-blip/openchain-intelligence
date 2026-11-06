package io.openchain.app;

import io.openchain.app.config.OciProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * OpenChain Intelligence: modular monolith (ADR-0001) wiring oci-blockchain -> oci-intelligence -> oci-alerting
 * behind the REST API v1. Humans authenticate with OIDC, machines with API keys (ADR-0010); there is no user store.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(OciProperties.class)
public class OciApplication {

    public static void main(String[] args) {
        SpringApplication.run(OciApplication.class, args);
    }
}
