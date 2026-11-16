package io.openchain.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

/** Decision D1: the client scope of an OIDC caller comes from a configurable claim, required in multi-client mode. */
class JwtClientScopeTest {
    static final String CLAIM = "oci_client_scope";

    final ClientScopeDirectory directory = ClientScopeDirectory.inMemory();

    static Jwt jwt(Map<String, Object> claims) {
        var b = Jwt.withTokenValue("t").header("alg", "RS256").subject("analyst@example.org")
                .issuedAt(Instant.parse("2026-11-16T00:00:00Z")).expiresAt(Instant.parse("2026-11-16T01:00:00Z"));
        claims.forEach(b::claim);
        return b.build();
    }

    @Test void singleClientModePutsEveryTokenInTheDefaultScope() {
        assertThat(ClientScope.ofJwt(jwt(Map.of("scope", "wallet:read")), false, CLAIM, directory)).isEqualTo(ClientScope.DEFAULT);
        // a claim naming another scope does not move a caller out of the only scope that exists
        assertThat(ClientScope.ofJwt(jwt(Map.of(CLAIM, "client-b")), false, CLAIM, directory)).isEqualTo(ClientScope.DEFAULT);
    }

    /** N8: multi-client mode, token without the claim: refused, never attached to the default scope. */
    @Test void multiClientModeRefusesATokenWithoutTheClaim() {
        assertThatThrownBy(() -> ClientScope.ofJwt(jwt(Map.of("scope", "wallet:read")), true, CLAIM, directory))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> ClientScope.ofJwt(jwt(Map.of(CLAIM, List.of("a", "b"))), true, CLAIM, directory))
                .as("one scope per token").isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test void multiClientModeRefusesAnUnknownOrDisabledScope() {
        assertThatThrownBy(() -> ClientScope.ofJwt(jwt(Map.of(CLAIM, "client-x")), true, CLAIM, directory))
                .isInstanceOf(OAuth2AuthenticationException.class);
        directory.register("client-b", "Client B");
        assertThat(ClientScope.ofJwt(jwt(Map.of(CLAIM, "client-b")), true, CLAIM, directory)).isEqualTo("client-b");
        directory.disable("client-b");
        assertThatThrownBy(() -> ClientScope.ofJwt(jwt(Map.of(CLAIM, "client-b")), true, CLAIM, directory))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test void anAuthenticationThatCarriesNoScopeIsNeverGivenOne() {
        var anonymous = new org.springframework.security.authentication.AnonymousAuthenticationToken("k", "anonymous",
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThatThrownBy(() -> ClientScope.of(anonymous)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClientScope.of(null)).isInstanceOf(IllegalStateException.class);
    }
}
