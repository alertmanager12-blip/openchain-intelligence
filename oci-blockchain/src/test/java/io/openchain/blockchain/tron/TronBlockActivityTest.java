package io.openchain.blockchain.tron;

import static io.openchain.blockchain.TestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.StubHttpServer;
import io.openchain.blockchain.StubHttpServer.Response;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.core.model.*;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TronGridProvider#getBlockActivity} against fixtures shaped like java-tron / TronGrid responses
 * (https://developers.tron.network/reference/getblockbynum and
 * https://developers.tron.network/reference/gettransactioninfobyblocknum; hex addresses, no visible=true). Synthetic
 * values, no live node. Block 80000015: 2.5 TRX TransferContract (SUCCESS), 9 TRX TransferContract (OUT_OF_ENERGY),
 * USDT transfer + unlimited Approval, a zero-amount TransferContract, and Tether addBlackList (AddedBlackList log).
 */
class TronBlockActivityTest {
    static final String USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    static final String OWN = TronAddress.fromHex("41" + "11".repeat(20)), TO = TronAddress.fromHex("41" + "22".repeat(20));
    static final String FZ = TronAddress.fromHex("41" + "44".repeat(20));
    static final String BID = "0000000004c4b40f" + "d1".repeat(24);

    StubHttpServer server;
    TronGridProvider provider;

    static String fixture(String name) {
        try (InputStream in = TronBlockActivityTest.class.getResourceAsStream("/fixtures/tron/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static String tx(int i) { return "%02x".formatted(i).repeat(32); }

    @BeforeEach void start() throws Exception {
        server = new StubHttpServer()
                .route("/wallet/getblockbynum", r -> r.body().contains("80000015") ? Response.ok(fixture("getblockbynum.json")) : Response.ok("{}"))
                .route("/wallet/gettransactioninfobyblocknum", r -> Response.ok(fixture("gettransactioninfobyblocknum.json")));
        HttpJsonTransport t = new HttpJsonTransport(endpoint("trongrid", server.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
        provider = new TronGridProvider("tron-1", t, new DefaultTokenRegistry(), TronConfig.defaults());
    }

    @AfterEach void stop() { server.close(); }

    @Test void decodesTrxTransfersApprovalsAndTetherFreeze() {
        BlockActivity a = provider.getBlockActivity(80000015, 80000015);

        assertThat(a.nativeTransfers()).as("failed and zero-amount TransferContract are not flows").singleElement().satisfies(n -> {
            assertThat(n.hash()).isEqualTo(tx(1));
            assertThat(n.from().value()).isEqualTo(OWN);
            assertThat(n.to().value()).isEqualTo(TO);
            assertThat(n.value()).isEqualTo(BigInteger.valueOf(2_500_000));
            assertThat(n.fee()).isEqualTo(BigInteger.valueOf(1_100_000));
            assertThat(n.blockHash()).isEqualTo(BID);
            assertThat(n.timestamp()).isEqualTo(Instant.ofEpochMilli(1735689600000L));
        });
        assertThat(a.tokenTransfers()).singleElement().satisfies(t -> {
            assertThat(t.amount()).isEqualByComparingTo(new BigDecimal("250.000123"));
            assertThat(t.token().contract()).isEqualTo(USDT);
            assertThat(t.eventId()).isEqualTo("tron:" + tx(3) + ":0");
        });
        assertThat(a.approvals()).singleElement().satisfies(ap -> {
            assertThat(ap.owner().value()).isEqualTo(OWN);
            assertThat(ap.spender().value()).isEqualTo(TO);
            assertThat(ap.isUnlimited()).isTrue();
            assertThat(ap.token().symbol()).isEqualTo("USDT");
            assertThat(ap.eventId()).isEqualTo("tron:" + tx(3) + ":1");
        });
        assertThat(a.freezes()).singleElement().satisfies(z -> {
            assertThat(z.target().value()).isEqualTo(FZ);
            assertThat(z.token().contract()).isEqualTo(USDT);
            assertThat(z.issuer()).isEqualTo("tether");
            assertThat(z.action()).isEqualTo(TokenFreeze.Action.FREEZE);
            assertThat(z.eventId()).isEqualTo("tron:" + tx(5) + ":0");
        });
        assertThat(a.blockHashes()).allMatch(BID::equals);
    }

    @Test void missingBlockIsRetryable() {
        assertThatThrownBy(() -> provider.getBlockActivity(99999999, 99999999))
                .isInstanceOf(io.openchain.core.provider.ProviderException.class)
                .satisfies(e -> assertThat(((io.openchain.core.provider.ProviderException) e).retryable()).isTrue());
    }
}
