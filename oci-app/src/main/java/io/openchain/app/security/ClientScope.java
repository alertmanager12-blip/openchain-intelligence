package io.openchain.app.security;

import java.util.Collection;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Client scope of the caller (decision D1, docs/FUND_FLOW_TRACE_TENANT_ISOLATION.md § 2.1). It comes from the
 * authenticated credential only: an API key carries its scope, an OIDC token its claim. It is never read from a request
 * parameter or header, and it is passed explicitly to the services that need it (no implicit thread-local).
 */
public final class ClientScope {
    private ClientScope() {}

    /** Scope of every credential while the instance serves a single client (one instance per client by default). */
    public static final String DEFAULT = "default";

    /** An authentication that knows its client scope. */
    public interface Scoped {
        String clientScope();
    }

    /** @throws IllegalStateException when the authentication carries no scope (fail closed: never a default) */
    public static String of(Authentication a) {
        if (a instanceof Scoped s && s.clientScope() != null) return s.clientScope();
        throw new IllegalStateException("the authenticated caller has no client scope");
    }

    /**
     * Scope of an OIDC token. Single-client mode: {@link #DEFAULT}, whatever the token says. Multi-client mode: the
     * {@code claim} must name exactly one known, active scope, else the token is refused (401), never attached to the
     * default scope.
     */
    public static String ofJwt(Jwt jwt, boolean multiClient, String claim, ClientScopeDirectory directory) {
        if (!multiClient) return DEFAULT;
        Object v = jwt.getClaims().get(claim);
        if (v instanceof Collection<?> c && c.size() == 1) v = c.iterator().next();
        if (!(v instanceof String id) || id.isBlank()) throw refused("token has no single " + claim + " claim");
        if (!directory.isActive(id)) throw refused("unknown or disabled client scope");
        return id;
    }

    private static OAuth2AuthenticationException refused(String why) {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token", why, null));
    }
}
