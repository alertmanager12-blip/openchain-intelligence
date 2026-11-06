package io.openchain.alerting.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded (LRU by insertion) + TTL map of event id -> state. Tracks retraction so that a reorged event that is
 * later re-included in the canonical chain (same deterministic id) is counted again. Not thread-safe (engine-confined).
 */
final class EventDedupCache {

    enum State { ACTIVE, RETRACTED }

    private record Stamp(State state, Instant at) {}

    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<String, Stamp> map;

    EventDedupCache(int capacity, Duration ttl, Clock clock) {
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = clock;
        this.map = new LinkedHashMap<>(Math.min(capacity, 1 << 16), 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Stamp> eldest) { return size() > EventDedupCache.this.capacity; }
        };
    }

    State get(String id) {
        Stamp e = map.get(id);
        if (e == null) return null;
        if (e.at().plus(ttl).isBefore(clock.instant())) { map.remove(id); return null; }
        return e.state();
    }

    void put(String id, State s) {
        map.remove(id); // refresh insertion order
        map.put(id, new Stamp(s, clock.instant()));
    }

    void purgeExpired() {
        Instant limit = clock.instant().minus(ttl);
        for (Iterator<Stamp> it = map.values().iterator(); it.hasNext(); ) {
            if (it.next().at().isBefore(limit)) it.remove(); else break; // insertion ordered
        }
    }

    int size() { return map.size(); }
}
