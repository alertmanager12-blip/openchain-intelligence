package io.openchain.blockchain.evm;

import static io.openchain.blockchain.TestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.StubHttpServer;
import io.openchain.blockchain.StubHttpServer.Response;
import io.openchain.blockchain.backfill.RangeTooLargeException;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.token.StablecoinFreezeSources;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.blockchain.transport.Json;
import io.openchain.blockchain.transport.JsonRpcClient;
import io.openchain.blockchain.transport.JsonRpcException;
import io.openchain.core.model.*;
import io.openchain.core.provider.ProviderException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * EVM adapter: contract admin changes (OwnershipTransferred indexed and non-indexed, EIP-1967 Upgraded / AdminChanged),
 * {@code eth_getCode} contract lookups with caching, and the freeze backfill log scan. JSON-RPC payloads follow the
 * Ethereum JSON-RPC specification (eth_getBlockByNumber, eth_getBlockReceipts, eth_getLogs, eth_getCode); synthetic values.
 */
class EvmAdminChangeAndBackfillTest {
    static final String USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7", USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";
    static final String OLD = "0x1111111111111111111111111111111111111111", NEW = "0x2222222222222222222222222222222222222222";
    static final String IMPL = "0x3333333333333333333333333333333333333333", OTHER = "0x7777777777777777777777777777777777777777";
    static final String ZERO = "0x0000000000000000000000000000000000000000";
    static final String BH = "0x" + "b2".repeat(32), TX = "0x" + "a1".repeat(32);

    StubHttpServer server;
    final Map<String, Integer> calls = new ConcurrentHashMap<>();
    final List<JsonNode> params = new CopyOnWriteArrayList<>();

    static String word(String addr) { return "0".repeat(24) + addr.substring(2); }
    static String topic(String addr) { return "0x" + word(addr); }

    static String log(String address, int index, String data, String... topics) {
        StringBuilder t = new StringBuilder();
        for (String x : topics) t.append(t.isEmpty() ? "" : ",").append('"').append(x).append('"');
        return """
                {"address":"%s","topics":[%s],"data":"%s","blockNumber":"0x20","blockHash":"%s","transactionHash":"%s",
                 "transactionIndex":"0x0","logIndex":"0x%x","removed":false}""".formatted(address, t, data, BH, TX, index);
    }

    static final String LOGS = "[" + String.join(",",
            log(USDT, 0, "0x", EvmCodec.OWNERSHIP_TRANSFERRED_TOPIC, topic(OLD), topic(NEW)),           // OpenZeppelin (indexed)
            log(USDC, 1, "0x" + word(OLD) + word(NEW), EvmCodec.OWNERSHIP_TRANSFERRED_TOPIC),            // Circle Ownable (data)
            log(USDC, 2, "0x", EvmCodec.UPGRADED_TOPIC, topic(IMPL)),                                    // EIP-1967
            log(USDC, 3, "0x" + word(OLD) + word(NEW), EvmCodec.ADMIN_CHANGED_TOPIC),                    // EIP-1967 (data)
            log(OTHER, 4, "0x", EvmCodec.OWNERSHIP_TRANSFERRED_TOPIC, topic(NEW), topic(ZERO)),          // renounce, any contract
            log(OTHER, 5, "0x", EvmCodec.OWNERSHIP_TRANSFERRED_TOPIC, topic(NEW)),                       // malformed: ignored
            log(OTHER, 6, "0x" + "f".repeat(128), EvmCodec.ADMIN_CHANGED_TOPIC)) + "]";                  // not address words

    static String ok(String result) { return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}"; }

    static String err(int code, String msg) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + code + ",\"message\":\"" + msg + "\"}}";
    }

    EvmJsonRpcProvider provider(Function<JsonNode, String> handler) throws Exception {
        if (server != null) server.close();
        server = new StubHttpServer().route("/", req -> {
            JsonNode body = Json.MAPPER.readTree(req.body());
            String method = body.path("method").asString();
            calls.merge(method, 1, Integer::sum);
            params.add(body);
            return Response.ok(handler.apply(body));
        });
        HttpJsonTransport t = new HttpJsonTransport(endpoint("eth", server.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
        return new EvmJsonRpcProvider("eth", Network.ETHEREUM, new JsonRpcClient(t), new DefaultTokenRegistry(), EvmConfig.defaults(Network.ETHEREUM));
    }

    @AfterEach void stop() { if (server != null) server.close(); }

    static final String BLOCK = """
            {"number":"0x20","hash":"%s","parentHash":"0x%s","timestamp":"0x65000100","transactions":[]}"""
            .formatted(BH, "b1".repeat(32));

    private void assertAdminChanges(BlockActivity a) {
        assertThat(a.adminChanges()).hasSize(5);
        ContractAdminChange oz = a.adminChanges().get(0);
        assertThat(oz.contract().value()).isEqualTo(USDT);
        assertThat(oz.role()).isEqualTo(ContractAdminChange.ROLE_OWNER);
        assertThat(oz.previous().value()).isEqualTo(OLD);
        assertThat(oz.newValue().value()).isEqualTo(NEW);
        assertThat(oz.eventName()).isEqualTo("OwnershipTransferred");
        assertThat(oz.eventId()).isEqualTo("ethereum:" + TX + ":0");
        assertThat(oz.timestamp()).isEqualTo(Instant.ofEpochSecond(0x65000100L));
        ContractAdminChange circle = a.adminChanges().get(1);
        assertThat(circle.contract().value()).isEqualTo(USDC);
        assertThat(circle.previous().value()).isEqualTo(OLD);
        assertThat(circle.newValue().value()).isEqualTo(NEW);
        ContractAdminChange up = a.adminChanges().get(2);
        assertThat(up.role()).isEqualTo(ContractAdminChange.ROLE_IMPLEMENTATION);
        assertThat(up.previous()).isNull();
        assertThat(up.newValue().value()).isEqualTo(IMPL);
        assertThat(up.eventName()).isEqualTo("Upgraded");
        ContractAdminChange admin = a.adminChanges().get(3);
        assertThat(admin.role()).isEqualTo(ContractAdminChange.ROLE_PROXY_ADMIN);
        assertThat(admin.eventName()).isEqualTo("AdminChanged");
        assertThat(admin.newValue().value()).isEqualTo(NEW);
        ContractAdminChange renounce = a.adminChanges().get(4);
        assertThat(renounce.contract().value()).isEqualTo(OTHER);
        assertThat(renounce.newValue().isZero()).isTrue();
        assertThat(a.blockHashes()).allMatch(BH::equals);
        assertThat(a.tokenTransfers()).isEmpty();
        assertThat(a.freezes()).isEmpty();
    }

    @Test void blockReceiptsPathDecodesAllAdminChangeLayouts() throws Exception {
        EvmJsonRpcProvider p = provider(b -> switch (b.path("method").asString()) {
            case "eth_getBlockByNumber" -> ok(BLOCK);
            case "eth_getBlockReceipts" -> ok("[{\"transactionHash\":\"" + TX + "\",\"status\":\"0x1\",\"blockHash\":\"" + BH + "\",\"logs\":" + LOGS + "}]");
            default -> err(-32602, "invalid params");
        });
        assertAdminChanges(p.getBlockActivity(0x20, 0x20));
    }

    @Test void getLogsFallbackAsksForTheAdminTopicsToo() throws Exception {
        EvmJsonRpcProvider p = provider(b -> switch (b.path("method").asString()) {
            case "eth_getBlockByNumber" -> ok(BLOCK);
            case "eth_getBlockReceipts" -> err(-32601, "the method eth_getBlockReceipts does not exist/is not available");
            case "eth_getLogs" -> ok(LOGS);
            default -> err(-32602, "invalid params");
        });
        assertAdminChanges(p.getBlockActivity(0x20, 0x20));
        JsonNode getLogs = params.stream().filter(x -> x.path("method").asString().equals("eth_getLogs")).findFirst().orElseThrow();
        assertThat(getLogs.path("params").get(0).path("topics").get(0).toString())
                .contains(EvmCodec.OWNERSHIP_TRANSFERRED_TOPIC, EvmCodec.UPGRADED_TOPIC, EvmCodec.ADMIN_CHANGED_TOPIC);
    }

    // ---- eth_getCode ------------------------------------------------------------------------------------------

    @Test void isContractUsesGetCodeAtTheEventBlockAndCachesAnswers() throws Exception {
        String contract = "0x000000000022d473030f116ddee9f6b43ac78ba3", eoa = "0x9999999999999999999999999999999999999999";
        EvmJsonRpcProvider p = provider(b -> {
            if (!b.path("method").asString().equals("eth_getCode")) return err(-32602, "invalid params");
            assertThat(b.path("params").get(1).asString()).isEqualTo("0x64");
            return ok(b.path("params").get(0).asString().equals(contract) ? "\"0x6080604052\"" : "\"0x\"");
        });
        assertThat(p.isContract(new Address(Network.ETHEREUM, contract), 100)).contains(true);
        assertThat(p.isContract(new Address(Network.ETHEREUM, contract.toUpperCase().replace("0X", "0x")), 100)).contains(true);
        assertThat(p.isContract(new Address(Network.ETHEREUM, eoa), 100)).contains(false);
        assertThat(p.isContract(new Address(Network.ETHEREUM, eoa), 100)).contains(false);
        assertThat(calls).containsEntry("eth_getCode", 2);
        assertThat(p.contractLookupStats().lookups()).isEqualTo(4);
        assertThat(p.contractLookupStats().hits()).isEqualTo(2);
        assertThat(p.contractLookupStats().remoteCalls()).isEqualTo(2);
    }

    @Test void prunedStateFallsBackToLatestAndNoCodeAnswersExpire() throws Exception {
        String a = "0x9999999999999999999999999999999999999999";
        List<String> tags = new ArrayList<>();
        EvmJsonRpcProvider p = provider(b -> {
            String tag = b.path("params").get(1).asString();
            tags.add(tag);
            if (tag.equals("0x64")) return err(-32000, "missing trie node 1234 (path ) state is not available");
            return ok("\"0x\"");
        });
        assertThat(p.isContract(new Address(Network.ETHEREUM, a), 100)).contains(false);
        assertThat(tags).containsExactly("0x64", "latest");
        tags.clear();
        assertThat(p.isContract(new Address(Network.ETHEREUM, a), 100 + 300)).contains(false); // within the TTL: cached
        assertThat(tags).isEmpty();
        assertThat(p.isContract(new Address(Network.ETHEREUM, a), 100 + 301)).contains(false); // TTL elapsed: asked again
        assertThat(tags).containsExactly("0x191");
    }

    @Test void transientGetCodeErrorPropagatesAndIsNotCached() throws Exception {
        EvmJsonRpcProvider p = provider(b -> err(-32005, "daily request count exceeded, request rate limited"));
        Address a = new Address(Network.ETHEREUM, OTHER);
        assertThatThrownBy(() -> p.isContract(a, 5)).isInstanceOf(ProviderException.class);
        assertThat(p.contractLookupStats().failures()).isEqualTo(1);
        assertThatThrownBy(() -> p.isContract(a, 5)).isInstanceOf(ProviderException.class);
        assertThat(calls).containsEntry("eth_getCode", 2);
    }

    // ---- freeze backfill scan -----------------------------------------------------------------------------------

    @Test void scanFreezesQueriesOnlyTheOfficialContractsAndTheirTopics() throws Exception {
        String frozen = "0x4444444444444444444444444444444444444444";
        String logs = "[" + String.join(",",
                log(USDT, 7, "0x" + word(frozen), EvmCodec.ADDED_BLACKLIST_TOPIC).replace("\"removed\":false", "\"removed\":false,\"blockTimestamp\":\"0x65000200\""),
                log(USDC, 8, "0x", EvmCodec.UNBLACKLISTED_TOPIC, topic(frozen))) + "]";
        EvmJsonRpcProvider p = provider(b -> switch (b.path("method").asString()) {
            case "eth_getLogs" -> ok(logs);
            case "eth_getBlockByHash" -> ok(BLOCK);
            default -> err(-32602, "invalid params");
        });
        List<TokenFreeze> found = p.scanFreezes(0x10, 0x30);
        JsonNode filter = params.stream().filter(x -> x.path("method").asString().equals("eth_getLogs")).findFirst().orElseThrow()
                .path("params").get(0);
        assertThat(filter.path("fromBlock").asString()).isEqualTo("0x10");
        assertThat(filter.path("toBlock").asString()).isEqualTo("0x30");
        assertThat(filter.path("address").toString()).contains(USDT, USDC).doesNotContain("0x3c499c"); // Ethereum sources only
        assertThat(filter.path("topics").get(0).toString()).contains(EvmCodec.ADDED_BLACKLIST_TOPIC, EvmCodec.REMOVED_BLACKLIST_TOPIC,
                EvmCodec.BLACKLISTED_TOPIC, EvmCodec.UNBLACKLISTED_TOPIC).doesNotContain(EvmCodec.TRANSFER_TOPIC);
        assertThat(found).hasSize(2);
        assertThat(found.get(0).action()).isEqualTo(TokenFreeze.Action.FREEZE);
        assertThat(found.get(0).issuer()).isEqualTo("tether");
        assertThat(found.get(0).timestamp()).isEqualTo(Instant.ofEpochSecond(0x65000200L)); // blockTimestamp: no extra call
        assertThat(found.get(0).eventId()).isEqualTo("ethereum:" + TX + ":7"); // same id as the live indexer
        assertThat(found.get(1).action()).isEqualTo(TokenFreeze.Action.UNFREEZE);
        assertThat(found.get(1).target().value()).isEqualTo(frozen);
        assertThat(calls).containsEntry("eth_getBlockByHash", 1).containsEntry("eth_getLogs", 1);
    }

    @Test void tooManyResultsBecomesRangeTooLargeButRateLimitingDoesNot() throws Exception {
        EvmJsonRpcProvider p = provider(b -> err(-32005, "query returned more than 10000 results. Try with this block range [0x10, 0x20]."));
        assertThatThrownBy(() -> p.scanFreezes(0, 100_000)).isInstanceOf(RangeTooLargeException.class);
        assertThat(new JsonRpcException("x", "eth_getLogs", -32602, "Log response size exceeded. You can make eth_getLogs requests with up to a 2K block range", false).rangeTooLarge()).isTrue();
        assertThat(new JsonRpcException("x", "eth_getLogs", -32000, "exceed maximum block range: 5000", false).rangeTooLarge()).isTrue();
        assertThat(new JsonRpcException("x", "eth_getLogs", -32005, "daily request count exceeded, request rate limited", true).rangeTooLarge()).isFalse();
        assertThat(new JsonRpcException("x", "eth_getLogs", -32000, "header not found", false).rangeTooLarge()).isFalse();
        // public gateways observed on 2026-11-07 (dRPC free plan, 1RPC, Cloudflare)
        assertThat(new JsonRpcException("x", "eth_getLogs", 35, "ranges over 10000 blocks are not supported on free plan", false).rangeTooLarge()).isTrue();
        assertThat(new JsonRpcException("x", "eth_getLogs", -32602, "eth_getLogs is limited to 0 - 50 blocks range", false).rangeTooLarge()).isTrue();
        assertThat(new JsonRpcException("x", "eth_getLogs", -32047, "Invalid eth_getLogs request. 'fromBlock'-'toBlock' range too large. Max range: 800", false).rangeTooLarge()).isTrue();
    }

    @Test void balanceAtABlockUsesEthGetBalanceOrBalanceOfAtThatBlock() throws Exception {
        String holder = "0x0f2897ec295cb6d6776c10ad16a1d4546c25ec06";
        EvmJsonRpcProvider p = provider(b -> switch (b.path("method").asString()) {
            case "eth_getBalance" -> ok("\"0xde0b6b3a7640000\"");                                        // 1 ETH
            case "eth_call" -> ok("\"0x" + "0".repeat(56) + "05f5e100\"");                              // 100 USDT (6 decimals)
            default -> err(-32601, "method not found");
        });
        Address a = new Address(Network.ETHEREUM, holder);
        var registry = new DefaultTokenRegistry();
        assertThat(p.getBalanceAt(a, Token.nativeOf(Network.ETHEREUM), 0x1234)).contains(new java.math.BigInteger("1000000000000000000"));
        assertThat(params.getLast().path("params").get(0).asString()).isEqualTo(holder);
        assertThat(params.getLast().path("params").get(1).asString()).isEqualTo("0x1234");
        assertThat(p.getBalanceAt(a, registry.resolve(Network.ETHEREUM, USDT), 0x1234)).contains(java.math.BigInteger.valueOf(100_000_000));
        JsonNode call = params.getLast().path("params");
        assertThat(call.get(0).path("to").asString()).isEqualTo(USDT);
        assertThat(call.get(0).path("data").asString()).isEqualTo("0x70a08231" + "0".repeat(24) + holder.substring(2));
        assertThat(call.get(1).asString()).isEqualTo("0x1234");
        // pruned state / rate limit: the error propagates (the caller then treats the balance as unknown)
        EvmJsonRpcProvider pruned = provider(b -> err(-32000, "missing trie node"));
        assertThatThrownBy(() -> pruned.getBalanceAt(a, Token.nativeOf(Network.ETHEREUM), 5)).isInstanceOf(ProviderException.class);
    }

    @Test void freezeSourcesCanBeEmptyForANetwork() throws Exception {
        EvmJsonRpcProvider p = provider(b -> err(-32602, "invalid params"));
        HttpJsonTransport t = new HttpJsonTransport(endpoint("bsc", server.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
        EvmJsonRpcProvider bsc = new EvmJsonRpcProvider("bsc", Network.BSC, new JsonRpcClient(t), new DefaultTokenRegistry(),
                EvmConfig.defaults(Network.BSC), StablecoinFreezeSources.defaults());
        assertThat(bsc.scanFreezes(1, 10)).isEmpty(); // no issuer blacklist on BSC: no request at all
        assertThat(calls).doesNotContainKey("eth_getLogs");
        assertThat(p).isNotNull();
    }
}
