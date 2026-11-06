package io.openchain.blockchain.tron;

import static io.openchain.blockchain.TestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.StubHttpServer;
import io.openchain.blockchain.StubHttpServer.Response;
import io.openchain.blockchain.backfill.RangeTooLargeException;
import io.openchain.blockchain.evm.EvmCodec;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.core.model.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TRON adapter: admin changes in TVM logs (java-tron {@code gettransactioninfobyblocknum} layout: 20-byte hex addresses,
 * topics without 0x), {@code /wallet/getcontract} lookups (https://developers.tron.network/reference/wallet-getcontract:
 * empty object for a non-contract) and the freeze backfill over TronGrid {@code /v1/contracts/{address}/events}
 * (https://developers.tron.network/reference/get-events-by-contract-address). Synthetic values, no live node.
 */
class TronAdminChangeAndBackfillTest {
    static final String USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    static final String USDT_HEX20 = TronAddress.toHex(USDT).substring(2);
    static final String OWNER = TronAddress.fromHex("41" + "11".repeat(20)), NEXT = TronAddress.fromHex("41" + "22".repeat(20));
    static final String FROZEN = TronAddress.fromHex("41" + "44".repeat(20));

    StubHttpServer server;
    TronGridProvider provider;
    final Map<String, Integer> hits = new ConcurrentHashMap<>();
    final List<String> eventQueries = new CopyOnWriteArrayList<>();
    volatile int eventPages = 1;

    static String w(String hex20) { return "0".repeat(24) + hex20; }
    static String noPrefix(String topic) { return EvmCodec.strip0x(topic); }

    static String block(long n) {
        return """
                {"blockID":"%016x%s","block_header":{"raw_data":{"number":%d,"parentHash":"%s","timestamp":%d}},"transactions":[]}"""
                .formatted(n, "d1".repeat(24), n, "ee".repeat(32), 1735689600000L + (n - 80000015L) * 3000);
    }

    static final String INFOS = """
            [{"id":"%s","fee":0,"blockNumber":80000015,"blockTimeStamp":1735689600000,"receipt":{"result":"SUCCESS"},
              "log":[{"address":"%s","topics":["%s","%s","%s"],"data":""},
                     {"address":"%s","topics":["%s","%s","%s"],"data":""}]}]""".formatted("09".repeat(32),
            USDT_HEX20, noPrefix(EvmCodec.OWNERSHIP_TRANSFERRED_TOPIC), w("11".repeat(20)), w("22".repeat(20)),
            "55".repeat(20), noPrefix(EvmCodec.OWNERSHIP_TRANSFERRED_TOPIC), w("22".repeat(20)), w("00".repeat(20)));

    @BeforeEach void start() throws Exception {
        server = new StubHttpServer()
                .route("/wallet/getblockbynum", r -> {
                    hits.merge("getblockbynum", 1, Integer::sum);
                    long n = Long.parseLong(r.body().replaceAll("\\D", ""));
                    return Response.ok(block(n));
                })
                .route("/wallet/gettransactioninfobyblocknum", r -> Response.ok(INFOS))
                .route("/wallet/getcontract", r -> {
                    hits.merge("getcontract", 1, Integer::sum);
                    assertThat(r.body()).contains("\"visible\":true");
                    return Response.ok(r.body().contains(USDT)
                            ? "{\"origin_address\":\"" + OWNER + "\",\"contract_address\":\"" + USDT + "\",\"code_hash\":\"ab\"}" : "{}");
                })
                .route("/v1/contracts/*", r -> {
                    eventQueries.add(r.pathAndQuery());
                    boolean freeze = r.pathAndQuery().contains("event_name=AddedBlackList");
                    boolean second = r.pathAndQuery().contains("fingerprint=");
                    if (!freeze) return Response.ok("{\"data\":[],\"success\":true,\"meta\":{\"page_size\":0}}");
                    String ev1 = """
                            {"block_number":80000016,"block_timestamp":1735689603000,"transaction_id":"%s","event_index":2,
                             "event_name":"AddedBlackList","contract_address":"%s","result":{"0":"0x%s","_user":"0x%s"},
                             "result_type":{"_user":"address"}}""".formatted("0a".repeat(32), USDT, "44".repeat(20), "44".repeat(20));
                    String outOfRange = """
                            {"block_number":80000099,"block_timestamp":1735689900000,"transaction_id":"%s","event_index":0,
                             "event_name":"AddedBlackList","contract_address":"%s","result":{"_user":"%s"},"result_type":{"_user":"address"}}"""
                            .formatted("0b".repeat(32), USDT, NEXT);
                    if (!second && eventPages > 1) {
                        return Response.ok("{\"data\":[" + ev1 + "],\"success\":true,\"meta\":{\"fingerprint\":\"fp-1\",\"page_size\":1}}");
                    }
                    String base58 = """
                            {"block_number":80000017,"block_timestamp":1735689606000,"transaction_id":"%s","event_index":0,
                             "event_name":"AddedBlackList","contract_address":"41%s","result":{"_user":"%s"},"result_type":{"_user":"address"}}"""
                            .formatted("0c".repeat(32), USDT_HEX20, NEXT);
                    String data = eventPages > 1 ? base58 + "," + outOfRange : ev1 + "," + base58 + "," + outOfRange;
                    return Response.ok("{\"data\":[" + data + "],\"success\":true,\"meta\":{\"page_size\":3}}");
                });
        HttpJsonTransport t = new HttpJsonTransport(endpoint("trongrid", server.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
        provider = new TronGridProvider("tron-1", t, new DefaultTokenRegistry(), TronConfig.defaults());
    }

    @AfterEach void stop() { server.close(); }

    @Test void decodesOwnershipTransfersOfAnyContractWithTheBase58ZeroAddressOnRenounce() {
        BlockActivity a = provider.getBlockActivity(80000015, 80000015);
        assertThat(a.adminChanges()).hasSize(2);
        ContractAdminChange usdt = a.adminChanges().getFirst();
        assertThat(usdt.contract().value()).isEqualTo(USDT);
        assertThat(usdt.previous().value()).isEqualTo(OWNER);
        assertThat(usdt.newValue().value()).isEqualTo(NEXT);
        assertThat(usdt.eventId()).isEqualTo("tron:" + "09".repeat(32) + ":0");
        assertThat(usdt.timestamp()).isEqualTo(Instant.ofEpochMilli(1735689600000L));
        ContractAdminChange renounce = a.adminChanges().get(1);
        assertThat(renounce.newValue().value()).isEqualTo(Address.TRON_ZERO);
        assertThat(renounce.newValue().isZero()).isTrue();
    }

    @Test void getcontractTellsContractsFromAccountsAndIsCached() {
        assertThat(provider.isContract(new Address(Network.TRON, USDT), 1)).contains(true);
        assertThat(provider.isContract(new Address(Network.TRON, OWNER), 1)).contains(false);
        assertThat(provider.isContract(new Address(Network.TRON, USDT), 2)).contains(true);
        assertThat(provider.isContract(new Address(Network.TRON, OWNER), 2)).contains(false);
        assertThat(hits).containsEntry("getcontract", 2);
        assertThat(provider.contractLookupStats().hits()).isEqualTo(2);
    }

    @Test void scanFreezesUsesTheContractEventsApiWithTimestampWindowAndBlockFilter() {
        List<TokenFreeze> found = provider.scanFreezes(80000015, 80000020);
        assertThat(found).extracting(z -> z.target().value()).containsExactly(FROZEN, NEXT); // block 80000099 is out of range
        TokenFreeze z = found.getFirst();
        assertThat(z.action()).isEqualTo(TokenFreeze.Action.FREEZE);
        assertThat(z.issuer()).isEqualTo("tether");
        assertThat(z.eventId()).isEqualTo("tron:" + "0a".repeat(32) + ":2");
        assertThat(z.blockNumber()).isEqualTo(80000016);
        assertThat(z.blockHash()).startsWith("0000000004c4b410");
        assertThat(z.token().symbol()).isEqualTo("USDT");
        assertThat(eventQueries).hasSize(2).allMatch(q -> q.startsWith("/v1/contracts/" + USDT + "/events?") && q.contains("only_confirmed=true")
                && q.contains("min_timestamp=" + (1735689600000L - 1)) && q.contains("max_timestamp=" + (1735689615000L + 999))
                && q.contains("limit=200") && q.contains("order_by=block_timestamp,asc"));
        assertThat(eventQueries).anyMatch(q -> q.contains("event_name=RemovedBlackList"));
    }

    @Test void scanFreezesFollowsFingerprintPaging() {
        eventPages = 2;
        List<TokenFreeze> found = provider.scanFreezes(80000015, 80000020);
        assertThat(found).extracting(z -> z.target().value()).containsExactly(FROZEN, NEXT);
        assertThat(eventQueries).anyMatch(q -> q.contains("fingerprint=fp-1"));
    }

    @Test void tooManyPagesIsReportedAsRangeTooLarge() throws Exception {
        try (StubHttpServer endless = new StubHttpServer()
                .route("/wallet/getblockbynum", r -> Response.ok(block(Long.parseLong(r.body().replaceAll("\\D", "")))))
                .route("/v1/contracts/*", r -> Response.ok("{\"data\":[],\"success\":true,\"meta\":{\"fingerprint\":\"again\"}}"))) {
            HttpJsonTransport t = new HttpJsonTransport(endpoint("trongrid", endless.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
            TronGridProvider p = new TronGridProvider("tron-2", t, new DefaultTokenRegistry(), TronConfig.defaults());
            assertThatThrownBy(() -> p.scanFreezes(80000015, 80000020)).isInstanceOf(RangeTooLargeException.class);
        }
    }
}
