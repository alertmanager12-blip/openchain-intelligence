package io.openchain.app.security;

import io.openchain.app.api.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticates {@code X-API-Key: oci_...} or {@code Authorization: Bearer oci_...}. Other bearer tokens are left
 * to the JWT resource server (when OIDC is configured). Invalid, expired or revoked keys get a 401 problem.
 * Credentials in the URL query string ({@code api_key}, {@code apiKey}, {@code key}, {@code access_token}) are rejected with 400:
 * URLs end up in logs, proxies and browser history. There is no unauthenticated bypass, in demo mode included.
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    private static final Set<String> FORBIDDEN_PARAMS = Set.of("api_key", "apikey", "api-key", "key", "access_token");

    /** Authentication of an API key. */
    public static final class ApiKeyAuthentication extends AbstractAuthenticationToken {
        private final String principal;
        private final String keyId;

        public ApiKeyAuthentication(String principal, String keyId, Collection<String> scopes) {
            super(Scopes.authorities(scopes));
            this.principal = principal;
            this.keyId = keyId;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return null; }
        @Override public Object getPrincipal() { return principal; }
        @Override public String getName() { return principal; }
        public String keyId() { return keyId; }
    }

    private final ApiKeyService keys;
    private final JsonMapper json;
    private final boolean oidcEnabled;

    public ApiKeyAuthenticationFilter(ApiKeyService keys, JsonMapper json, boolean oidcEnabled) {
        this.keys = keys;
        this.json = json;
        this.oidcEnabled = oidcEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (credentialInQuery(req.getQueryString())) {
            Problems.write(req, res, json, HttpStatus.BAD_REQUEST, "bad-request", "Bad request",
                    "credentials must not be sent in the URL; use the X-API-Key or Authorization header");
            return;
        }
        String apiKey = req.getHeader(API_KEY_HEADER);
        String auth = req.getHeader("Authorization");
        String bearer = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : null;
        String presented = apiKey != null ? apiKey.trim() : bearer != null && bearer.startsWith("oci_") ? bearer : null;

        if (presented != null) {
            Optional<ApiKeyService.KeyPrincipal> p = keys.verify(presented);
            if (p.isEmpty()) {
                Problems.write(req, res, json, HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required",
                        "invalid, expired or revoked API key");
                return;
            }
            ApiKeyService.KeyPrincipal k = p.get();
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new ApiKeyAuthentication("apikey:" + k.name() + ":" + k.prefix(), k.keyId(), k.scopes()));
            SecurityContextHolder.setContext(ctx);
        } else if (bearer != null && !oidcEnabled) {
            Problems.write(req, res, json, HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required",
                    "bearer tokens other than API keys require OIDC, which is not configured");
            return;
        }
        chain.doFilter(req, res);
    }

    static boolean credentialInQuery(String query) {
        if (query == null || query.isEmpty()) return false;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            try {
                name = URLDecoder.decode(name, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return true; // undecodable parameter names are suspicious enough
            }
            if (FORBIDDEN_PARAMS.contains(name.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
