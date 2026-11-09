package io.openchain.app.watchlist;

import java.time.Instant;
import java.util.List;

/** Watchlist storage ({@link InMemoryWatchlistRepository}, {@link JdbcWatchlistRepository}). */
public interface WatchlistRepository {

    /** One watched wallet; {@code address} is canonical for its network. */
    record Entry(String id, String network, String address, String name, String createdBy, Instant createdAt) {}

    /** @return false when (network, address) is already watched */
    boolean insert(Entry e);

    boolean delete(String id);

    /** Oldest first. */
    List<Entry> findAll();
}
