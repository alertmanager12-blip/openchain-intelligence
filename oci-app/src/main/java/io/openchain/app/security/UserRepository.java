package io.openchain.app.security;

import java.util.List;
import java.util.Optional;

/** Console users and roles ({@link InMemoryUserRepository}, {@link JdbcUserRepository}). */
public interface UserRepository {
    /** Insert or update. */
    void save(UserDirectory.User user);
    /** @return true when inserted, false when a user with this id already exists */
    boolean insertIfAbsent(UserDirectory.User user);
    Optional<UserDirectory.User> findById(String id);
    List<UserDirectory.User> findAll();
}
