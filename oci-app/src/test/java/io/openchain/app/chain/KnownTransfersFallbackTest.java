package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.ApiTestSupport;
import io.openchain.app.OciApplication;
import io.openchain.app.api.Dto;
import io.openchain.app.api.InvestigationController;
import io.openchain.blockchain.indexer.IndexerConfig;
import io.openchain.blockchain.indexer.TransferEventMapper;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.core.provider.ProviderException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Production defect: on a real EVM network the wallet page's Transactions tab always failed (503), because standard
 * JSON-RPC cannot list an address's transactions. It now shows the transfers the platform knows, each in its own unit.
 */
class KnownTransfersFallbackTest {
    static final String USDT_POLYGON = "0xc2132d05d31c914a87c6611c10748aeb04b58e8f";
    static final Address ME = new Address(Network.POLYGON, "0x" + "1".repeat(40)), OTHER = new Address(Network.POLYGON, "0x" + "2".repeat(40));
    static ConfigurableApplicationContext ctx;

    /** A real (non-simulated) EVM provider: like EvmJsonRpcProvider, it cannot list an address's transactions. */
    static final class NoListingProvider implements BlockchainProvider {
        @Override public Page<ChainTransaction> getAddressTransactions(Address address, String cursor, int limit) {
            throw new ProviderException("stub: address history is not available through standard JSON-RPC", false);
        }
        @Override public Optional<Boolean> isContract(Address address, long atBlock) { return Optional.of(address.equals(OTHER)); }
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

    static void transfer(TokenRegistry tokens, Token usdt, String tx, int log, long block, Instant ts, Address from, Address to, long raw) {
        TokenTransfer t = new TokenTransfer(Network.POLYGON, tx, log, block, "0xb" + block, ts, from, to, usdt, BigInteger.valueOf(raw));
        ctx.getBean(ChainIngestionService.class).onEvent(new TransferEventMapper(tokens).map(t, new BlockRef(Network.POLYGON, block,
                "0xb" + block, null, ts), Instant.now()));
    }

    @BeforeAll static void start() {
        ctx = new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class).profiles("test")
                .run("--server.port=0", "--oci.demo.networks=tron");
        ctx.getBean(ChainIngestionService.class).register(new NoListingProvider(), null, null, IndexerConfig.of("stub-polygon", Network.POLYGON, 100), null);
        TokenRegistry tokens = ctx.getBean(TokenRegistry.class);
        Token usdt = tokens.resolve(Network.POLYGON, USDT_POLYGON);
        Instant t0 = Instant.parse("2026-11-09T10:00:00Z");
        transfer(tokens, usdt, "0xtx0", 0, 100, t0, ME, OTHER, 1_500_000);
        transfer(tokens, usdt, "0xtx1", 0, 101, t0.plusSeconds(60), OTHER, ME, 3_000_000);
        // one transaction carrying two transfers
        transfer(tokens, usdt, "0xtx2", 0, 102, t0.plusSeconds(120), ME, OTHER, 4_500_000);
        transfer(tokens, usdt, "0xtx2", 1, 102, t0.plusSeconds(120), ME, new Address(Network.POLYGON, "0x" + "3".repeat(40)), 42);
    }

    @AfterAll static void stop() { if (ctx != null) ctx.close(); }

    @Test void transactionsTabServesKnownTransfersNewestFirstInTheirOwnUnitWithPaging() {
        InvestigationController c = ctx.getBean(InvestigationController.class);
        var page1 = c.transactions(ME.value(), "polygon", null, 2);
        assertThat(page1.items()).hasSize(2).allSatisfy(t -> {
            assertThat(t.kind()).isEqualTo("TRANSFER");
            assertThat(t.nativeSymbol()).isEqualTo(usdtSymbol());
            assertThat(t.nativeDecimals()).isEqualTo(6);
        });
        assertThat(page1.items()).extracting(Dto.TxDto::hash).containsExactly("0xtx2", "0xtx2"); // newest first, two rows
        assertThat(page1.items()).extracting(Dto.TxDto::id).doesNotHaveDuplicates();
        assertThat(new String(Base64.getUrlDecoder().decode(page1.nextCursor()), StandardCharsets.UTF_8)).isEqualTo("known:2");

        var page2 = c.transactions(ME.value(), "polygon", page1.nextCursor(), 2);
        assertThat(page2.items()).extracting(Dto.TxDto::hash).containsExactly("0xtx1", "0xtx0");
        assertThat(page2.nextCursor()).isNull();
    }

    static String usdtSymbol() { return ctx.getBean(TokenRegistry.class).resolve(Network.POLYGON, USDT_POLYGON).symbol(); }

    @Test void walletAnalysisReportsWhetherCodeIsDeployedInsteadOfAHardCodedFalse() {
        InvestigationController c = ctx.getBean(InvestigationController.class);
        assertThat(c.analysis(OTHER.value(), "polygon").isContract()).isTrue();
        assertThat(c.analysis(ME.value(), "polygon").isContract()).isFalse();
        assertThat(c.analysis(ME.value(), "polygon").profile().isContract()).isFalse();
        // blind spots of a real EVM network are stated, not hidden
        assertThat(c.analysis(ME.value(), "polygon").limitations()).anySatisfy(l -> assertThat(l).contains("Internal transactions"));
        assertThat(c.networks(true).stream().filter(n -> n.network().equals("polygon")).findFirst().orElseThrow().limitations())
                .anySatisfy(l -> assertThat(l).contains("Internal transactions"));
    }

    @Test void ageAndActivityFieldsAreWithheldUntilTheRealChainHasBeenObservedLongEnough() {
        // a few minutes of observation, default warm-up of 30 days: every address would look "new", so no field
        var events = ctx.getBean(EventHistory.class).range(null, null, Network.POLYGON, 100);
        assertThat(events).isNotEmpty().allSatisfy(e -> {
            assertThat(e.fields()).doesNotContainKeys("wallet.age_days", "wallet.tx_count", "counterparty.age_days");
            assertThat(e.fields()).containsKey("wallet.address");
        });
        assertThat(ctx.getBean(InvestigationController.class).analysis(ME.value(), "polygon").limitations())
                .anySatisfy(l -> assertThat(l).contains("withheld until 30 days of observation"));
    }
}
