package io.openchain.blockchain.tron;

import static io.openchain.blockchain.TestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.StubHttpServer;
import io.openchain.blockchain.StubHttpServer.Response;
import io.openchain.blockchain.evm.EvmCodec;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider.TransactionReceipt;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TronGridProviderTest {
    static final String USDT_HEX20 = "a614f803b6fd780986a42c78ec9c7f77e6ded13c";
    static final String FROM_HEX = "41" + "11".repeat(20);
    static final String TO_HEX = "41" + "22".repeat(20);
    static final String TX = "c".repeat(64);
    static final String BLOCK_ID = "0000000004c4b40f" + "d".repeat(48);

    StubHttpServer server;
    TronGridProvider provider;

    static String block(long n, String id) {
        return """
            {"blockID":"%s","block_header":{"raw_data":{"number":%d,"parentHash":"%s","timestamp":1735689600000}}}"""
                .formatted(id, n, "e".repeat(64));
    }

    static final String INFO = """
        {"id":"%s","fee":345000,"blockNumber":80000015,"blockTimeStamp":1735689600000,
         "receipt":{"result":"SUCCESS"},
         "log":[
           {"address":"%s","topics":["%s","000000000000000000000000%s","000000000000000000000000%s"],
            "data":"%s"},
           {"address":"%s","topics":["8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925","00","00"],"data":"00"}
         ]}""".formatted(TX, USDT_HEX20, EvmCodec.strip0x(EvmCodec.TRANSFER_TOPIC), FROM_HEX.substring(2), TO_HEX.substring(2),
            String.format("%64s", BigInteger.valueOf(250_000_123L).toString(16)).replace(' ', '0'), USDT_HEX20);

    @BeforeEach void start() throws Exception {
        server = new StubHttpServer()
                .route("/wallet/getnowblock", r -> Response.ok(block(80000020, "f".repeat(64))))
                .route("/walletsolidity/getnowblock", r -> Response.ok(block(80000001, "1".repeat(64))))
                .route("/wallet/getblockbynum", r -> r.body().contains("80000015") ? Response.ok(block(80000015, BLOCK_ID)) : Response.ok("{}"))
                .route("/wallet/gettransactionbyid", r -> Response.ok("""
                    {"txID":"%s","ret":[{"contractRet":"SUCCESS"}],"raw_data":{"contract":[{"type":"TriggerSmartContract",
                     "parameter":{"value":{"owner_address":"%s","contract_address":"41%s","data":"a9059cbb0000"}}}]}}"""
                        .formatted(TX, FROM_HEX, USDT_HEX20)))
                .route("/wallet/gettransactioninfobyid", r -> Response.ok(INFO))
                .route("/wallet/gettransactioninfobyblocknum", r -> Response.ok("[" + INFO + "]"))
                .route("/v1/accounts/*", r -> {
                    if (r.pathAndQuery().contains("/transactions/trc20")) {
                        assertThat(r.pathAndQuery()).contains("limit=50").contains("fingerprint=abc%2B1");
                        return Response.ok("""
                            {"data":[{"transaction_id":"%s","token_info":{"symbol":"USDT","address":"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t","decimals":6},
                              "block_timestamp":1735689600000,"from":"%s","to":"%s","type":"Transfer","value":"1000000"}],
                             "meta":{"fingerprint":"next-fp"}}""".formatted(TX, TronAddress.fromHex(FROM_HEX), TronAddress.fromHex(TO_HEX)));
                    }
                    return Response.ok("""
                        {"data":[{"balance":7000000,"trc20":[{"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t":"42000000"}]}]}""");
                });
        HttpJsonTransport t = new HttpJsonTransport(endpoint("trongrid", server.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
        provider = new TronGridProvider("tron-1", t, new DefaultTokenRegistry(), TronConfig.defaults());
    }

    @AfterEach void stop() { server.close(); }

    @Test void decodesTrc20TransfersFromBlockTransactionInfo() {
        List<TokenTransfer> ts = provider.getTokenTransfers(80000015, 80000015);
        assertThat(ts).hasSize(1); // Approval log ignored
        TokenTransfer t = ts.getFirst();
        assertThat(t.token().symbol()).isEqualTo("USDT");
        assertThat(t.token().contract()).isEqualTo("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
        assertThat(t.amount()).isEqualByComparingTo(new BigDecimal("250.000123"));
        assertThat(t.from().value()).isEqualTo(TronAddress.fromHex(FROM_HEX));
        assertThat(t.to().value()).isEqualTo(TronAddress.fromHex(TO_HEX));
        assertThat(t.blockHash()).isEqualTo(BLOCK_ID);
        assertThat(t.timestamp()).isEqualTo(Instant.ofEpochMilli(1735689600000L));
        assertThat(t.eventId()).isEqualTo("tron:" + TX + ":0");
    }

    @Test void transactionAndReceipt() {
        TransactionReceipt r = provider.getTransactionReceipt(TX).orElseThrow();
        assertThat(r.success()).isTrue();
        assertThat(r.transaction().from().value()).startsWith("T");
        assertThat(r.transaction().to().value()).isEqualTo("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
        assertThat(r.transaction().fee()).isEqualTo(BigInteger.valueOf(345000));
        assertThat(r.transaction().methodSelector()).isEqualTo("0xa9059cbb");
        assertThat(r.transaction().blockHash()).isEqualTo(BLOCK_ID);
        assertThat(r.transfers()).hasSize(1);
    }

    @Test void blocksAndStatus() {
        assertThat(provider.getBlock(80000015)).get().extracting(BlockRef::hash).isEqualTo(BLOCK_ID);
        assertThat(provider.getBlock(99999999)).isEmpty();
        NetworkStatus s = provider.getNetworkStatus();
        assertThat(s.healthy()).isTrue();
        assertThat(s.headBlock()).isEqualTo(80000020);
        assertThat(s.finalizedBlock()).isEqualTo(80000001);
    }

    @Test void trc20HistoryWithCursorAndBalances() {
        Address a = new Address(Network.TRON, TronAddress.fromHex(FROM_HEX));
        Page<TokenTransfer> page = provider.getAddressTokenTransfers(a, "abc+1", 50);
        assertThat(page.nextCursor()).isEqualTo("next-fp");
        assertThat(page.items().getFirst().amount()).isEqualByComparingTo("1");

        List<Balance> bs = provider.getAddressBalance(a);
        assertThat(bs).extracting(b -> b.token().symbol()).containsExactly("TRX", "USDT");
        assertThat(bs.get(0).rawAmount()).isEqualTo(BigInteger.valueOf(7_000_000));
        assertThat(bs.get(1).rawAmount()).isEqualTo(BigInteger.valueOf(42_000_000));
    }
}
