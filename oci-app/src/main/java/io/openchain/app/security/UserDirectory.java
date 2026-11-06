package io.openchain.app.security;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory directory of console users and their OCI role. Humans authenticate with the IdP (ADR-0010); this only
 * holds role assignments. Demo mode seeds fictional users. TODO(persistence): users table synced from OIDC logins.
 */
@Service
public class UserDirectory {

    public static final List<String> ROLES = List.of("VIEWER", "ANALYST", "RULE_AUTHOR", "ADMIN");

    public record User(String id, String email, String name, String role, Instant lastLoginAt) {}

    private final Map<String, User> users = new ConcurrentHashMap<>();

    public void put(User u) { users.put(u.id(), u); }

    public List<User> list() { return users.values().stream().sorted(Comparator.comparing(User::id)).toList(); }

    public Optional<User> find(String id) { return Optional.ofNullable(users.get(id)); }

    public User setRole(String id, String role) {
        User u = users.get(id);
        if (u == null) throw new IllegalArgumentException("unknown user " + id);
        User updated = new User(u.id(), u.email(), u.name(), role, u.lastLoginAt());
        users.put(id, updated);
        return updated;
    }
}
