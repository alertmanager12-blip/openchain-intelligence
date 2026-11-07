package io.openchain.app.history;

import io.openchain.core.event.ChainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link AddressHistoryStore} (no persistence mode): lost at restart, like the rest of the chain state. */
public final class InMemoryAddressHistoryStore implements AddressHistoryStore {

    static final Comparator<ChainEvent> ORDER = Comparator.comparing((ChainEvent e) -> e.eventTime() == null ? Instant.EPOCH : e.eventTime())
            .thenComparing(ChainEvent::eventId);

    private final Map<String, Progress> progress = new LinkedHashMap<>();
    private final Map<String, Map<String, ChainEvent>> events = new LinkedHashMap<>();

    @Override public synchronized Optional<Progress> load(String id) { return Optional.ofNullable(progress.get(id)); }

    @Override public synchronized List<Progress> all() { return List.copyOf(progress.values()); }

    @Override public synchronized void save(Progress p) { progress.put(p.id(), p); }

    @Override public synchronized void saveChunk(Progress p, List<ChainEvent> chunk) {
        Map<String, ChainEvent> m = events.computeIfAbsent(p.id(), k -> new LinkedHashMap<>());
        for (ChainEvent e : chunk) m.putIfAbsent(e.eventId(), e);
        progress.put(p.id(), p);
    }

    @Override public synchronized List<ChainEvent> events(String id) {
        List<ChainEvent> out = new ArrayList<>(events.getOrDefault(id, Map.of()).values());
        out.sort(ORDER);
        return out;
    }
}
