package io.openchain.blockchain.evm;

import static io.openchain.blockchain.TestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.StubHttpServer;
import io.openchain.blockchain.StubHttpServer.Response;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.blockchain.transport.Json;
import io.openchain.blockchain.transport.JsonRpcClient;
import io.openchain.blockchain.transport.JsonRpcException;
import io.openchain.core.model.*;
import io.openchain.core.provider.ProviderException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@link EvmJsonRpcProvider#getBlockActivity} against recorded-shape fixtures (src/test/resources/fixtures/evm). No live
 * node is available in CI: the fixtures follow the object layouts of the Ethereum JSON-RPC specification
 * (https://ethereum.org/en/developers/docs/apis/json-rpc/#eth_getblockbynumber, #eth_gettransactionreceipt,
 * #eth_getlogs; execution-apis https://github.com/ethereum/execution-apis eth_getBlockReceipts) with synthetic values.
 * Block 0x10 contains: 1.5 ETH transfer (ok), 2 ETH transfer (status 0x0), zero-value USDT call (Transfer + unlimited
 * Approval + an Approval and an AddedBlackList emitted by a non-issuer contract), contract creation carrying 0.5 ETH
 * (to = null), Tether addBlackList (non-indexed AddedBlackList) and Circle blacklist (indexed Blacklisted).
 */
class EvmBlockActivityTest {
    static final String F = "0x1111111111111111111111111111111111111111", T = "0x2222222222222222222222222222222222222222";
    static final String FRZ = "0x4444444444444444444444444444444444444444", FRZ2 = "0x5555555555555555555555555555555555555555";
    static final String NEWC = "0x6666666666666666666666666666666666666666";
    static final String BH = "0x" + "b1".repeat(32);

    StubHttpServer server;
    boolean blockReceiptsSupported = true;
    final Map<String, Integer> calls = new ConcurrentHashMap<>();
    JsonNode receipts;

    static String fixture(String name) {
        try (InputStream in = EvmBlockActivityTest.class.getResourceAsStream("/fixtures/evm/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static String h(int i) { return "0x" + "%02x".formatted(i).repeat(32); }

    Response rpc(StubHttpServer.Request req) {
        JsonNode body = Json.MAPPER.readTree(req.body());
        String method = body.path("method").asString();
        calls.merge(method, 1, Integer::sum);
        JsonNode params = body.path("params");
        return switch (method) {
            case "eth_getBlockByNumber" -> {
                assertThat(params.get(0).asString()).isEqualTo("0x10");
                assertThat(params.get(1).asBoolean()).as("full transactions").isTrue();
                yield Response.ok(fixture("eth_getBlockByNumber_full.json"));
            }
            case "eth_getBlockReceipts" -> Response.ok(blockReceiptsSupported ? fixture("eth_getBlockReceipts.json")
                    : fixture("error_method_not_found.json"));
            case "eth_getLogs" -> {
                assertThat(params.get(0).path("blockHash").asString()).isEqualTo(BH);
                JsonNode topic0 = params.get(0).path("topics").get(0);
                assertThat(topic0.isArray()).isTrue();
                assertThat(topic0.toString()).contains(EvmCodec.TRANSFER_TOPIC, EvmCodec.APPROVAL_TOPIC,
                        EvmCodec.ADDED_BLACKLIST_TOPIC, EvmCodec.BLACKLISTED_TOPIC);
                yield Response.ok(fixture("eth_getLogs_blockHash.json"));
            }
            case "eth_getTransactionReceipt" -> {
                String hash = params.get(0).asString();
                for (JsonNode rc : receipts.path("result")) {
                    if (rc.path("transactionHash").asString().equals(hash)) {
                        yield Response.ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + rc + "}");
                    }
                }
                yield Response.ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
            }
            default -> Response.ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"invalid params\"}}");
        };
    }

    EvmJsonRpcProvider provider() {
        HttpJsonTransport t = new HttpJsonTransport(endpoint("eth", server.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
        return new EvmJsonRpcProvider("eth", Network.ETHEREUM, new JsonRpcClient(t), new DefaultTokenRegistry(), EvmConfig.defaults(Network.ETHEREUM));
    }

    @BeforeEach void start() throws Exception {
        receipts = Json.MAPPER.readTree(fixture("eth_getBlockReceipts.json"));
        server = new StubHttpServer().route("/", this::rpc);
    }

    @AfterEach void stop() { server.close(); }

    private void assertActivity(BlockActivity a) {
        // native: only successful, non-zero value; contract creation goes to the created contract
        assertThat(a.nativeTransfers()).extracting(ChainTransaction::hash).containsExactly(h(1), h(4));
        ChainTransaction eth = a.nativeTransfers().getFirst();
        assertThat(eth.from().value()).isEqualTo(F);
        assertThat(eth.to().value()).isEqualTo(T);
        assertThat(eth.value()).isEqualTo(new BigInteger("1500000000000000000"));
        assertThat(eth.success()).isTrue();
        assertThat(eth.blockHash()).isEqualTo(BH);
        assertThat(eth.timestamp()).isEqualTo(Instant.ofEpochSecond(0x65000000L));
        assertThat(eth.fee()).isEqualTo(BigInteger.valueOf(0x5208L * 0x77359400L));
        ChainTransaction creation = a.nativeTransfers().get(1);
        assertThat(creation.to().value()).isEqualTo(NEWC);
        assertThat(creation.value()).isEqualTo(new BigInteger("500000000000000000"));
        assertThat(creation.contractInteraction()).isTrue();

        assertThat(a.tokenTransfers()).singleElement().satisfies(t -> {
            assertThat(t.token().symbol()).isEqualTo("USDT");
            assertThat(t.amount()).isEqualByComparingTo(new BigDecimal("1234.567891"));
            assertThat(t.eventId()).isEqualTo("ethereum:" + h(3) + ":0");
        });

        // approvals of all tokens are decoded (the indexer filters unknown tokens)
        assertThat(a.approvals()).hasSize(2);
        TokenApproval usdt = a.approvals().getFirst();
        assertThat(usdt.owner().value()).isEqualTo(F);
        assertThat(usdt.spender().value()).isEqualTo(T);
        assertThat(usdt.token().symbol()).isEqualTo("USDT");
        assertThat(usdt.isUnlimited()).isTrue();
        assertThat(usdt.eventId()).isEqualTo("ethereum:" + h(3) + ":1");
        assertThat(a.approvals().get(1).token().isUnverified()).isTrue();

        // freezes only from the issuers' official contracts; Tether's address is in data, Circle's in topic 1
        assertThat(a.freezes()).hasSize(2);
        TokenFreeze tether = a.freezes().getFirst();
        assertThat(tether.target().value()).isEqualTo(FRZ);
        assertThat(tether.issuer()).isEqualTo("tether");
        assertThat(tether.token().symbol()).isEqualTo("USDT");
        assertThat(tether.action()).isEqualTo(TokenFreeze.Action.FREEZE);
        assertThat(tether.eventName()).isEqualTo("AddedBlackList");
        assertThat(tether.eventId()).isEqualTo("ethereum:" + h(5) + ":4");
        TokenFreeze circle = a.freezes().get(1);
        assertThat(circle.target().value()).isEqualTo(FRZ2);
        assertThat(circle.issuer()).isEqualTo("circle");
        assertThat(circle.token().symbol()).isEqualTo("USDC");
        assertThat(a.blockHashes()).allMatch(BH::equals);
    }

    @Test void blockReceiptsPathDecodesNativeApprovalsAndFreezes() {
        EvmJsonRpcProvider p = provider();
        assertActivity(p.getBlockActivity(16, 16));
        assertThat(p.blockReceiptsSupported()).contains(true);
        assertThat(calls).containsEntry("eth_getBlockReceipts", 1).doesNotContainKeys("eth_getTransactionReceipt", "eth_getLogs");
    }

    @Test void unsupportedBlockReceiptsFallsBackToLogsAndPerTransactionReceiptsOnce() {
        blockReceiptsSupported = false;
        EvmJsonRpcProvider p = provider();
        assertActivity(p.getBlockActivity(16, 16));
        assertThat(p.blockReceiptsSupported()).contains(false);
        // receipts only for the 3 transactions with value (1.5 ETH, reverted 2 ETH, creation), not for zero-value calls
        assertThat(calls).containsEntry("eth_getTransactionReceipt", 3).containsEntry("eth_getLogs", 1);
        assertActivity(p.getBlockActivity(16, 16));
        assertThat(calls).as("unsupported method is not probed again").containsEntry("eth_getBlockReceipts", 1);
    }

    @Test void methodNotFoundIsClassifiedAsUnsupportedButOtherErrorsAreNot() {
        assertThat(new JsonRpcException("x", "m", -32601, "the method does not exist", false).methodUnsupported()).isTrue();
        assertThat(new JsonRpcException("x", "m", -32004, "method not supported", false).methodUnsupported()).isTrue();
        assertThat(new JsonRpcException("x", "m", -32000, "the method eth_getBlockReceipts does not exist/is not available", false).methodUnsupported()).isTrue();
        assertThat(new JsonRpcException("x", "m", -32602, "invalid params", false).methodUnsupported()).isFalse();
        assertThat(new JsonRpcException("x", "m", -32005, "limit exceeded", true).methodUnsupported()).isFalse();
        assertThat(new JsonRpcException("x", "m", -32000, "header not found", false).methodUnsupported()).isFalse();
    }

    @Test void transientBlockReceiptsErrorPropagatesWithoutDisablingTheMethod() throws Exception {
        try (StubHttpServer limited = new StubHttpServer().route("/", req -> {
            String method = Json.MAPPER.readTree(req.body()).path("method").asString();
            if (method.equals("eth_getBlockReceipts"))
                return Response.ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32005,\"message\":\"limit exceeded\"}}");
            return rpc(req);
        })) {
            HttpJsonTransport t = new HttpJsonTransport(endpoint("eth", limited.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
            EvmJsonRpcProvider p = new EvmJsonRpcProvider("eth", Network.ETHEREUM, new JsonRpcClient(t), new DefaultTokenRegistry(),
                    EvmConfig.defaults(Network.ETHEREUM));
            assertThatThrownBy(() -> p.getBlockActivity(16, 16)).isInstanceOf(ProviderException.class)
                    .satisfies(e -> assertThat(((ProviderException) e).retryable()).isTrue());
            assertThat(p.blockReceiptsSupported()).isEmpty();
        }
    }

    @Test void freezeLogsOfOtherContractsOrUnexpectedShapesAreIgnored() {
        LogDecoder d = new LogDecoder(Network.ETHEREUM, new DefaultTokenRegistry(),
                io.openchain.blockchain.token.StablecoinFreezeSources.defaults(), hex -> new Address(Network.ETHEREUM, "0x" + hex), c -> c);
        LogDecoder.Decoded out = new LogDecoder.Decoded();
        String usdt = "0xdac17f958d2ee523a2206206994597c13d831ec7";
        String addrWord = "0x" + "0".repeat(24) + FRZ.substring(2);
        d.decode("0x9999999999999999999999999999999999999999", List.of(EvmCodec.ADDED_BLACKLIST_TOPIC), addrWord, h(1), 0, 1, BH, Instant.EPOCH, out);
        d.decode(usdt, List.of(EvmCodec.BLACKLISTED_TOPIC, addrWord), "0x", h(1), 1, 1, BH, Instant.EPOCH, out); // Circle event on Tether contract
        d.decode(usdt, List.of(EvmCodec.ADDED_BLACKLIST_TOPIC), "0x" + "f".repeat(64), h(1), 2, 1, BH, Instant.EPOCH, out); // not an address word
        d.decode(usdt, List.of(EvmCodec.APPROVAL_TOPIC, addrWord, addrWord, addrWord), "0x", h(1), 3, 1, BH, Instant.EPOCH, out); // ERC-721
        assertThat(out.freezes).isEmpty();
        assertThat(out.approvals).isEmpty();
        d.decode(usdt.toUpperCase().replace("0X", "0x"), List.of(EvmCodec.REMOVED_BLACKLIST_TOPIC), addrWord, h(1), 4, 1, BH, Instant.EPOCH, out);
        assertThat(out.freezes).singleElement().satisfies(z -> assertThat(z.action()).isEqualTo(TokenFreeze.Action.UNFREEZE));
    }
}
