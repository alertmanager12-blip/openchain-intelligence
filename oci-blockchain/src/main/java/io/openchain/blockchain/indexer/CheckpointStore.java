package io.openchain.blockchain.indexer;

import java.util.Optional;

/** Persistence of indexer checkpoints. Implementations must make {@link #save} atomic per indexer id. */
public interface CheckpointStore {
    Optional<Checkpoint> load(String indexerId);
    void save(String indexerId, Checkpoint checkpoint);
}
