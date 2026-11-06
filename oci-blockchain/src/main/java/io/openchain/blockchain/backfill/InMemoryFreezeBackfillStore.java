package io.openchain.blockchain.backfill;

import io.openchain.core.model.TokenFreeze;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-lifetime store (in-memory persistence mode). */
public final class InMemoryFreezeBackfillStore implements FreezeBackfillStore {
    private final Map<String, Progress> progress = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TokenFreeze>> freezes = new ConcurrentHashMap<>();

    @Override public Optional<Progress> load(String id) { return Optional.ofNullable(progress.get(id)); }

    @Override public synchronized void saveChunk(Progress p, List<TokenFreeze> found) {
        Map<String, TokenFreeze> m = freezes.computeIfAbsent(p.id(), k -> new LinkedHashMap<>());
        for (TokenFreeze z : found) m.putIfAbsent(z.eventId(), z);
        progress.put(p.id(), p);
    }

    @Override public synchronized List<TokenFreeze> freezes(String id) {
        List<TokenFreeze> out = new ArrayList<>(freezes.getOrDefault(id, Map.of()).values());
        out.sort(FreezeBackfill.ORDER);
        return out;
    }
}
