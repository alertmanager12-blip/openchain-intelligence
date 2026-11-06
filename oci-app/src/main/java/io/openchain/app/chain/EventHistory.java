package io.openchain.app.chain;

import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Network;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded in-memory record of the (enriched) events fed to the rule engine: gives alerts their context and is the
 * historical data replayed by rule dry runs. Oldest events are evicted first.
 * TODO(persistence): replace with a PostgreSQL/ClickHouse event table (ADR-0004).
 */
public final class EventHistory {

    private final int capacity;
    private final LinkedHashMap<String, ChainEvent> events;

    public EventHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.events = new LinkedHashMap<>(1024, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, ChainEvent> eldest) {
                return size() > EventHistory.this.capacity;
            }
        };
    }

    public synchronized void record(ChainEvent e) {
        if (e.removed()) {
            events.remove(e.eventId());
        } else {
            events.put(e.eventId(), e);
        }
    }

    public synchronized Optional<ChainEvent> find(String eventId) { return Optional.ofNullable(events.get(eventId)); }

    /** Events in [from, to] (event time), optionally restricted to one network, oldest first, at most {@code max}. */
    public synchronized List<ChainEvent> range(Instant from, Instant to, Network network, int max) {
        List<ChainEvent> out = new ArrayList<>();
        for (ChainEvent e : events.values()) {
            if (e.eventTime() == null) continue;
            if (from != null && e.eventTime().isBefore(from)) continue;
            if (to != null && e.eventTime().isAfter(to)) continue;
            if (network != null && e.network() != network) continue;
            out.add(e);
        }
        out.sort((a, b) -> a.eventTime().compareTo(b.eventTime()));
        return out.size() > max ? List.copyOf(out.subList(out.size() - max, out.size())) : out;
    }

    public synchronized int size() { return events.size(); }
}
