package io.openchain.blockchain.indexer;

import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Network;
import io.openchain.core.model.TokenTransfer;
import io.openchain.core.provider.ProviderException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class BlockIndexerTest {
    final List<ChainEvent> sink = new CopyOnWriteArrayList<>();
    final InMemoryCheckpointStore store = new InMemoryCheckpointStore();
    final SimulatedChainProvider chain = new SimulatedChainProvider("sim", Network.ETHEREUM, 7, 5, 8, new DefaultTokenRegistry());

    BlockIndexer indexer(int confirmations, int window) {
        return new BlockIndexer(chain, store, sink::add,
                new IndexerConfig("idx-eth", 1, confirmations, window, 1000));
    }

    /** Apply emitted stream (adds + retractions) and return the resulting live event ids. */
    Set<String> live() {
        Set<String> s = new LinkedHashSet<>();
        for (ChainEvent e : sink) if (e.removed()) assertThat(s.remove(e.eventId())).as("retracting unknown " + e.eventId()).isTrue();
                                  else assertThat(s.add(e.eventId())).as("duplicate add " + e.eventId()).isTrue();
        return s;
    }

    Set<String> canonical(long from, long to) {
        Set<String> s = new LinkedHashSet<>();
        for (TokenTransfer t : chain.getTokenTransfers(from, to)) s.add(t.eventId());
        return s;
    }

    long eventsInBlocks(long from, long to) {
        return sink.stream().filter(e -> !e.removed() && e.blockNumber() >= from && e.blockNumber() <= to).count();
    }

    @Test void indexesAllTransfersWithFlatFields() {
        chain.mine(20);
        BlockIndexer.PollResult r = indexer(0, 10).poll();
        assertThat(r.lastBlock()).isEqualTo(20);
        assertThat(r.blocksIndexed()).isEqualTo(20);
        assertThat(sink).isNotEmpty().hasSize(r.eventsEmitted());
        assertThat(live()).isEqualTo(canonical(1, 20));

        ChainEvent e = sink.getFirst();
        TokenTransfer t = chain.getTokenTransfers(e.blockNumber(), e.blockNumber()).getFirst();
        assertThat(e.type()).isEqualTo(ChainEvent.EventType.TOKEN_TRANSFER);
        assertThat(e.eventId()).isEqualTo(t.eventId());
        assertThat(e.field("network")).isEqualTo("ethereum");
        assertThat(e.field("transaction.hash")).isEqualTo(t.txHash());
        assertThat(e.field("transaction.from")).isEqualTo(t.from().value());
        assertThat(e.field("transaction.to")).isEqualTo(t.to().value());
        assertThat(e.field("transaction.amount")).isInstanceOf(BigDecimal.class).isEqualTo(t.amount());
        assertThat(e.field("transaction.amount_raw")).isEqualTo(new BigDecimal(t.rawAmount()));
        assertThat(e.field("transaction.token.symbol")).isIn("USDT", "USDC");
        assertThat(e.field("transaction.token.contract")).isEqualTo(t.token().contract());
        assertThat(e.field("transaction.token.known")).isEqualTo(true);
        assertThat(e.field("block.number")).isEqualTo(t.blockNumber());
        assertThat(e.field("block.timestamp")).isInstanceOf(Instant.class);
        assertThat(e.eventTime()).isEqualTo(t.timestamp());
    }

    @Test void respectsConfirmationsAndResumesFromCheckpoint() {
        chain.mine(10);
        assertThat(indexer(3, 10).poll().lastBlock()).isEqualTo(7);
        assertThat(sink.stream().mapToLong(ChainEvent::blockNumber).max().orElse(0)).isLessThanOrEqualTo(7);
        chain.mine(5);
        BlockIndexer.PollResult r = indexer(3, 10).poll(); // new instance, same store = restart
        assertThat(r.lastBlock()).isEqualTo(12);
        assertThat(live()).isEqualTo(canonical(1, 12));
    }

    @Test void reorgOfDepthOneRetractsAndReindexes() { assertReorg(1, 2); }

    @Test void reorgOfDepthThreeRetractsAndReindexes() { assertReorg(3, 4); }

    @Test void reorgOfDepthEqualToWindowIsHandled() { assertReorg(5, 5); }

    private void assertReorg(int depth, int newLength) {
        chain.mine(15);
        BlockIndexer idx = indexer(0, 5);
        idx.poll();
        long orphanedEvents = eventsInBlocks(15 - depth + 1, 15);
        Set<String> before = live();

        chain.reorg(depth, newLength);
        BlockIndexer.PollResult r = idx.poll();

        assertThat(r.reorgs()).isEqualTo(1);
        assertThat(r.eventsRetracted()).isEqualTo(orphanedEvents);
        List<ChainEvent> removed = sink.stream().filter(ChainEvent::removed).toList();
        assertThat(removed).hasSize((int) orphanedEvents)
                .allSatisfy(e -> assertThat(e.blockNumber()).isGreaterThan(15 - depth));
        assertThat(removed).extracting(ChainEvent::eventId).allMatch(before::contains);
        assertThat(r.lastBlock()).isEqualTo(15 - depth + newLength);
        assertThat(live()).isEqualTo(canonical(1, chain.head()));
        assertThat(idx.checkpoint().window().getLast().block()).isEqualTo(chain.getBlock(chain.head()).orElseThrow());
    }

    @Test void reorgDeeperThanWindowFailsExplicitlyWithoutStateChange() {
        chain.mine(15);
        BlockIndexer idx = indexer(0, 3);
        idx.poll();
        Checkpoint before = idx.checkpoint();
        int emitted = sink.size();
        chain.reorg(5, 6);
        assertThatThrownBy(idx::poll).isInstanceOf(ReorgTooDeepException.class)
                .hasMessageContaining("deeper than the rollback window (3 blocks)");
        assertThat(idx.checkpoint()).isEqualTo(before);
        assertThat(sink).hasSize(emitted); // no partial retraction
    }

    @Test void duplicateTransfersInSameBlockAreEmittedOnce() {
        chain.mine(6);
        chain.duplicateTransfersOf(3);
        chain.duplicateTransfersOf(4);
        indexer(0, 10).poll();
        assertThat(live()).isEqualTo(canonical(1, 6)); // live() asserts no duplicate add
    }

    @Test void reprocessingAfterRestartOrPollAgainDoesNotReEmit() {
        chain.mine(8);
        indexer(0, 10).poll();
        int n = sink.size();
        assertThat(indexer(0, 10).poll().eventsEmitted()).isZero();
        assertThat(indexer(0, 10).poll().blocksIndexed()).isZero();
        assertThat(sink).hasSize(n);
    }

    @Test void providerFailureMidwayKeepsConsistentStateAndResumes() {
        chain.mine(10);
        BlockIndexer idx = new BlockIndexer(chain, store, sink::add, new IndexerConfig("idx-eth", 1, 0, 10, 4));
        idx.poll(); // 4 blocks
        chain.failNext(2, true); // status ok? first failure hits status -> unhealthy
        assertThatThrownBy(idx::poll).isInstanceOf(ProviderException.class);
        assertThatThrownBy(idx::poll).isInstanceOf(ProviderException.class);
        while (idx.poll().blocksIndexed() > 0) { }
        assertThat(live()).isEqualTo(canonical(1, 10));
    }
}
