package io.openchain.app.watchlist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWatchlistRepository implements WatchlistRepository {
    private final Map<String, Entry> byId = new ConcurrentHashMap<>();

    @Override public synchronized boolean insert(Entry e) {
        boolean dup = byId.values().stream().anyMatch(x -> x.network().equals(e.network()) && x.address().equals(e.address()));
        if (dup) return false;
        byId.put(e.id(), e);
        return true;
    }

    @Override public boolean delete(String id) { return byId.remove(id) != null; }

    @Override public List<Entry> findAll() {
        List<Entry> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparing(Entry::createdAt).thenComparing(Entry::id));
        return all;
    }
}
