package io.openchain.blockchain.indexer;

import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.BlockActivity;
import io.openchain.core.model.BlockRef;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.ContractAdminChange;
import io.openchain.core.model.NetworkStatus;
import io.openchain.core.model.TokenApproval;
import io.openchain.core.model.TokenFreeze;
import io.openchain.core.model.TokenTransfer;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.core.provider.ProviderException;
import java.time.Clock;
import java.util.*;

/**
 * Network-agnostic, cursor-based indexer over {@link BlockchainProvider#getBlockActivity}: native transfers,
 * token transfers, token approvals (registry-known tokens unless {@link IndexerConfig#approvalsOfUnknownTokens()})
 * stablecoin freezes and contract admin changes (contracts of the token registry or of
 * {@link IndexerConfig#watchedContracts()}, unless {@link IndexerConfig#adminChangesOfAllContracts()}), in that order
 * within a block. All event kinds share the window/retraction mechanics.
 * <ul>
 *   <li>indexes blocks up to {@code head - confirmations}, at most {@code maxBlocksPerPoll} per {@link #poll()};</li>
 *   <li>detects reorgs when a new block's parentHash differs from the last indexed hash, walks back the window to the
 *       common ancestor, emits {@code removed=true} for every retracted event (newest first), then re-indexes;</li>
 *   <li>a reorg with no ancestor inside the window raises {@link ReorgTooDeepException} without changing state;</li>
 *   <li>deduplicates by eventId within the window; checkpoints after each block (at-least-once delivery).</li>
 * </ul>
 * Not thread-safe for concurrent polls of the same instance ({@link #poll()} is synchronized).
 */
public final class BlockIndexer {
    private final BlockchainProvider provider;
    private final CheckpointStore store;
    private final ChainEventSink sink;
    private final IndexerConfig config;
    private final TransferEventMapper mapper;
    private final Clock clock;
    private final TokenRegistry tokens;

    public record PollResult(long head, long lastBlock, int blocksIndexed, int eventsEmitted, int eventsRetracted, int reorgs) {}

    public BlockIndexer(BlockchainProvider provider, CheckpointStore store, ChainEventSink sink, IndexerConfig config) {
        this(provider, store, sink, config, new DefaultTokenRegistry(), Clock.systemUTC());
    }

    public BlockIndexer(BlockchainProvider provider, CheckpointStore store, ChainEventSink sink, IndexerConfig config,
                        TokenRegistry tokens, Clock clock) {
        this.provider = provider;
        this.store = store;
        this.sink = sink;
        this.config = config;
        this.mapper = new TransferEventMapper(tokens);
        this.tokens = tokens;
        this.clock = clock;
    }

    public Checkpoint checkpoint() {
        return store.load(config.indexerId()).orElse(new Checkpoint(provider.network(), config.startBlock() - 1, List.of()));
    }

    /** One bounded indexing step. Provider failures propagate as {@link ProviderException}; state stays consistent. */
    public synchronized PollResult poll() {
        Checkpoint cp = checkpoint();
        if (cp.network() != provider.network()) throw new IllegalStateException("checkpoint network mismatch");
        NetworkStatus status = provider.getNetworkStatus();
        if (!status.healthy()) throw new ProviderException(provider.id() + " unhealthy: " + status.detail(), true);
        long safeHead = status.headBlock() - config.confirmations();

        Deque<IndexedBlock> window = new ArrayDeque<>(cp.window());
        long last = cp.lastBlock();
        int blocks = 0, emitted = 0, retracted = 0, reorgs = 0;

        // Tip check: catches same-length (or shorter) forks that no new block would reveal yet.
        if (!window.isEmpty()) {
            BlockRef tip = window.peekLast().block();
            Optional<BlockRef> canonicalTip = provider.getBlock(tip.number());
            if (canonicalTip.isEmpty() || !canonicalTip.get().hash().equals(tip.hash())) {
                List<ChainEvent> retractions = new ArrayList<>();
                last = rollback(window, tip.number(), retractions);
                retracted += retractions.size();
                reorgs++;
                save(last, window);
            }
        }

        while (last + 1 <= safeHead && blocks < config.maxBlocksPerPoll()) {
            long n = last + 1;
            Optional<BlockRef> maybe = provider.getBlock(n);
            if (maybe.isEmpty()) break;
            BlockRef block = maybe.get();
            if (!window.isEmpty() && !Objects.equals(block.parentHash(), window.peekLast().block().hash())) {
                List<ChainEvent> retractions = new ArrayList<>();
                last = rollback(window, n, retractions);
                retracted += retractions.size();
                reorgs++;
                save(last, window);
                continue;
            }
            BlockActivity activity = provider.getBlockActivity(n, n);
            if (activity.blockHashes().anyMatch(h -> !h.equals(block.hash()))) {
                break; // chain moved between the two calls: retry on next poll, the parent check will sort it out
            }
            Set<String> seen = seenIds(window);
            List<ChainEvent> events = new ArrayList<>();
            for (ChainTransaction tx : activity.nativeTransfers()) {
                // providers only return successful non-zero transfers; re-checked so a lax provider cannot fake value
                if (!tx.success() || tx.value() == null || tx.value().signum() <= 0 || tx.from() == null || tx.to() == null) continue;
                emit(TransferEventMapper.nativeEventId(tx), () -> mapper.mapNative(tx, block, clock.instant()), seen, events);
            }
            for (TokenTransfer t : activity.tokenTransfers()) emit(t.eventId(), () -> mapper.map(t, block, clock.instant()), seen, events);
            for (TokenApproval a : activity.approvals()) {
                if (!config.approvalsOfUnknownTokens() && !tokens.isKnown(a.token())) continue;
                emit(a.eventId(), () -> mapper.mapApproval(a, block, clock.instant()), seen, events);
            }
            for (TokenFreeze z : activity.freezes()) emit(z.eventId(), () -> mapper.mapFreeze(z, block, clock.instant()), seen, events);
            for (ContractAdminChange c : activity.adminChanges()) {
                if (!watched(c)) continue;
                emit(c.eventId(), () -> mapper.mapAdminChange(c, block, clock.instant()), seen, events);
            }
            emitted += events.size();
            window.addLast(new IndexedBlock(block, events));
            while (window.size() > config.windowSize()) window.removeFirst();
            last = n;
            blocks++;
            save(last, window);
        }
        return new PollResult(status.headBlock(), last, blocks, emitted, retracted, reorgs);
    }

    /**
     * Pops orphaned blocks back to the common ancestor (inside the window, or the parent of the oldest window block)
     * and emits retractions, newest first. Returns the new last indexed block. All checks happen before any emit.
     */
    private long rollback(Deque<IndexedBlock> window, long detectedAt, List<ChainEvent> retractions) {
        List<IndexedBlock> orphaned = new ArrayList<>();
        Long ancestor = null;
        Iterator<IndexedBlock> it = window.descendingIterator();
        while (it.hasNext()) {
            IndexedBlock ib = it.next();
            Optional<BlockRef> canonical = provider.getBlock(ib.block().number());
            if (canonical.isPresent() && canonical.get().hash().equals(ib.block().hash())) { ancestor = ib.block().number(); break; }
            orphaned.add(ib);
        }
        if (orphaned.isEmpty()) {
            // parent mismatch although our tip is still canonical: inconsistent source (e.g. load-balanced nodes
            // at different heights). Do not loop; retry later (possibly on another source).
            throw new ProviderException(provider.id() + ": block " + detectedAt + " does not link to canonical block "
                    + ancestor + "; inconsistent provider view", true);
        }
        if (ancestor == null) {
            BlockRef oldest = window.peekFirst().block();
            long parentNumber = oldest.number() - 1;
            boolean parentIsCanonical = parentNumber < config.startBlock() // never indexed: nothing to retract
                    || provider.getBlock(parentNumber).map(b -> b.hash().equals(oldest.parentHash())).orElse(false);
            if (!parentIsCanonical) throw new ReorgTooDeepException(provider.network(), detectedAt, config.windowSize());
            ancestor = parentNumber;
        }
        for (IndexedBlock ib : orphaned) {
            List<ChainEvent> evs = ib.events();
            for (int i = evs.size() - 1; i >= 0; i--) {
                ChainEvent r = TransferEventMapper.retraction(evs.get(i), clock.instant());
                sink.emit(r);
                retractions.add(r);
            }
            window.removeLast();
        }
        return ancestor;
    }

    /** Admin changes are kept for registry tokens, the watch list, or every contract when configured. */
    private boolean watched(ContractAdminChange c) {
        if (config.adminChangesOfAllContracts()) return true;
        String contract = TokenRegistry.canonical(c.network(), c.contract().value());
        return config.watchedContracts().contains(contract) || tokens.find(c.network(), contract).isPresent();
    }

    private void emit(String eventId, java.util.function.Supplier<ChainEvent> event, Set<String> seen, List<ChainEvent> events) {
        if (!seen.add(eventId)) return;
        ChainEvent e = event.get();
        sink.emit(e);
        events.add(e);
    }

    private static Set<String> seenIds(Deque<IndexedBlock> window) {
        Set<String> s = new HashSet<>();
        for (IndexedBlock ib : window) for (ChainEvent e : ib.events()) s.add(e.eventId());
        return s;
    }

    private void save(long last, Deque<IndexedBlock> window) {
        store.save(config.indexerId(), new Checkpoint(provider.network(), last, List.copyOf(window)));
    }
}
