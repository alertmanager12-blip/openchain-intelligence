package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.app.ApiTestSupport;
import io.openchain.app.OciApplication;
import io.openchain.blockchain.indexer.IndexerConfig;
import io.openchain.blockchain.indexer.TransferEventMapper;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.core.provider.ProviderException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Production finding (Ethereum mainnet, demo off): {@code wallet.balance} was estimated from the flows seen since the
 * indexer start, so every wallet spending funds received earlier looked drained (182 "wallet-draining" alerts within
 * minutes). On a real chain the balance now comes from the provider (end of the previous block) or is absent.
 */
class RealBalanceDrainTest {

    static final String USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7";
    static final Address FUNDER = eth("1"), RICH = eth("2"), DRAINED = eth("3"), UNKNOWN = eth("4"), SINK = eth("9");
    static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");

    static ConfigurableApplicationContext ctx;
    static final StubRealProvider provider = new StubRealProvider();

    static Address eth(String d) { return new Address(Network.ETHEREUM, "0x" + d.repeat(40)); }

    /** A real (non-simulated) Ethereum provider: balances by (address, block), failures for UNKNOWN. */
    static final class StubRealProvider implements BlockchainProvider {
        final Map<String, BigInteger> balances = new ConcurrentHashMap<>();
        final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        void balance(Address a, long block, String usdt) { balances.put(a.value() + "@" + block, new BigDecimal(usdt).movePointRight(6).toBigIntegerExact()); }
        @Override public Optional<BigInteger> getBalanceAt(Address a, Token token, long atBlock) {
            calls.add(a.value() + "@" + atBlock);
            if (a.value().startsWith(SLOW_PREFIX)) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return Optional.of(BigInteger.valueOf(1_000_000_000L));
            }
            if (a.equals(UNKNOWN)) throw new ProviderException("HTTP 429 rate limited", true);
            return Optional.ofNullable(balances.get(a.value() + "@" + atBlock));
        }
        @Override public String id() { return "stub-eth"; }
        @Override public Network network() { return Network.ETHEREUM; }
        @Override public Optional<BlockRef> getBlock(long number) { return Optional.empty(); }
        @Override public Optional<ChainTransaction> getTransaction(String hash) { return Optional.empty(); }
        @Override public Optional<TransactionReceipt> getTransactionReceipt(String hash) { return Optional.empty(); }
        @Override public Page<ChainTransaction> getAddressTransactions(Address address, String cursor, int limit) { return Page.of(List.of()); }
        @Override public List<TokenTransfer> getTokenTransfers(long fromBlock, long toBlock) { return List.of(); }
        @Override public List<Balance> getAddressBalance(Address address) { return List.of(); }
        @Override public Subscription subscribeToEvents(Consumer<BlockRef> onNewHead) { return () -> {}; }
        @Override public NetworkStatus getNetworkStatus() {
            return new NetworkStatus(Network.ETHEREUM, 200, 190, true, "stub-eth", 1, Instant.now(), "stub");
        }
    }

    @BeforeAll static void start() {
        ctx = new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class).profiles("test")
                .run("--server.port=0", "--oci.demo.networks=tron");
        ctx.getBean(ChainIngestionService.class).register(provider, null, null, IndexerConfig.of("stub-eth", Network.ETHEREUM, 100), null);
    }

    @AfterAll static void stop() { if (ctx != null) ctx.close(); }

    static final String SLOW_PREFIX = "0x5100";

    static io.openchain.core.event.ChainEvent event(int n, long block, Address from, Address to, String usdt) {
        TokenRegistry tokens = ctx.getBean(TokenRegistry.class);
        Token token = tokens.resolve(Network.ETHEREUM, USDT);
        Instant ts = T0.plusSeconds(12 * (block - 100));
        TokenTransfer t = new TokenTransfer(Network.ETHEREUM, "0x" + String.format("%064x", n), 0, block, "0xb" + block, ts, from, to, token,
                new BigDecimal(usdt).movePointRight(6).toBigIntegerExact());
        return new TransferEventMapper(tokens).map(t, new BlockRef(Network.ETHEREUM, block, "0xb" + block, null, ts), Instant.now());
    }

    static void transfer(int n, long block, Address from, Address to, String usdt) {
        ctx.getBean(ChainIngestionService.class).onEvent(event(n, block, from, to, usdt));
    }

    static List<Alert> drains(Address a) {
        return ctx.getBean(AlertService.class).repository().findAll().stream().filter(x -> x.ruleId().equals("wallet-draining"))
                .filter(x -> x.subject() != null && a.value().equals(x.subject().address())).toList();
    }

    static boolean rapidDrainSignal(Address a) {
        return ctx.getBean(ChainIngestionService.class).recentSignals().stream()
                .anyMatch(s -> s.type() == SignalType.RAPID_DRAIN && s.address().equals(a));
    }

    @Test void walletWithAPreExistingRealBalanceSpendingPartOfItIsNotDrained() {
        // the platform sees 10 USDT arrive, then 2 x 5,000 USDT leave: by the flow estimate that is a 1000x "drain"
        provider.balance(FUNDER, 100, "50");
        provider.balance(RICH, 101, "1000010");
        provider.balance(RICH, 102, "995010");
        transfer(1, 101, FUNDER, RICH, "10");
        transfer(2, 102, RICH, SINK, "5000");
        transfer(3, 103, RICH, SINK, "5000");
        assertThat(drains(RICH)).isEmpty();
        assertThat(rapidDrainSignal(RICH)).isFalse();
        assertThat(provider.calls).contains(RICH.value() + "@101", RICH.value() + "@102");
    }

    @Test void spendingMoreThan80PercentOfTheRealBalanceRaisesTheAlert() {
        provider.balance(DRAINED, 109, "10000");
        provider.balance(DRAINED, 110, "4000");
        transfer(11, 110, DRAINED, SINK, "6000");
        transfer(12, 111, DRAINED, eth("8"), "3000");
        assertThat(drains(DRAINED)).singleElement().satisfies(a -> {
            assertThat(a.subject().fields().get("wallet.balance")).isEqualTo(new BigDecimal("4000").setScale(6));
            assertThat(a.evidence().get("value").toString()).isEqualTo("0.9");
        });
        assertThat(rapidDrainSignal(DRAINED)).isTrue();
    }

    @Test void unknownRealBalanceLeavesTheFieldAbsentAndRaisesNothing() {
        ChainIngestionService chains = ctx.getBean(ChainIngestionService.class);
        long failures = chains.balanceLookupFailures();
        transfer(21, 120, UNKNOWN, SINK, "100");
        transfer(22, 120, UNKNOWN, eth("7"), "100"); // same block: served from the cache, no second call
        assertThat(chains.balanceLookupFailures()).isEqualTo(failures + 1);
        assertThat(provider.calls.stream().filter(c -> c.startsWith(UNKNOWN.value()))).hasSize(1);
        assertThat(drains(UNKNOWN)).isEmpty();
        assertThat(rapidDrainSignal(UNKNOWN)).isFalse();
    }

    static Address sender(int i) { return new Address(Network.ETHEREUM, "0x" + String.format("%040x", 0xabc000 + i)); }

    @Test void realBalanceLookupsAreCappedPerBlock() {
        ChainIngestionService chains = ctx.getBean(ChainIngestionService.class);
        // lag check neutralised: this test is about the per-block cap only (test order must not matter)
        chains.setBalanceBudget(Network.ETHEREUM, new ChainIngestionService.BalanceBudget(25, Long.MAX_VALUE));
        long skipped = chains.balanceLookupsSkipped();
        for (int i = 0; i < 30; i++) transfer(300 + i, 150, sender(i), SINK, "100");
        long calls = provider.calls.stream().filter(c -> c.endsWith("@149")).count();
        assertThat(calls).isEqualTo(25);                                   // never more than the budget for one block
        assertThat(chains.balanceLookupsSkipped()).isEqualTo(skipped + 5); // the rest: field absent, no call
    }

    @Test void noRealBalanceLookupWhileCatchingUpFarBehindTheHead() {
        ChainIngestionService chains = ctx.getBean(ChainIngestionService.class);
        ChainIngestionService.NetworkRuntime rt = chains.runtime(Network.ETHEREUM).orElseThrow();
        chains.setBalanceBudget(Network.ETHEREUM, new ChainIngestionService.BalanceBudget(25, 32));
        try {
            chains.poll(rt); // records the head (200) from the provider's network status
            long skipped = chains.balanceLookupsSkipped();
            int before = provider.calls.size();
            transfer(400, 160, sender(99), SINK, "100"); // 40 blocks behind the head > 32: catch-up, no RPC call
            assertThat(provider.calls).hasSize(before);
            assertThat(chains.balanceLookupsSkipped()).isEqualTo(skipped + 1);
            assertThat(drains(sender(99))).isEmpty();
        } finally {
            // the other tests use blocks ~100-150, far "behind" the head recorded here: neutralise the lag check for them
            chains.setBalanceBudget(Network.ETHEREUM, new ChainIngestionService.BalanceBudget(25, Long.MAX_VALUE));
        }
    }

    @Test void balancesOfABlockArePrefetchedConcurrentlyThenServedFromTheCache() {
        ChainIngestionService chains = ctx.getBean(ChainIngestionService.class);
        chains.setBalanceBudget(Network.ETHEREUM, new ChainIngestionService.BalanceBudget(5, Long.MAX_VALUE));
        List<Address> senders = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> new Address(Network.ETHEREUM, SLOW_PREFIX + String.format("%036x", i))).toList();
        List<io.openchain.core.event.ChainEvent> block = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) block.add(event(500 + i, 170, senders.get(i), SINK, "100"));
        block.add(event(510, 170, senders.get(0), eth("6"), "100")); // same sender and token: no second lookup

        long t0 = System.nanoTime();
        chains.prefetchBalances(block);
        long millis = (System.nanoTime() - t0) / 1_000_000;
        assertThat(millis).as("5 lookups of 300 ms in parallel, not 1.5 s in sequence").isLessThan(1_000);
        assertThat(provider.calls.stream().filter(c -> c.startsWith(SLOW_PREFIX))).hasSize(5);

        long lookups = chains.balanceLookups();
        block.forEach(e -> chains.onEvent(e));
        assertThat(chains.balanceLookups()).as("processing is served from the cache").isEqualTo(lookups);
        assertThat(provider.calls.stream().filter(c -> c.startsWith(SLOW_PREFIX))).hasSize(5);
    }
}
