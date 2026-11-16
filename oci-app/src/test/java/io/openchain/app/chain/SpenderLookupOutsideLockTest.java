package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.ApiTestSupport;
import io.openchain.app.OciApplication;
import io.openchain.blockchain.indexer.IndexerConfig;
import io.openchain.blockchain.indexer.TransferEventMapper;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Code review I15 (2026-11-13): the contract lookup of an approval's spender is a provider call (retries, rate limiter,
 * back-off). It ran under the service-wide processing lock, so a slow or rate-limited network stalled every other one.
 */
class SpenderLookupOutsideLockTest {
    static ConfigurableApplicationContext ctx;
    static ChainIngestionService chains;
    static final AtomicInteger lookups = new AtomicInteger(), lookupsUnderLock = new AtomicInteger();

    static final class RecordingProvider implements BlockchainProvider {
        @Override public Optional<Boolean> isContract(Address address, long atBlock) {
            lookups.incrementAndGet();
            if (Thread.holdsLock(chains)) lookupsUnderLock.incrementAndGet();
            return Optional.of(true);
        }
        @Override public Page<ChainTransaction> getAddressTransactions(Address address, String cursor, int limit) { return new Page<>(List.of(), null); }
        @Override public String id() { return "stub-polygon"; }
        @Override public Network network() { return Network.POLYGON; }
        @Override public Optional<BlockRef> getBlock(long number) { return Optional.empty(); }
        @Override public Optional<ChainTransaction> getTransaction(String hash) { return Optional.empty(); }
        @Override public Optional<TransactionReceipt> getTransactionReceipt(String hash) { return Optional.empty(); }
        @Override public List<TokenTransfer> getTokenTransfers(long fromBlock, long toBlock) { return List.of(); }
        @Override public List<Balance> getAddressBalance(Address address) { return List.of(); }
        @Override public Subscription subscribeToEvents(Consumer<BlockRef> onNewHead) { return () -> {}; }
        @Override public NetworkStatus getNetworkStatus() {
            return new NetworkStatus(Network.POLYGON, 1000, 990, true, "stub-polygon", 1, Instant.now(), "stub");
        }
    }

    @BeforeAll static void start() {
        ctx = new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class).profiles("test")
                .run("--server.port=0", "--oci.demo.networks=tron");
        chains = ctx.getBean(ChainIngestionService.class);
        chains.register(new RecordingProvider(), null, null, IndexerConfig.of("stub-polygon", Network.POLYGON, 100), null);
    }

    @AfterAll static void stop() { if (ctx != null) ctx.close(); }

    @Test void theSpenderLookupRunsBeforeTheProcessingLockAndStillEnrichesTheEvent() {
        TokenRegistry tokens = ctx.getBean(TokenRegistry.class);
        Token usdt = tokens.resolve(Network.POLYGON, "0xc2132d05d31c914a87c6611c10748aeb04b58e8f");
        Instant ts = Instant.parse("2026-11-09T10:00:00Z");
        Address owner = new Address(Network.POLYGON, "0x" + "c1".repeat(20)), spender = new Address(Network.POLYGON, "0x" + "c2".repeat(20));
        TokenApproval a = new TokenApproval(Network.POLYGON, "0xapprove", 0, 100, "0xb100", ts, owner, spender, usdt, BigInteger.valueOf(1_000_000));
        ChainEvent e = new TransferEventMapper(tokens).mapApproval(a, new BlockRef(Network.POLYGON, 100, "0xb100", null, ts), Instant.now());

        chains.onEvent(e);

        assertThat(lookups.get()).isEqualTo(1);
        assertThat(lookupsUnderLock.get()).as("provider call made while holding the service lock").isZero();
        ChainEvent stored = ctx.getBean(EventHistory.class).range(null, null, Network.POLYGON, 100).stream()
                .filter(x -> x.eventId().equals(e.eventId())).findFirst().orElseThrow();
        assertThat(stored.fields()).containsEntry("approval.spender_is_contract", true);
    }
}
