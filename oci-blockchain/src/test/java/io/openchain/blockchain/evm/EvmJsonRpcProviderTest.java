package io.openchain.blockchain.evm;

import static io.openchain.blockchain.TestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.StubHttpServer;
import io.openchain.blockchain.StubHttpServer.Response;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.blockchain.transport.JsonRpcClient;
import io.openchain.blockchain.transport.Json;
import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider.TransactionReceipt;
import io.openchain.core.provider.ProviderException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class EvmJsonRpcProviderTest {
    static final String USDT = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
    static final String UNKNOWN = "0x9999999999999999999999999999999999999999";
    static final String FROM = "0x1111111111111111111111111111111111111111";
    static final String TO = "0x2222222222222222222222222222222222222222";
    static final String BLOCK_HASH = "0x" + "b".repeat(64);
    static final String TX = "0x" + "a".repeat(64);

    StubHttpServer server;
    boolean finalizedSupported = true;

    static String topic(String addr) { return "0x" + "0".repeat(24) + addr.substring(2).toLowerCase(); }
    static String word(BigInteger v) { return "0x" + String.format("%64s", v.toString(16)).replace(' ', '0'); }

    static String log(String contract, List<String> topics, String data, int logIndex) {
        return """
            {"address":"%s","topics":[%s],"data":"%s","blockNumber":"0x10","blockHash":"%s",
             "transactionHash":"%s","logIndex":"%s","removed":false}"""
                .formatted(contract, String.join(",", topics.stream().map(t -> "\"" + t + "\"").toList()), data, BLOCK_HASH, TX,
                        "0x" + Integer.toHexString(logIndex));
    }

    static final String LOGS = "[" + String.join(",",
            log(USDT, List.of(EvmCodec.TRANSFER_TOPIC, topic(FROM), topic(TO)), word(new BigInteger("1234567891")), 3),
            log(UNKNOWN, List.of(EvmCodec.TRANSFER_TOPIC, topic(FROM), topic(TO)), word(BigInteger.TEN.pow(20)), 4),
            // ERC721 Transfer: 4 topics (tokenId indexed) -> must be ignored
            log(UNKNOWN, List.of(EvmCodec.TRANSFER_TOPIC, topic(FROM), topic(TO), word(BigInteger.ONE)), "0x", 5)) + "]";

    Response rpc(StubHttpServer.Request req) {
        JsonNode body = Json.MAPPER.readTree(req.body());
        String method = body.path("method").asString();
        JsonNode params = body.path("params");
        String result = switch (method) {
            case "eth_blockNumber" -> "\"0x64\"";
            case "eth_getBlockByNumber" -> {
                String tag = params.get(0).asString();
                if (tag.equals("finalized")) {
                    if (!finalizedSupported) yield null;
                    yield "{\"number\":\"0x50\",\"hash\":\"0xf\",\"parentHash\":\"0xe\",\"timestamp\":\"0x1\"}";
                }
                yield tag.equals("0x10") ? "{\"number\":\"0x10\",\"hash\":\"" + BLOCK_HASH + "\",\"parentHash\":\"0x" + "c".repeat(64)
                        + "\",\"timestamp\":\"0x65000000\"}" : "null";
            }
            case "eth_getBlockByHash" -> "{\"number\":\"0x10\",\"hash\":\"" + BLOCK_HASH + "\",\"parentHash\":\"0xc\",\"timestamp\":\"0x65000000\"}";
            case "eth_getLogs" -> {
                assertThat(params.get(0).path("topics").get(0).asString()).isEqualTo(EvmCodec.TRANSFER_TOPIC);
                yield LOGS;
            }
            case "eth_getTransactionByHash" -> """
                {"hash":"%s","blockNumber":"0x10","blockHash":"%s","from":"%s","to":"%s","value":"0x0",
                 "gasPrice":"0x3b9aca00","input":"0xa9059cbb000000"}""".formatted(TX, BLOCK_HASH, FROM, USDT);
            case "eth_getTransactionReceipt" -> """
                {"status":"0x1","gasUsed":"0x5208","effectiveGasPrice":"0x77359400","logs":%s}""".formatted(LOGS);
            case "eth_getBalance" -> "\"0xde0b6b3a7640000\"";
            case "eth_call" -> params.get(0).path("to").asString().equals(USDT.toLowerCase()) ? "\"" + word(BigInteger.valueOf(5_000_000)) + "\"" : "\"0x\"";
            default -> null;
        };
        if (result == null) {
            return Response.ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"invalid params\"}}");
        }
        return Response.ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}");
    }

    EvmJsonRpcProvider provider(String url, String id) {
        HttpJsonTransport t = new HttpJsonTransport(endpoint(id, url, fastResilience(1, 50)), Clock.systemUTC(), d -> {});
        return new EvmJsonRpcProvider(id, Network.ETHEREUM, new JsonRpcClient(t), new DefaultTokenRegistry(), EvmConfig.defaults(Network.ETHEREUM));
    }

    @BeforeEach void start() throws Exception { server = new StubHttpServer().route("/", this::rpc); }
    @AfterEach void stop() { server.close(); }

    @Test void decodesErc20TransfersWithExactAmountsAndFlagsUnknownTokens() {
        List<TokenTransfer> ts = provider(server.url(), "eth-1").getTokenTransfers(16, 16);
        assertThat(ts).hasSize(2);
        TokenTransfer usdt = ts.get(0);
        assertThat(usdt.token().symbol()).isEqualTo("USDT");
        assertThat(usdt.token().decimals()).isEqualTo(6);
        assertThat(usdt.amount()).isEqualByComparingTo(new BigDecimal("1234.567891"));
        assertThat(usdt.from().value()).isEqualTo(FROM);
        assertThat(usdt.to().value()).isEqualTo(TO);
        assertThat(usdt.logIndex()).isEqualTo(3);
        assertThat(usdt.timestamp()).isEqualTo(Instant.ofEpochSecond(0x65000000L));
        assertThat(usdt.eventId()).isEqualTo("ethereum:" + TX + ":3");

        TokenTransfer unknown = ts.get(1);
        assertThat(unknown.token().symbol()).isEqualTo(TokenRegistry.UNKNOWN_SYMBOL);
        assertThat(unknown.token().decimals()).isZero();
        assertThat(unknown.rawAmount()).isEqualTo(BigInteger.TEN.pow(20));
        assertThat(new DefaultTokenRegistry().isKnown(unknown.token())).isFalse();
    }

    @Test void readsBlockTransactionAndReceipt() {
        EvmJsonRpcProvider p = provider(server.url(), "eth-1");
        BlockRef b = p.getBlock(16).orElseThrow();
        assertThat(b.hash()).isEqualTo(BLOCK_HASH);
        assertThat(p.getBlock(17)).isEmpty();

        TransactionReceipt r = p.getTransactionReceipt(TX).orElseThrow();
        assertThat(r.success()).isTrue();
        assertThat(r.transaction().fee()).isEqualTo(BigInteger.valueOf(0x5208L).multiply(BigInteger.valueOf(0x77359400L)));
        assertThat(r.transaction().methodSelector()).isEqualTo("0xa9059cbb");
        assertThat(r.transaction().contractInteraction()).isTrue();
        assertThat(r.transfers()).hasSize(2);
    }

    @Test void balancesIncludeNativeAndKnownTokens() {
        List<Balance> bs = provider(server.url(), "eth-1").getAddressBalance(new Address(Network.ETHEREUM, FROM));
        assertThat(bs).extracting(bl -> bl.token().symbol()).containsExactly("ETH", "USDT");
        assertThat(bs.get(0).rawAmount()).isEqualTo(new BigInteger("1000000000000000000"));
        assertThat(bs.get(1).rawAmount()).isEqualTo(BigInteger.valueOf(5_000_000));
        assertThat(bs.get(0).atBlock()).isEqualTo(100);
    }

    @Test void finalizedTagWithFallbackToHeadConfirmations() {
        NetworkStatus s = provider(server.url(), "eth-1").getNetworkStatus();
        assertThat(s.healthy()).isTrue();
        assertThat(s.headBlock()).isEqualTo(100);
        assertThat(s.finalizedBlock()).isEqualTo(0x50);
        finalizedSupported = false;
        assertThat(provider(server.url(), "eth-1").getNetworkStatus().finalizedBlock()).isEqualTo(100 - 12);
    }

    @Test void unhealthyStatusWhenEndpointDown() {
        NetworkStatus s = provider("http://127.0.0.1:1", "dead").getNetworkStatus();
        assertThat(s.healthy()).isFalse();
    }

    @Test void addressHistoryIsExplicitlyUnsupported() {
        assertThatThrownBy(() -> provider(server.url(), "eth-1").getAddressTransactions(new Address(Network.ETHEREUM, FROM), null, 10))
                .isInstanceOf(ProviderException.class).satisfies(e -> assertThat(((ProviderException) e).retryable()).isFalse());
    }

    @Test void failoverToSecondRpcSourceOnServerErrors() throws Exception {
        // failover lives in the transport (OCI_CHAINS_<NETWORK>_FALLBACK_URLS): the provider keeps all its capabilities
        try (StubHttpServer broken = new StubHttpServer().route("/", r -> Response.status(502))) {
            HttpJsonTransport t = new HttpJsonTransport(List.of(endpoint("eth-a", broken.url(), fastResilience(1, 50)),
                    endpoint("eth-b", server.url(), fastResilience(1, 50))), Clock.systemUTC(), d -> {});
            EvmJsonRpcProvider p = new EvmJsonRpcProvider("eth", Network.ETHEREUM, new JsonRpcClient(t), new DefaultTokenRegistry(),
                    EvmConfig.defaults(Network.ETHEREUM));
            assertThat(p.getTokenTransfers(16, 16)).hasSize(2);
            assertThat(t.endpoint().name()).isEqualTo("eth-b");
            int brokenHits = broken.hits.get();
            p.getBlock(16); // sticky: broken source not retried
            assertThat(broken.hits).hasValue(brokenHits);
        }
    }

    @Test void rejectsNonEvmNetwork() {
        Function<Network, EvmConfig> cfg = EvmConfig::defaults;
        assertThatThrownBy(() -> cfg.apply(Network.TRON)).isInstanceOf(IllegalArgumentException.class);
    }
}
