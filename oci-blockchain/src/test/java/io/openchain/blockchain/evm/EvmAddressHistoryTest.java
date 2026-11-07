package io.openchain.blockchain.evm;

import static io.openchain.blockchain.TestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.StubHttpServer;
import io.openchain.blockchain.StubHttpServer.Response;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.blockchain.transport.Json;
import io.openchain.blockchain.transport.JsonRpcClient;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.history.AddressHistorySource.HistoryPage;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.provider.ProviderException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Address history over {@code eth_getLogs} by topic (ADR-0020): exact filters, ERC-20 decoding, dedup, backwards chunk
 * walking with the cursor, adaptive halving. Payloads follow the Ethereum JSON-RPC specification (eth_getLogs log
 * objects, error objects); synthetic values, no live node.
 */
class EvmAddressHistoryTest {
    static final String ADDR = "0x0f2897ec295cb6d6776c10ad16a1d4546c25ec06";
    static final String OTHER = "0x7777777777777777777777777777777777777777", SPENDER = "0x5555555555555555555555555555555555555555";
    static final String USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7", NFT = "0xbc4ca0eda7647a8ab7c2061c2e118a18a936f13d";
    static final String BH = "0x" + "b2".repeat(32), TX = "0x" + "a1".repeat(32);
    static final String ADDR_TOPIC = "0x" + "0".repeat(24) + ADDR.substring(2);
    static final String RANGE_ERROR = "query returned more than 10000 results. Try with this block range [0x10, 0x20].";

    StubHttpServer server;
    final Map<String, Integer> calls = new ConcurrentHashMap<>();
    final List<JsonNode> getLogs = new CopyOnWriteArrayList<>();

    static String topic(String addr) { return "0x" + "0".repeat(24) + addr.substring(2); }
    static String amount(long v) { return "0x" + "%064x".formatted(v); }

    static String log(String contract, int index, String data, boolean removed, String extra, String... topics) {
        String t = java.util.Arrays.stream(topics).map(x -> '"' + x + '"').collect(Collectors.joining(","));
        return """
                {"address":"%s","topics":[%s],"data":"%s","blockNumber":"0x150","blockHash":"%s","transactionHash":"%s",
                 "transactionIndex":"0x0","logIndex":"0x%x","removed":%s%s}""".formatted(contract, t, data, BH, TX, index, removed, extra);
    }

    static final String TS = ",\"blockTimestamp\":\"0x65000200\"";
    static final String SELF = log(USDT, 4, amount(9), false, TS, EvmCodec.TRANSFER_TOPIC, ADDR_TOPIC, ADDR_TOPIC);
    /** Answer to the sender/owner query. */
    static final String SENT = "[" + String.join(",",
            log(USDT, 0, amount(1_500_000), false, TS, EvmCodec.TRANSFER_TOPIC, ADDR_TOPIC, topic(OTHER)),
            log(USDT, 1, "0x" + "f".repeat(64), false, "", EvmCodec.APPROVAL_TOPIC, ADDR_TOPIC, topic(SPENDER)),  // no blockTimestamp
            log(NFT, 2, "0x", false, TS, EvmCodec.TRANSFER_TOPIC, ADDR_TOPIC, topic(OTHER), amount(42)),       // ERC-721: 4 topics
            log(USDT, 3, amount(5), true, TS, EvmCodec.TRANSFER_TOPIC, ADDR_TOPIC, topic(OTHER)),              // removed (reorg)
            SELF,
            log(USDT, 5, amount(3), false, TS, EvmCodec.APPROVAL_TOPIC, topic(OTHER), topic(ADDR))) + "]";     // another owner
    /** Answer to the recipient query (the self-transfer comes back here too). */
    static final String RECEIVED = "[" + String.join(",",
            log(USDT, 6, amount(2_000_000), false, TS, EvmCodec.TRANSFER_TOPIC, topic(OTHER), ADDR_TOPIC), SELF) + "]";

    static String ok(String result) { return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}"; }

    static String err(int code, String msg) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + code + ",\"message\":\"" + msg + "\"}}";
    }

    static long from(JsonNode req) { return EvmCodec.quantityLong(req.path("params").get(0).path("fromBlock").asString()); }
    static long to(JsonNode req) { return EvmCodec.quantityLong(req.path("params").get(0).path("toBlock").asString()); }
    static boolean senderQuery(JsonNode req) { return !req.path("params").get(0).path("topics").get(1).isNull(); }

    EvmJsonRpcProvider provider(EvmConfig config, Function<JsonNode, String> handler) throws Exception {
        server = new StubHttpServer().route("/", req -> {
            JsonNode body = Json.MAPPER.readTree(req.body());
            String method = body.path("method").asString();
            calls.merge(method, 1, Integer::sum);
            if (method.equals("eth_getLogs")) getLogs.add(body);
            return Response.ok(handler.apply(body));
        });
        HttpJsonTransport t = new HttpJsonTransport(endpoint("eth", server.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
        return new EvmJsonRpcProvider("eth", Network.ETHEREUM, new JsonRpcClient(t), new DefaultTokenRegistry(), config);
    }

    @AfterEach void stop() { if (server != null) server.close(); }

    static String emptyLogs(JsonNode b) {
        return b.path("method").asString().equals("eth_getLogs") ? ok("[]") : err(-32602, "invalid params");
    }

    @Test void describesItself() throws Exception {
        EvmJsonRpcProvider p = provider(EvmConfig.defaults(Network.ETHEREUM), EvmAddressHistoryTest::emptyLogs);
        assertThat(p.sourceId()).isEqualTo("eth_getLogs");
        assertThat(p.coverage().tokenTransfers()).isTrue();
        assertThat(p.coverage().approvals()).isTrue();
        assertThat(p.coverage().nativeTransfers()).isFalse();
        assertThat(p.coverage().internalTransfers()).isFalse();
        assertThat(EvmConfig.defaults(Network.ETHEREUM).historyChunkSize()).isEqualTo(10_000);
        assertThat(EvmConfig.defaults(Network.ETHEREUM).historyMinChunkSize()).isEqualTo(50);
        assertThatThrownBy(() -> EvmConfig.defaults(Network.ETHEREUM).withHistoryChunks(10, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void queriesBothTopicPositionsWithoutContractFilterAndDecodesErc20Only() throws Exception {
        EvmJsonRpcProvider p = provider(EvmConfig.defaults(Network.ETHEREUM), b -> switch (b.path("method").asString()) {
            case "eth_getLogs" -> ok(senderQuery(b) ? SENT : RECEIVED);
            case "eth_getBlockByHash" -> ok("{\"number\":\"0x150\",\"hash\":\"" + BH + "\",\"parentHash\":\"0x" + "b1".repeat(32)
                    + "\",\"timestamp\":\"0x65000200\",\"transactions\":[]}");
            default -> err(-32602, "invalid params");
        });
        HistoryPage page = p.fetch(new Address(Network.ETHEREUM, ADDR.toUpperCase().replace("0X", "0x")), 0x100, 0x1ff, null);

        assertThat(getLogs).hasSize(2);
        JsonNode sent = getLogs.get(0).path("params").get(0), received = getLogs.get(1).path("params").get(0);
        for (JsonNode f : List.of(sent, received)) {
            assertThat(f.path("fromBlock").asString()).isEqualTo("0x100");
            assertThat(f.path("toBlock").asString()).isEqualTo("0x1ff");
            assertThat(f.has("address")).as("no contract filter").isFalse();
        }
        assertThat(ADDR_TOPIC).hasSize(66).isEqualTo(ADDR_TOPIC.toLowerCase());
        assertThat(sent.path("topics").toString())
                .isEqualTo("[[\"" + EvmCodec.TRANSFER_TOPIC + "\",\"" + EvmCodec.APPROVAL_TOPIC + "\"],\"" + ADDR_TOPIC + "\"]");
        assertThat(received.path("topics").toString()).isEqualTo("[\"" + EvmCodec.TRANSFER_TOPIC + "\",null,\"" + ADDR_TOPIC + "\"]");

        Map<String, ChainEvent> byId = page.events().stream().collect(Collectors.toMap(ChainEvent::eventId, e -> e));
        // ERC-721 (2), removed (3) and the approval of another owner (5) are skipped; the self-transfer (4) appears once
        assertThat(byId.keySet()).containsExactlyInAnyOrder("ethereum:" + TX + ":0", "ethereum:" + TX + ":1",
                "ethereum:" + TX + ":4", "ethereum:" + TX + ":6");
        ChainEvent out = byId.get("ethereum:" + TX + ":0");
        assertThat(out.type()).isEqualTo(ChainEvent.EventType.TOKEN_TRANSFER);
        assertThat(out.field("transaction.from")).isEqualTo(ADDR);
        assertThat(out.field("transaction.to")).isEqualTo(OTHER);
        assertThat(out.field("transaction.token.symbol")).isEqualTo("USDT");
        assertThat(out.field("transaction.amount")).isEqualTo(new BigDecimal("1.500000"));
        assertThat(out.field("transaction.log_index")).isEqualTo(0L);
        assertThat(out.blockNumber()).isEqualTo(0x150);
        assertThat(out.blockHash()).isEqualTo(BH);
        assertThat(out.eventTime()).isEqualTo(Instant.ofEpochSecond(0x65000200L));
        assertThat(out.removed()).isFalse();
        ChainEvent in = byId.get("ethereum:" + TX + ":6");
        assertThat(in.field("transaction.from")).isEqualTo(OTHER);
        assertThat(in.field("transaction.to")).isEqualTo(ADDR);
        ChainEvent approval = byId.get("ethereum:" + TX + ":1");
        assertThat(approval.type()).isEqualTo(ChainEvent.EventType.APPROVAL);
        assertThat(approval.field("approval.owner")).isEqualTo(ADDR);
        assertThat(approval.field("approval.spender")).isEqualTo(SPENDER);
        assertThat(approval.field("approval.unlimited")).isEqualTo(true); // max uint256
        assertThat(approval.eventTime()).isEqualTo(Instant.ofEpochSecond(0x65000200L)); // via eth_getBlockByHash
        assertThat(calls).containsEntry("eth_getBlockByHash", 1);

        assertThat(page.coveredFromBlock()).isEqualTo(0x100);
        assertThat(page.coveredFromTime()).isNull();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.last()).isTrue();
        assertThat(page.queries()).isEqualTo(2);
    }

    @Test void walksBackwardsChunkByChunkUntilTheCursorIsNull() throws Exception {
        EvmJsonRpcProvider p = provider(EvmConfig.defaults(Network.ETHEREUM).withHistoryChunks(100, 25), EvmAddressHistoryTest::emptyLogs);
        Address a = new Address(Network.ETHEREUM, ADDR);
        HistoryPage p1 = p.fetch(a, 0, 249, null);
        assertThat(p1.coveredFromBlock()).isEqualTo(150);
        assertThat(p1.nextCursor()).isEqualTo("149:100");
        HistoryPage p2 = p.fetch(a, 0, 249, p1.nextCursor());
        assertThat(p2.coveredFromBlock()).isEqualTo(50);
        assertThat(p2.nextCursor()).isEqualTo("49:100");
        HistoryPage p3 = p.fetch(a, 0, 249, p2.nextCursor());
        assertThat(p3.coveredFromBlock()).isEqualTo(0);
        assertThat(p3.nextCursor()).isNull();
        assertThat(getLogs).extracting(r -> from(r) + ".." + to(r))
                .containsExactly("150..249", "150..249", "50..149", "50..149", "0..49", "0..49");
        assertThatThrownBy(() -> p.fetch(a, 0, 249, "garbage")).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> p.fetch(a, 0, 249, "300:100")).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> p.fetch(new Address(Network.BSC, ADDR), 0, 249, null)).isInstanceOf(ProviderException.class);
    }

    @Test void rangeErrorHalvesTheChunkWithinTheCallAndKeepsItInTheCursor() throws Exception {
        EvmJsonRpcProvider p = provider(EvmConfig.defaults(Network.ETHEREUM).withHistoryChunks(100, 25),
                b -> to(b) - from(b) + 1 > 30 ? err(-32005, RANGE_ERROR) : ok(senderQuery(b) ? "[]" : RECEIVED.replace("0x150", "0x3d0")));
        Address a = new Address(Network.ETHEREUM, ADDR);
        HistoryPage p1 = p.fetch(a, 0, 999, null);
        assertThat(p1.queries()).isEqualTo(4); // 100 (failed), 50 (failed), 25: two queries
        assertThat(p1.coveredFromBlock()).isEqualTo(975);
        assertThat(p1.nextCursor()).isEqualTo("974:25");
        assertThat(p1.events()).extracting(ChainEvent::eventId).containsExactlyInAnyOrder("ethereum:" + TX + ":6", "ethereum:" + TX + ":4");
        HistoryPage p2 = p.fetch(a, 0, 999, p1.nextCursor());
        assertThat(p2.queries()).isEqualTo(2);
        assertThat(p2.coveredFromBlock()).isEqualTo(950);
        assertThat(p2.nextCursor()).isEqualTo("949:25");
        assertThat(p2.events()).isEmpty(); // block 0x3d0 = 976 is outside 950..974: a log out of the chunk is dropped
    }

    @Test void stillTooLargeAtTheMinimumChunkIsANonRetryableError() throws Exception {
        EvmJsonRpcProvider p = provider(EvmConfig.defaults(Network.ETHEREUM).withHistoryChunks(100, 25), b -> err(-32005, RANGE_ERROR));
        assertThatThrownBy(() -> p.fetch(new Address(Network.ETHEREUM, ADDR), 0, 999, null))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("still too large at the minimum chunk size 25")
                .satisfies(e -> assertThat(((ProviderException) e).retryable()).isFalse());
        assertThat(getLogs).extracting(r -> to(r) - from(r) + 1).containsExactly(100L, 50L, 25L);
    }

    @Test void otherRpcErrorsPropagateWithoutHalving() throws Exception {
        EvmJsonRpcProvider p = provider(EvmConfig.defaults(Network.ETHEREUM), b -> err(-32005, "daily request count exceeded, request rate limited"));
        assertThatThrownBy(() -> p.fetch(new Address(Network.ETHEREUM, ADDR), 0, 999, null))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).retryable()).isTrue());
        assertThat(getLogs).hasSize(1);
    }
}
