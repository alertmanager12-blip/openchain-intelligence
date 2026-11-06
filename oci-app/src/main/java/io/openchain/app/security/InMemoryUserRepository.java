package io.openchain.app.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryUserRepository implements UserRepository {
    private final Map<String, UserDirectory.User> users = new ConcurrentHashMap<>();

    @Override public void save(UserDirectory.User u) { users.put(u.id(), u); }
    @Override public boolean insertIfAbsent(UserDirectory.User u) { return users.putIfAbsent(u.id(), u) == null; }
    @Override public Optional<UserDirectory.User> findById(String id) { return Optional.ofNullable(users.get(id)); }
    @Override public List<UserDirectory.User> findAll() { return List.copyOf(users.values()); }
}
