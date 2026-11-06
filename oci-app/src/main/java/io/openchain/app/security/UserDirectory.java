package io.openchain.app.security;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Directory of console users and their OCI role. Humans authenticate with the IdP (ADR-0010); this only holds role
 * assignments ({@link UserRepository}: in memory or the {@code users} table). Demo mode seeds fictional users.
 * TODO(persistence): sync users from OIDC logins.
 */
@Service
public class UserDirectory {

    public static final List<String> ROLES = List.of("VIEWER", "ANALYST", "RULE_AUTHOR", "ADMIN");

    public record User(String id, String email, String name, String role, Instant lastLoginAt) {}

    private final UserRepository repository;

    public UserDirectory(UserRepository repository) { this.repository = repository; }

    public void put(User u) { repository.save(u); }

    /** Inserts the user unless one with the same id exists (keeps role changes made by an administrator). */
    public boolean putIfAbsent(User u) { return repository.insertIfAbsent(u); }

    public List<User> list() { return repository.findAll().stream().sorted(Comparator.comparing(User::id)).toList(); }

    public Optional<User> find(String id) { return repository.findById(id); }

    public synchronized User setRole(String id, String role) {
        User u = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("unknown user " + id));
        User updated = new User(u.id(), u.email(), u.name(), role, u.lastLoginAt());
        repository.save(updated);
        return updated;
    }
}
