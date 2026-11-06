package io.openchain.app.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.openchain.app.config.OciProperties;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.Scopes;
import io.openchain.app.security.UserDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API key and user administration (permission keys:admin). Secrets are returned once, at creation. */
@RestController
@RequestMapping("/api/v1")
public class AdminController {

    private final ApiKeyService keys;
    private final UserDirectory users;
    private final OciProperties props;

    public AdminController(ApiKeyService keys, UserDirectory users, OciProperties props) {
        this.keys = keys;
        this.users = users;
        this.props = props;
    }

    public record ApiKeyDto(String id, String name, String prefix, List<String> scopes, Instant createdAt, Instant lastUsedAt,
                            Instant expiresAt, boolean revoked, Instant revokedAt, String createdBy,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String secret) {}

    /** {@code expiresAt} (RFC 3339) or {@code expiresInDays}; default {@code oci.security.default-key-ttl}. */
    public record CreateKeyRequest(String name, List<String> scopes, String expiresAt, Integer expiresInDays) {}

    @GetMapping("/api-keys")
    public List<ApiKeyDto> listKeys() { return keys.list().stream().map(k -> dto(k, null)).toList(); }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyDto createKey(@RequestBody CreateKeyRequest req, Authentication auth) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty() || name.length() > 100) errors.add(new ApiException.FieldError("/name", "required", "name is required (max 100 chars)"));
        Set<String> scopes = Set.of();
        if (req.scopes() == null || req.scopes().isEmpty()) {
            errors.add(new ApiException.FieldError("/scopes", "required", "at least one permission is required"));
        } else {
            try {
                scopes = Scopes.normalise(req.scopes());
            } catch (IllegalArgumentException e) {
                errors.add(new ApiException.FieldError("/scopes", "unknown_permission", e.getMessage()));
            }
        }
        // a key can never hold more than its creator (ADR-0010: scopes intersected with the creator's role)
        Set<String> mine = Scopes.of(auth.getAuthorities());
        for (String s : scopes) {
            if (!mine.contains(s)) errors.add(new ApiException.FieldError("/scopes", "exceeds_creator", "you do not hold " + s));
        }
        Duration ttl = props.getSecurity().getDefaultKeyTtl();
        Instant now = Instant.now();
        if (req.expiresAt() != null && !req.expiresAt().isBlank()) {
            Instant at = InvestigationController.instant(req.expiresAt(), "expiresAt");
            ttl = Duration.between(now, at);
        } else if (req.expiresInDays() != null) {
            ttl = Duration.ofDays(req.expiresInDays());
        }
        if (ttl.isNegative() || ttl.isZero()) errors.add(new ApiException.FieldError("/expiresAt", "in_past", "expiry must be in the future"));
        else if (ttl.compareTo(props.getSecurity().getMaxKeyTtl()) > 0)
            errors.add(new ApiException.FieldError("/expiresAt", "max_exceeded", "expiry must be <= " + props.getSecurity().getMaxKeyTtl().toDays() + " days"));
        if (!errors.isEmpty()) throw ApiException.validation("API key request is invalid", errors);
        ApiKeyService.Created c = keys.create(name, scopes, ttl, auth.getName());
        return dto(c.key(), c.plaintext());
    }

    @PostMapping("/api-keys/{id}/revoke")
    public ApiKeyDto revoke(@PathVariable String id, Authentication auth) {
        keys.find(id).orElseThrow(() -> ApiException.notFound("API key " + id + " does not exist"));
        return dto(keys.revoke(id, auth.getName()), null);
    }

    /** Alias of {@code POST /api-keys/{id}/revoke}. Keys are revoked, never physically deleted (audit trail). */
    @DeleteMapping("/api-keys/{id}")
    public ApiKeyDto revokeDelete(@PathVariable String id, Authentication auth) { return revoke(id, auth); }

    private static ApiKeyDto dto(ApiKeyService.StoredKey k, String secret) {
        return new ApiKeyDto(k.id(), k.name(), k.displayPrefix(), List.copyOf(k.scopes()), k.createdAt(), k.lastUsedAt(), k.expiresAt(),
                k.revoked(), k.revokedAt(), k.createdBy(), secret);
    }

    // ---- users --------------------------------------------------------------------------------------------------

    @GetMapping("/users")
    public List<UserDirectory.User> users() { return users.list(); }

    public record RoleRequest(String role) {}

    @PatchMapping("/users/{id}")
    public UserDirectory.User updateUser(@PathVariable String id, @RequestBody RoleRequest req, Authentication auth) {
        users.find(id).orElseThrow(() -> ApiException.notFound("user " + id + " does not exist"));
        String role = req.role() == null ? "" : req.role().trim().toUpperCase(Locale.ROOT);
        if (!UserDirectory.ROLES.contains(role)) {
            throw ApiException.validation("unknown role", List.of(new ApiException.FieldError("/role", "invalid_value",
                    "role must be one of " + UserDirectory.ROLES)));
        }
        UserDirectory.User u = users.setRole(id, role);
        ApiKeyService.AUDIT.info("user.role_change id={} role={} actor={}", id, role, auth.getName());
        return u;
    }
}
