package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.ApiTestSupport;
import io.openchain.app.OciApplication;
import io.openchain.blockchain.indexer.IndexerConfig;
import io.openchain.blockchain.indexer.TransferEventMapper;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.BlockRef;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Code review B3 (2026-11-13): a wallet whose profile left the bounded working set looked brand new (wallet.age_days = 0,
 * wallet.tx_count = 0), so "young wallet" rules would fire on old wallets. An address seen before whose profile is gone has
 * an unknown age: the fields are absent. A never-seen address is genuinely new: age 0 stays, so nothing is lost for it.
 */
class WalletAgeAfterEvictionTest {
    static final String USDT_POLYGON = "0xc2132d05d31c914a87c6611c10748aeb04b58e8f";
    static ConfigurableApplicationContext ctx;
    static ChainIngestionService chains;
    static TokenRegistry tokens;
    static Token usdt;
    static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    static Address addr(String hex2) { return new Address(Network.POLYGON, "0x" + hex2.repeat(20)); }

    static ChainEvent transfer(String tx, long block, Instant ts, Address from, Address to) {
        TokenTransfer t = new TokenTransfer(Network.POLYGON, tx, 0, block, "0xb" + block, ts, from, to, usdt, BigInteger.valueOf(5_000_000));
        ChainEvent e = new TransferEventMapper(tokens).map(t, new BlockRef(Network.POLYGON, block, "0xb" + block, null, ts), Instant.now());
        chains.onEvent(e);
        return ctx.getBean(EventHistory.class).range(null, null, Network.POLYGON, 1000).stream()
                .filter(x -> x.eventId().equals(e.eventId()) && !x.removed()).findFirst().orElseThrow();
    }

    @BeforeAll static void start() {
        ctx = new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class).profiles("test")
                .run("--server.port=0", "--oci.demo.networks=tron", "--oci.ingestion.age-warmup=PT0S");
        chains = ctx.getBean(ChainIngestionService.class);
        chains.register(new KnownTransfersFallbackTest.NoListingProvider(), null, null, IndexerConfig.of("stub-polygon", Network.POLYGON, 100), null);
        tokens = ctx.getBean(TokenRegistry.class);
        usdt = tokens.resolve(Network.POLYGON, USDT_POLYGON);
    }

    @AfterAll static void stop() { if (ctx != null) ctx.close(); }

    @Test void ageIsUnknownForAnEvictedWalletAndZeroForAGenuinelyNewOne() {
        Address old = addr("a1"), peer = addr("a2"), fresh = addr("a3"), other = addr("a4"), newcomer = addr("a5");

        // day 0: `old` and `peer` are seen for the first time (genuinely new: age 0, measured)
        ChainEvent first = transfer("0xage0", 100, T0, old, peer);
        assertThat(first.fields()).containsEntry("wallet.age_days", 0L).containsEntry("wallet.tx_count", 0L)
                .containsEntry("counterparty.age_days", 0L);

        // day 40, profile still in memory: the real age
        ChainEvent later = transfer("0xage1", 200, T0.plus(Duration.ofDays(40)), old, peer);
        assertThat(later.fields()).containsEntry("wallet.age_days", 40L).containsEntry("counterparty.age_days", 40L);

        // the bounded working set drops every profile (caps), as it does under chain-wide volume
        chains.trimWorkingSet(Duration.ofDays(3650), Duration.ofDays(3650), Duration.ofDays(3650),
                Integer.MAX_VALUE, Integer.MAX_VALUE, 0, a -> false);

        // day 60: `old` pays `peer` again. Both were seen before and their profiles are gone: age unknown, NOT 0
        ChainEvent afterEviction = transfer("0xage2", 300, T0.plus(Duration.ofDays(60)), old, peer);
        assertThat(afterEviction.fields()).doesNotContainKeys("wallet.age_days", "wallet.tx_count", "counterparty.age_days");
        assertThat(afterEviction.fields()).containsKey("wallet.address");

        // a never-seen sender and receiver are genuinely new: age 0 is a fact and stays
        ChainEvent brandNew = transfer("0xage3", 301, T0.plus(Duration.ofDays(60)), fresh, other);
        assertThat(brandNew.fields()).containsEntry("wallet.age_days", 0L).containsEntry("wallet.tx_count", 0L)
                .containsEntry("counterparty.age_days", 0L);

        // mixed: evicted sender (unknown) paying a never-seen receiver (0)
        ChainEvent mixed = transfer("0xage4", 302, T0.plus(Duration.ofDays(61)), old, newcomer);
        assertThat(mixed.fields()).doesNotContainKeys("wallet.age_days", "wallet.tx_count");
        assertThat(mixed.fields()).containsEntry("counterparty.age_days", 0L);
    }
}
