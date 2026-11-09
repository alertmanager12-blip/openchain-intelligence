package io.openchain.app.security;

import static io.openchain.app.security.Scopes.authority;

import io.openchain.app.api.Problems;
import io.openchain.app.config.OciProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless security: API keys (always) and an OIDC JWT resource server only when
 * {@code oci.security.oidc.issuer-uri} is set (the decoder resolves the issuer lazily, so no IdP is contacted at
 * startup). Scopes from the docs/api table are enforced per endpoint below.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);
    private static final String API = "/api/v1";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyService keys, OciProperties props, JsonMapper json) throws Exception {
        OciProperties.Security sec = props.getSecurity();
        String issuer = sec.getOidc().getIssuerUri();
        boolean oidc = issuer != null && !issuer.isBlank();

        http.csrf(c -> c.disable()) // stateless API, no cookies
                .cors(c -> c.configurationSource(corsSource(sec.getCorsAllowedOrigins())))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .addFilterBefore(new ApiKeyAuthenticationFilter(keys, json, oidc), AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> Problems.write(req, res, json, HttpStatus.UNAUTHORIZED,
                                "unauthenticated", "Authentication required", "missing or invalid credentials"))
                        .accessDeniedHandler((req, res, ex) -> Problems.write(req, res, json, HttpStatus.FORBIDDEN,
                                "forbidden", "Forbidden", "missing permission for this operation")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Prometheus scrapes from the host or the compose network: no key, but never from a public address
                        .requestMatchers(r -> "/actuator/prometheus".equals(r.getRequestURI()) && privateSource(r.getRemoteAddr())).permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority(authority(Scopes.KEYS_ADMIN))
                        // wallets & transactions
                        .requestMatchers(HttpMethod.GET, API + "/wallets/*/analysis").hasAuthority(authority(Scopes.WALLET_READ))
                        .requestMatchers(HttpMethod.GET, API + "/wallets/*/transactions").hasAuthority(authority(Scopes.TRANSACTION_READ))
                        .requestMatchers(HttpMethod.GET, API + "/wallets/*/risk").hasAuthority(authority(Scopes.RISK_READ))
                        .requestMatchers(HttpMethod.GET, API + "/wallets/*/exposure").hasAuthority(authority(Scopes.INVESTIGATION_READ))
                        .requestMatchers(HttpMethod.GET, API + "/transactions/**").hasAuthority(authority(Scopes.TRANSACTION_READ))
                        .requestMatchers(HttpMethod.GET, API + "/graph/**").hasAuthority(authority(Scopes.INVESTIGATION_READ))
                        .requestMatchers(HttpMethod.GET, API + "/signals").hasAuthority(authority(Scopes.RISK_READ))
                        .requestMatchers(HttpMethod.GET, API + "/networks", API + "/dashboard").hasAuthority(authority(Scopes.WALLET_READ))
                        // demo evidence (demo mode only): wallets + alerts
                        .requestMatchers(HttpMethod.GET, API + "/demo/**").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAuthority(authority(Scopes.WALLET_READ)),
                                AuthorityAuthorizationManager.hasAuthority(authority(Scopes.ALERTS_READ))))
                        // labels
                        .requestMatchers(API + "/labels/reports").hasAuthority(authority(Scopes.LABELS_REPORT))
                        .requestMatchers(HttpMethod.GET, API + "/labels").hasAuthority(authority(Scopes.WALLET_READ))
                        // rules (validate is a read: compile only, no side effect)
                        .requestMatchers(HttpMethod.POST, API + "/rules/validate").hasAuthority(authority(Scopes.RULES_READ))
                        .requestMatchers(HttpMethod.GET, API + "/rules", API + "/rules/**").hasAuthority(authority(Scopes.RULES_READ))
                        .requestMatchers(API + "/rules", API + "/rules/**").hasAuthority(authority(Scopes.RULES_WRITE))
                        // alerts
                        .requestMatchers(HttpMethod.GET, API + "/alerts", API + "/alerts/**").hasAuthority(authority(Scopes.ALERTS_READ))
                        .requestMatchers(API + "/alerts/**").hasAuthority(authority(Scopes.ALERTS_WRITE))
                        // connectors
                        .requestMatchers(HttpMethod.GET, API + "/webhooks", API + "/webhooks/**").hasAuthority(authority(Scopes.WEBHOOKS_READ))
                        .requestMatchers(API + "/webhooks", API + "/webhooks/**").hasAuthority(authority(Scopes.WEBHOOKS_WRITE))
                        // administration
                        .requestMatchers(API + "/api-keys", API + "/api-keys/**", API + "/users", API + "/users/**", API + "/admin/**")
                        .hasAuthority(authority(Scopes.KEYS_ADMIN))
                        .anyRequest().authenticated());

        if (oidc) {
            log.info("OIDC resource server enabled for issuer {}", issuer);
            JwtDecoder decoder = jwtDecoder(issuer, sec.getOidc().getAudience());
            String rolesClaim = sec.getOidc().getRolesClaim();
            http.oauth2ResourceServer(o -> o
                    .authenticationEntryPoint((req, res, ex) -> Problems.write(req, res, json, HttpStatus.UNAUTHORIZED,
                            "unauthenticated", "Authentication required", "invalid bearer token"))
                    .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(jwt ->
                            new JwtAuthenticationToken(jwt, jwtAuthorities(jwt, rolesClaim), jwt.getSubject()))));
        }
        return http.build();
    }

    /** Lazy: the issuer's discovery document / JWKS is fetched on the first token, not at startup. */
    private static JwtDecoder jwtDecoder(String issuer, String audience) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder d = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            OAuth2TokenValidator<Jwt> base = JwtValidators.createDefaultWithIssuer(issuer);
            if (audience != null && !audience.isBlank()) {
                OAuth2TokenValidator<Jwt> aud = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience mismatch", null));
                d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(base, aud));
            } else {
                d.setJwtValidator(base);
            }
            return d;
        });
    }

    /** Standard {@code scope}/{@code scp} claims plus OCI roles mapped to scopes. */
    static Collection<GrantedAuthority> jwtAuthorities(Jwt jwt, String rolesClaim) {
        Set<String> scopes = new LinkedHashSet<>();
        for (GrantedAuthority a : new JwtGrantedAuthoritiesConverter().convert(jwt)) {
            String s = a.getAuthority().substring("SCOPE_".length());
            if (Scopes.ALL.contains(s)) scopes.add(s);
        }
        Object roles = jwt.getClaims().get(rolesClaim);
        List<String> roleList = new ArrayList<>();
        if (roles instanceof Collection<?> c) c.forEach(r -> roleList.add(String.valueOf(r)));
        else if (roles instanceof String s) roleList.addAll(List.of(s.split("[ ,]+")));
        for (String r : roleList) scopes.addAll(Scopes.forRole(r));
        return Scopes.authorities(scopes);
    }

    private static CorsConfigurationSource corsSource(List<String> origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "X-API-Key", "Content-Type", "Idempotency-Key", "If-Match", "Accept"));
        c.setExposedHeaders(List.of("ETag", "Location", "Retry-After", "Idempotent-Replayed"));
        c.setAllowCredentials(false);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    /** Loopback or private (RFC 1918 / unique-local / link-local) source address: the host itself or the compose network. */
    public static boolean privateSource(String remoteAddr) {
        // literal IPv4/IPv6 only (Servlet remote addresses are literals): getByName then never performs a DNS lookup
        if (remoteAddr == null || !remoteAddr.matches("[0-9A-Fa-f:.%]+")) return false;
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(remoteAddr);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || (a instanceof java.net.Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc);
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return false;
        }
    }
}
