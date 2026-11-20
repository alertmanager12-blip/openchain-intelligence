package io.openchain.app.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.openchain.app.config.OciProperties;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.ClientScope;
import io.openchain.app.security.Scopes;
import io.openchain.app.security.UserDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
                            Instant expiresAt, boolean revoked, Instant revokedAt, String createdBy, String clientScope,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String secret) {}

    /** {@code expiresAt} (RFC 3339) or {@code expiresInDays}; default {@code oci.security.default-key-ttl}. */
    public record CreateKeyRequest(String name, List<String> scopes, String expiresAt, Integer expiresInDays) {}

    /** Keys of the caller's client scope only (decision D1): an administrator never sees another client's keys. */
    @GetMapping("/api-keys")
    public List<ApiKeyDto> listKeys(Authentication auth) {
        return keys.list(ClientScope.of(auth)).stream().map(k -> dto(k, null)).toList();
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyDto createKey(@RequestBody CreateKeyRequest req, Authentication auth) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty()) errors.add(FE("/name", ApiCode.FIELD_REQUIRED, Map.of("field", "name"), "name is required (max 100 chars)"));
        else if (name.length() > 100) errors.add(FE("/name", ApiCode.FIELD_TOO_LONG, Map.of("field", "name", "max", 100), "name is required (max 100 chars)"));
        Set<String> scopes = Set.of();
        if (req.scopes() == null || req.scopes().isEmpty()) {
            errors.add(FE("/scopes", ApiCode.FIELD_REQUIRED, Map.of("field", "scopes"), "at least one permission is required"));
        } else {
            try {
                scopes = Scopes.normalise(req.scopes());
            } catch (IllegalArgumentException e) {
                errors.add(FE("/scopes", ApiCode.FIELD_UNKNOWN_PERMISSION, Map.of("reason", String.valueOf(e.getMessage()))));
            }
        }
        // a key can never hold more than its creator (ADR-0010: scopes intersected with the creator's role)
        Set<String> mine = Scopes.of(auth.getAuthorities());
        for (String s : scopes) {
            if (!mine.contains(s)) errors.add(FE("/scopes", ApiCode.FIELD_EXCEEDS_CREATOR, Map.of("permission", s)));
        }
        Duration ttl = props.getSecurity().getDefaultKeyTtl();
        Instant now = Instant.now();
        if (req.expiresAt() != null && !req.expiresAt().isBlank()) {
            Instant at = InvestigationController.instant(req.expiresAt(), "expiresAt");
            ttl = Duration.between(now, at);
        } else if (req.expiresInDays() != null) {
            ttl = Duration.ofDays(req.expiresInDays());
        }
        if (ttl.isNegative() || ttl.isZero()) errors.add(FE("/expiresAt", ApiCode.FIELD_IN_PAST, Map.of()));
        else if (ttl.compareTo(props.getSecurity().getMaxKeyTtl()) > 0)
            errors.add(FE("/expiresAt", ApiCode.FIELD_MAX_EXCEEDED, Map.of("days", props.getSecurity().getMaxKeyTtl().toDays())));
        if (!errors.isEmpty()) throw ApiException.validation(ApiCode.KEY_REQUEST_INVALID, errors);
        ApiKeyService.Created c = keys.create(name, scopes, ttl, auth.getName(), ClientScope.of(auth));
        return dto(c.key(), c.plaintext());
    }

    @PostMapping("/api-keys/{id}/revoke")
    public ApiKeyDto revoke(@PathVariable String id, Authentication auth) {
        // another client's key gets the same answer as a key that does not exist (no disclosure)
        keys.find(id, ClientScope.of(auth)).orElseThrow(() -> ApiException.notFound(ApiCode.KEY_NOT_FOUND, Map.of("id", id)));
        return dto(keys.revoke(id, auth.getName()), null);
    }

    /** Alias of {@code POST /api-keys/{id}/revoke}. Keys are revoked, never physically deleted (audit trail). */
    @DeleteMapping("/api-keys/{id}")
    public ApiKeyDto revokeDelete(@PathVariable String id, Authentication auth) { return revoke(id, auth); }

    private static ApiKeyDto dto(ApiKeyService.StoredKey k, String secret) {
        return new ApiKeyDto(k.id(), k.name(), k.displayPrefix(), List.copyOf(k.scopes()), k.createdAt(), k.lastUsedAt(), k.expiresAt(),
                k.revoked(), k.revokedAt(), k.createdBy(), k.clientScope(), secret);
    }

    // ---- users --------------------------------------------------------------------------------------------------

    @GetMapping("/users")
    public List<UserDirectory.User> users() { return users.list(); }

    public record RoleRequest(String role) {}

    @PatchMapping("/users/{id}")
    public UserDirectory.User updateUser(@PathVariable String id, @RequestBody RoleRequest req, Authentication auth) {
        users.find(id).orElseThrow(() -> ApiException.notFound(ApiCode.USER_NOT_FOUND, Map.of("id", id)));
        String role = req.role() == null ? "" : req.role().trim().toUpperCase(Locale.ROOT);
        if (!UserDirectory.ROLES.contains(role)) {
            throw ApiException.validation(ApiCode.USER_UNKNOWN_ROLE, List.of(FE("/role", ApiCode.FIELD_INVALID_ROLE,
                    Map.of("roles", String.valueOf(UserDirectory.ROLES)))));
        }
        UserDirectory.User u = users.setRole(id, role);
        ApiKeyService.AUDIT.info("user.role_change id={} role={} actor={}", id, role, auth.getName());
        return u;
    }

    private static ApiException.FieldError FE(String pointer, ApiCode code, java.util.Map<String, Object> params) {
        return ApiException.FieldError.of(pointer, code, params);
    }

    private static ApiException.FieldError FE(String pointer, ApiCode code, java.util.Map<String, Object> params, String message) {
        return ApiException.FieldError.of(pointer, code, params, message);
    }
}
