package io.openchain.blockchain.indexer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCheckpointStore implements CheckpointStore {
    private final Map<String, Checkpoint> data = new ConcurrentHashMap<>();
    @Override public Optional<Checkpoint> load(String indexerId) { return Optional.ofNullable(data.get(indexerId)); }
    @Override public void save(String indexerId, Checkpoint checkpoint) { data.put(indexerId, checkpoint); }
}
