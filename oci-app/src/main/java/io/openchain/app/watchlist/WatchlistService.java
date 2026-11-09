package io.openchain.app.watchlist;

import io.openchain.core.model.Address;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * The organisation's own wallets. Kept in an in-memory index (loaded from the repository at start) because every indexed
 * event asks {@link #isWatched}. Feeds the rule fields {@code wallet.watchlisted}, {@code counterparty.watchlisted} and
 * {@code watchlist.active}.
 */
@Service
public class WatchlistService implements io.openchain.app.chain.ChainIngestionService.Watchlist {
    private final WatchlistRepository repository;
    private final Clock clock;
    private final Set<Address> index = ConcurrentHashMap.newKeySet();

    public WatchlistService(WatchlistRepository repository, Clock clock, io.openchain.app.chain.ChainIngestionService chains) {
        this.repository = repository;
        this.clock = clock;
        for (WatchlistRepository.Entry e : repository.findAll()) index.add(address(e));
        chains.setWatchlist(this); // enrichment reads the in-memory index on every event
    }

    private static Address address(WatchlistRepository.Entry e) {
        return new Address(io.openchain.core.model.Network.fromId(e.network()), e.address());
    }

    @Override public boolean isWatched(Address a) { return a != null && index.contains(a); }

    /** True when at least one wallet is watched: behavioural pack rules then only look at watched wallets. */
    @Override public boolean active() { return !index.isEmpty(); }

    public List<WatchlistRepository.Entry> list() { return repository.findAll(); }

    /** Adds a canonical address; empty when it is already watched. */
    public synchronized Optional<WatchlistRepository.Entry> add(Address a, String name, String createdBy) {
        WatchlistRepository.Entry e = new WatchlistRepository.Entry("wl-" + UUID.randomUUID().toString().substring(0, 18),
                a.network().id(), a.value(), name == null || name.isBlank() ? null : name.strip(), createdBy, clock.instant());
        if (!repository.insert(e)) return Optional.empty();
        index.add(a);
        return Optional.of(e);
    }

    public synchronized boolean remove(String id) {
        Optional<WatchlistRepository.Entry> e = repository.findAll().stream().filter(x -> x.id().equals(id)).findFirst();
        if (e.isEmpty() || !repository.delete(id)) return false;
        index.remove(address(e.get()));
        return true;
    }
}
