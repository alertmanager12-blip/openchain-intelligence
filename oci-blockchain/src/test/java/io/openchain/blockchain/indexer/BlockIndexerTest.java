package io.openchain.blockchain.indexer;

import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.BlockActivity;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.ContractAdminChange;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenApproval;
import io.openchain.core.model.TokenFreeze;
import io.openchain.core.model.TokenTransfer;
import java.math.BigInteger;
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

    /** Ids of every indexable item (natives, token transfers, known-token approvals, freezes) in the range. */
    Set<String> canonical(long from, long to) {
        Set<String> s = new LinkedHashSet<>();
        for (long n = from; n <= to; n++) {
            BlockActivity a = chain.getBlockActivity(n, n);
            for (ChainTransaction tx : a.nativeTransfers()) s.add(TransferEventMapper.nativeEventId(tx));
            for (TokenTransfer t : a.tokenTransfers()) s.add(t.eventId());
            for (TokenApproval ap : a.approvals()) if (!ap.token().isUnverified()) s.add(ap.eventId());
            for (TokenFreeze z : a.freezes()) s.add(z.eventId());
            for (ContractAdminChange c : a.adminChanges()) if (c.contract().value().equals(USDT.contract())) s.add(c.eventId());
        }
        return s;
    }

    List<ChainEvent> ofType(ChainEvent.EventType t) { return sink.stream().filter(e -> e.type() == t && !e.removed()).toList(); }

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

        assertThat(ofType(ChainEvent.EventType.NATIVE_TRANSFER)).isNotEmpty();
        assertThat(ofType(ChainEvent.EventType.APPROVAL)).isNotEmpty();
        ChainEvent e = ofType(ChainEvent.EventType.TOKEN_TRANSFER).getFirst();
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

    static final Token USDT = new Token(Network.ETHEREUM, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6);

    @Test void nativeApprovalAndFreezeEventsCarryTheirFieldsAndAreEmittedOnce() {
        chain.mine(2);
        Address a = chain.addressPool().get(0), b = chain.addressPool().get(1);
        String nat = chain.scheduleNative(3, a, b, new BigInteger("1500000000000000000"));
        String appr = chain.scheduleApproval(3, a, b, USDT, TokenApproval.MAX_UINT256);
        String frz = chain.scheduleFreeze(4, b, USDT, "tether", TokenFreeze.Action.FREEZE, "AddedBlackList");
        String unf = chain.scheduleFreeze(5, b, USDT, "tether", TokenFreeze.Action.UNFREEZE, "RemovedBlackList");
        chain.duplicateTransfersOf(3);
        chain.mine(4);
        BlockIndexer idx = indexer(0, 10);
        idx.poll();
        idx.poll();
        assertThat(live()).isEqualTo(canonical(1, 6)); // live() asserts each id is added once

        ChainEvent n = sink.stream().filter(e -> e.eventId().equals("ethereum:" + nat + ":native")).findFirst().orElseThrow();
        assertThat(n.type()).isEqualTo(ChainEvent.EventType.NATIVE_TRANSFER);
        assertThat(n.field("transaction.amount")).isEqualTo(new BigDecimal("1.500000000000000000"));
        assertThat(n.field("transaction.token.symbol")).isEqualTo("ETH");
        assertThat(n.field("transaction.token.contract")).isNull();
        assertThat(n.field("transaction.from")).isEqualTo(a.value());
        assertThat(n.field("transaction.success")).isEqualTo(true);

        ChainEvent ap = sink.stream().filter(e -> appr.equals(e.field("transaction.hash"))).findFirst().orElseThrow();
        assertThat(ap.type()).isEqualTo(ChainEvent.EventType.APPROVAL);
        assertThat(ap.field("approval.owner")).isEqualTo(a.value());
        assertThat(ap.field("approval.spender")).isEqualTo(b.value());
        assertThat(ap.field("approval.unlimited")).isEqualTo(true);
        assertThat(ap.field("approval.amount_raw")).isEqualTo(new BigDecimal(TokenApproval.MAX_UINT256));
        assertThat(ap.field("token.symbol")).isEqualTo("USDT");

        ChainEvent f = sink.stream().filter(e -> frz.equals(e.field("transaction.hash"))).findFirst().orElseThrow();
        assertThat(f.type()).isEqualTo(ChainEvent.EventType.STABLECOIN_FREEZE);
        assertThat(f.field("freeze.address")).isEqualTo(b.value());
        assertThat(f.field("freeze.token")).isEqualTo("USDT");
        assertThat(f.field("freeze.action")).isEqualTo("FREEZE");
        assertThat(f.field("freeze.issuer")).isEqualTo("tether");
        assertThat(f.eventId()).startsWith("ethereum:" + frz + ":");
        ChainEvent u = sink.stream().filter(e -> unf.equals(e.field("transaction.hash"))).findFirst().orElseThrow();
        assertThat(u.type()).isEqualTo(ChainEvent.EventType.STABLECOIN_UNFREEZE);
        assertThat(u.field("freeze.action")).isEqualTo("UNFREEZE");
    }

    @Test void nativeApprovalAndFreezeEventsAreRetractedOnReorgAndReEmittedByTheNewFork() {
        chain.mine(10);
        Address a = chain.addressPool().get(2), b = chain.addressPool().get(3);
        String nat = chain.scheduleNative(12, a, b, BigInteger.TEN.pow(18));
        chain.scheduleApproval(12, a, b, USDT, TokenApproval.MAX_UINT256);
        String frz = chain.scheduleFreeze(13, b, USDT, "tether", TokenFreeze.Action.FREEZE, "AddedBlackList");
        chain.mine(4); // head 14
        BlockIndexer idx = indexer(0, 10);
        idx.poll();
        Set<String> before = live();
        assertThat(before).contains("ethereum:" + nat + ":native");

        chain.reorg(3, 3); // blocks 12..14 replaced; scheduled items are re-included with the same tx hash
        BlockIndexer.PollResult r = idx.poll();
        assertThat(r.reorgs()).isEqualTo(1);
        List<ChainEvent> removed = sink.stream().filter(ChainEvent::removed).toList();
        assertThat(removed).extracting(ChainEvent::type).contains(ChainEvent.EventType.NATIVE_TRANSFER,
                ChainEvent.EventType.APPROVAL, ChainEvent.EventType.STABLECOIN_FREEZE);
        assertThat(removed).anyMatch(e -> frz.equals(e.field("transaction.hash")));
        assertThat(live()).isEqualTo(canonical(1, chain.head())).contains("ethereum:" + nat + ":native");
    }

    @Test void approvalsOfUnknownTokensAreSkippedUnlessConfigured() {
        chain.mine(1);
        Token fake = new Token(Network.ETHEREUM, "0x9999999999999999999999999999999999999999", Token.UNKNOWN_SYMBOL, 0);
        Address a = chain.addressPool().get(4), b = chain.addressPool().get(5);
        String h = chain.scheduleApproval(2, a, b, fake, TokenApproval.MAX_UINT256);
        chain.mine(1);
        new BlockIndexer(chain, store, sink::add, new IndexerConfig("idx-a", 1, 0, 10, 100)).poll();
        assertThat(sink).noneMatch(e -> h.equals(e.field("transaction.hash")));
        new BlockIndexer(chain, store, sink::add, new IndexerConfig("idx-b", 1, 0, 10, 100).withApprovalsOfUnknownTokens(true)).poll();
        assertThat(sink).anyMatch(e -> h.equals(e.field("transaction.hash")) && e.field("transaction.token.known").equals(false));
    }

    @Test void blockWhoseItemsBelongToAnotherBlockHashIsNotIndexedYet() {
        chain.mine(3);
        // provider whose activity claims another block hash (chain moved between getBlock and the activity read)
        io.openchain.core.provider.BlockchainProvider p = (io.openchain.core.provider.BlockchainProvider) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.openchain.core.provider.BlockchainProvider.class}, (proxy, m, args) -> {
                    if (!m.getName().equals("getBlockActivity")) return m.invoke(chain, args);
                    BlockActivity real = chain.getBlockActivity((long) args[0], (long) args[1]);
                    ChainTransaction moved = new ChainTransaction(Network.ETHEREUM, "0x" + "ab".repeat(32), (long) args[0], "0x" + "cd".repeat(32),
                            Instant.EPOCH, chain.addressPool().get(0), chain.addressPool().get(1), BigInteger.ONE, BigInteger.ZERO, true, null, false);
                    return new BlockActivity(real.tokenTransfers(), List.of(moved), real.approvals(), real.freezes());
                });
        BlockIndexer.PollResult r = new BlockIndexer(p, store, sink::add, new IndexerConfig("idx-c", 1, 0, 10, 100)).poll();
        assertThat(r.blocksIndexed()).isZero();
        assertThat(sink).isEmpty();
    }

    static final Address ZERO = new Address(Network.ETHEREUM, "0x0000000000000000000000000000000000000000");

    @Test void adminChangesOfWatchedContractsAreEmittedOnceAndRetractedOnReorg() {
        chain.mine(10);
        Address prev = chain.addressPool().get(0), next = chain.addressPool().get(1);
        Address usdt = new Address(Network.ETHEREUM, USDT.contract());
        Address watched = new Address(Network.ETHEREUM, "0x00000000000000000000000000000000000000aa");
        Address other = new Address(Network.ETHEREUM, "0x00000000000000000000000000000000000000bb");
        String own = chain.scheduleAdminChange(12, usdt, ContractAdminChange.ROLE_OWNER, prev, next, "OwnershipTransferred");
        String up = chain.scheduleAdminChange(12, watched, ContractAdminChange.ROLE_IMPLEMENTATION, null, next, "Upgraded");
        String ignored = chain.scheduleAdminChange(13, other, ContractAdminChange.ROLE_OWNER, next, ZERO, "OwnershipTransferred");
        chain.duplicateTransfersOf(12);
        chain.mine(4); // head 14
        BlockIndexer idx = new BlockIndexer(chain, store, sink::add, new IndexerConfig("idx-adm", 1, 0, 10, 1000)
                .withWatchedContracts(Network.ETHEREUM, java.util.Set.of(watched.value().toUpperCase().replace("0X", "0x"))));
        idx.poll();
        idx.poll();
        assertThat(ofType(ChainEvent.EventType.CONTRACT_ADMIN_CHANGE)).extracting(e -> e.field("transaction.hash"))
                .containsExactlyInAnyOrder(own, up); // token registry contract + watch list; the other contract is out of scope

        ChainEvent e = ofType(ChainEvent.EventType.CONTRACT_ADMIN_CHANGE).stream().filter(x -> own.equals(x.field("transaction.hash")))
                .findFirst().orElseThrow();
        assertThat(e.field("admin.contract")).isEqualTo(usdt.value());
        assertThat(e.field("admin.previous")).isEqualTo(prev.value());
        assertThat(e.field("admin.new")).isEqualTo(next.value());
        assertThat(e.field("admin.role")).isEqualTo("owner");
        assertThat(e.field("admin.renounced")).isEqualTo(false);
        assertThat(e.field("contract.address")).isEqualTo(usdt.value());
        assertThat(e.field("contract.event")).isEqualTo("OwnershipTransferred");
        assertThat(e.field("contract.new_owner")).isEqualTo(next.value());
        assertThat(e.field("token.symbol")).isEqualTo("USDT");
        assertThat(e.blockNumber()).isEqualTo(12);
        ChainEvent u = ofType(ChainEvent.EventType.CONTRACT_ADMIN_CHANGE).stream().filter(x -> up.equals(x.field("transaction.hash")))
                .findFirst().orElseThrow();
        assertThat(u.field("admin.previous")).isNull();
        assertThat(u.field("admin.role")).isEqualTo("implementation");

        chain.reorg(3, 3); // blocks 12..14 replaced; the scheduled admin changes are re-included with the same tx hash
        assertThat(idx.poll().reorgs()).isEqualTo(1);
        assertThat(sink.stream().filter(ChainEvent::removed).filter(x -> x.type() == ChainEvent.EventType.CONTRACT_ADMIN_CHANGE))
                .extracting(x -> x.field("transaction.hash")).containsExactlyInAnyOrder(own, up);
        Set<String> ids = new LinkedHashSet<>();
        for (ChainEvent x : sink) if (x.type() == ChainEvent.EventType.CONTRACT_ADMIN_CHANGE) {
            if (x.removed()) assertThat(ids.remove(x.eventId())).isTrue(); else assertThat(ids.add(x.eventId())).as("emitted once").isTrue();
        }
        assertThat(ids).hasSize(2);
        assertThat(sink).noneMatch(x -> ignored.equals(x.field("transaction.hash")));
    }

    @Test void adminChangesOfAllContractsWhenConfigured() {
        chain.mine(1);
        Address other = new Address(Network.ETHEREUM, "0x00000000000000000000000000000000000000cc");
        String h = chain.scheduleAdminChange(2, other, ContractAdminChange.ROLE_OWNER, chain.addressPool().get(0), ZERO, "OwnershipTransferred");
        chain.mine(1);
        new BlockIndexer(chain, store, sink::add, new IndexerConfig("idx-all", 1, 0, 10, 100).withAdminChangesOfAllContracts(true)).poll();
        assertThat(sink).anyMatch(e -> h.equals(e.field("transaction.hash")) && Boolean.TRUE.equals(e.field("admin.renounced"))
                && e.field("token.symbol") == null);
    }
}
