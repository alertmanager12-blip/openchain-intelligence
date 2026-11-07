package io.openchain.blockchain.tron;

import static io.openchain.blockchain.TestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.StubHttpServer;
import io.openchain.blockchain.StubHttpServer.Response;
import io.openchain.blockchain.indexer.TransferEventMapper;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.history.AddressHistorySource.HistoryPage;
import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.Network;
import io.openchain.core.provider.ProviderException;
import java.math.BigDecimal;
import java.math.BigInteger;
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
 * Address history over TronGrid account endpoints (ADR-0020): {@code /v1/accounts/{address}/transactions/trc20}
 * (https://developers.tron.network/reference/get-trc20-transaction-info-by-account-address) then
 * {@code /v1/accounts/{address}/transactions} (https://developers.tron.network/reference/get-transaction-info-by-account-address),
 * with {@code min_timestamp/max_timestamp} from {@code /wallet/getblockbynum} and fingerprint paging. Synthetic values.
 */
class TronAddressHistoryTest {
    static final String USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    static final String ADDR = TronAddress.fromHex("41" + "11".repeat(20)), OTHER = TronAddress.fromHex("41" + "22".repeat(20));
    static final String THIRD = TronAddress.fromHex("41" + "33".repeat(20));
    static final String TX1 = "01".repeat(32), TX2 = "02".repeat(32), TX3 = "03".repeat(32);
    static final long FROM = 80000015L, TO = 80000020L, T0 = 1735689600000L, T_TO = T0 + 15_000;
    static final String NATIVE_FP = "fp:n1/+=";

    StubHttpServer server;
    TronGridProvider provider;
    final Map<String, Integer> hits = new ConcurrentHashMap<>();
    final List<String> queries = new CopyOnWriteArrayList<>();

    static String block(long n) {
        return """
                {"blockID":"%016x%s","block_header":{"raw_data":{"number":%d,"parentHash":"%s","timestamp":%d}},"transactions":[]}"""
                .formatted(n, "d1".repeat(24), n, "ee".repeat(32), T0 + (n - FROM) * 3000);
    }

    static String trc20(String tx, String from, String to, String value, long ts, String type) {
        return """
                {"transaction_id":"%s","token_info":{"symbol":"USDT","address":"%s","decimals":6,"name":"Tether USD"},
                 "block_timestamp":%d,"from":"%s","to":"%s","type":"%s","value":"%s"}""".formatted(tx, USDT, ts, from, to, type, value);
    }

    static String tx(String id, String ret, String type, String valueJson, long block, long ts) {
        return """
                {"ret":[{"contractRet":"%s","fee":1100000}],"txID":"%s","blockNumber":%d,"block_timestamp":%d,
                 "raw_data":{"contract":[{"parameter":{"value":%s,"type_url":"type.googleapis.com/protocol.%s"},"type":"%s"}],
                 "timestamp":%d},"internal_transactions":[]}""".formatted(ret, id, block, ts, valueJson, type, type, ts);
    }

    static String transfer(String from, String to, long amount) {
        return "{\"amount\":%d,\"owner_address\":\"%s\",\"to_address\":\"%s\"}".formatted(amount, TronAddress.toHex(from), TronAddress.toHex(to));
    }

    static String trigger(String from, String contract, long callValue) {
        return "{\"data\":\"a9059cbb00\",\"owner_address\":\"%s\",\"contract_address\":\"%s\",\"call_value\":%d}"
                .formatted(TronAddress.toHex(from), TronAddress.toHex(contract), callValue);
    }

    static String page(String data, String fingerprint) {
        String meta = fingerprint == null ? "{\"at\":1735700000000,\"page_size\":1}"
                : "{\"at\":1735700000000,\"fingerprint\":\"" + fingerprint + "\",\"page_size\":200}";
        return "{\"data\":[" + data + "],\"success\":true,\"meta\":" + meta + "}";
    }

    @BeforeEach void start() throws Exception {
        server = new StubHttpServer()
                .route("/wallet/getblockbynum", r -> {
                    hits.merge("getblockbynum", 1, Integer::sum);
                    return Response.ok(block(Long.parseLong(r.body().replaceAll("\\D", ""))));
                })
                .route("/v1/accounts/*", r -> {
                    String q = r.pathAndQuery();
                    queries.add(q);
                    boolean second = q.contains("fingerprint=");
                    if (q.startsWith("/v1/accounts/" + ADDR + "/transactions/trc20?")) {
                        if (!second) return Response.ok(page(String.join(",",
                                trc20(TX1, OTHER, ADDR, "1000000", T0 + 9000, "Transfer"),
                                trc20(TX1, OTHER, ADDR, "1000000", T0 + 9000, "Transfer"),  // identical transfer, same tx
                                trc20(TX1, ADDR, THIRD, "250000", T0 + 9000, "Transfer"),
                                trc20(TX2, ADDR, THIRD, "5", T0 + 6000, "Approval")), "fp-t1"));
                        return Response.ok(page(trc20(TX3, ADDR, OTHER, "7", T0 + 3000, "Transfer"), null));
                    }
                    if (q.startsWith("/v1/accounts/" + ADDR + "/transactions?")) {
                        if (!second) return Response.ok(page(String.join(",",
                                tx(TX1, "SUCCESS", "TransferContract", transfer(ADDR, OTHER, 5_000_000), FROM + 3, T0 + 9000),
                                tx(TX2, "REVERT", "TransferContract", transfer(ADDR, OTHER, 9), FROM + 2, T0 + 6000),        // failed
                                tx(TX3, "SUCCESS", "TransferContract", transfer(ADDR, OTHER, 0), FROM + 2, T0 + 6000),       // zero
                                tx("04".repeat(32), "SUCCESS", "TriggerSmartContract", trigger(ADDR, USDT, 0), FROM + 1, T0 + 3000),
                                "{\"internal_tx_id\":\"" + "05".repeat(32) + "\",\"from_address\":\"x\",\"to_address\":\"y\"}"),
                                NATIVE_FP));
                        return Response.ok(page(tx("06".repeat(32), "SUCCESS", "TriggerSmartContract", trigger(OTHER, ADDR, 7), FROM, T0),
                                null));
                    }
                    return Response.status(404);
                });
        HttpJsonTransport t = new HttpJsonTransport(endpoint("trongrid", server.url(), fastResilience(1, 50)), Clock.systemUTC(), d -> {});
        provider = new TronGridProvider("tron-1", t, new DefaultTokenRegistry(), TronConfig.defaults());
    }

    @AfterEach void stop() { server.close(); }

    @Test void describesItself() {
        assertThat(provider.sourceId()).isEqualTo("trongrid");
        assertThat(provider.coverage().tokenTransfers()).isTrue();
        assertThat(provider.coverage().approvals()).isFalse();
        assertThat(provider.coverage().nativeTransfers()).isTrue();
        assertThat(provider.coverage().internalTransfers()).isFalse();
    }

    @Test void pagesTrc20ThenNativeWithTheCursorUntilTheEnd() {
        Address a = new Address(Network.TRON, ADDR);
        String window = T0 + ":" + T_TO;

        HistoryPage p1 = provider.fetch(a, FROM, TO, null);
        assertThat(p1.queries()).isEqualTo(3); // two getblockbynum + one page
        assertThat(p1.nextCursor()).isEqualTo("trc20:" + window + ":fp-t1");
        assertThat(p1.coveredFromBlock()).isEqualTo(TO + 1);
        assertThat(p1.coveredFromTime()).isNull();
        String inbound = TronGridProvider.tupleHash(OTHER, ADDR, USDT, "1000000"), outbound = TronGridProvider.tupleHash(ADDR, THIRD, USDT, "250000");
        assertThat(inbound).hasSize(16).isNotEqualTo(outbound);
        // the Approval entry is skipped; the identical transfers of TX1 get occurrences 0 and 1
        assertThat(p1.events()).extracting(ChainEvent::eventId).containsExactly("tron:" + TX1 + ":trc20:" + inbound + ":0",
                "tron:" + TX1 + ":trc20:" + inbound + ":1", "tron:" + TX1 + ":trc20:" + outbound + ":0");
        assertThat(provider.fetch(a, FROM, TO, null).events()).extracting(ChainEvent::eventId)
                .isEqualTo(p1.events().stream().map(ChainEvent::eventId).toList()); // deterministic
        ChainEvent t = p1.events().getFirst();
        assertThat(t.type()).isEqualTo(ChainEvent.EventType.TOKEN_TRANSFER);
        assertThat(t.field("transaction.from")).isEqualTo(OTHER);
        assertThat(t.field("transaction.to")).isEqualTo(ADDR);
        assertThat(t.field("transaction.hash")).isEqualTo(TX1);
        assertThat(t.field("transaction.amount")).isEqualTo(new BigDecimal("1.000000"));
        assertThat(t.field("transaction.token.symbol")).isEqualTo("USDT");
        assertThat(t.field("transaction.token.contract")).isEqualTo(USDT);
        assertThat(t.fields()).doesNotContainKeys("block.hash", "block.number", "transaction.log_index");
        assertThat(t.blockNumber()).isEqualTo(-1);
        assertThat(t.blockHash()).isNull();
        assertThat(t.eventTime()).isEqualTo(Instant.ofEpochMilli(T0 + 9000));

        HistoryPage p2 = provider.fetch(a, FROM, TO, p1.nextCursor());
        assertThat(p2.queries()).isEqualTo(1); // timestamps come from the cursor
        assertThat(p2.events()).extracting(ChainEvent::eventId)
                .containsExactly("tron:" + TX3 + ":trc20:" + TronGridProvider.tupleHash(ADDR, OTHER, USDT, "7") + ":0");
        assertThat(p2.nextCursor()).isEqualTo("native:" + window + ":");
        assertThat(p2.coveredFromBlock()).isEqualTo(TO + 1);
        assertThat(p2.coveredFromTime()).isNull();

        HistoryPage p3 = provider.fetch(a, FROM, TO, p2.nextCursor());
        assertThat(p3.nextCursor()).isEqualTo("native:" + window + ":" + NATIVE_FP);
        assertThat(p3.coveredFromBlock()).isEqualTo(TO + 1);
        // failed, zero-value, zero call_value and internal entries are skipped
        assertThat(p3.events()).hasSize(1);
        ChainEvent n = p3.events().getFirst();
        ChainTransaction same = new ChainTransaction(Network.TRON, TX1, FROM + 3, null, null, null, null, BigInteger.ONE, null, true, null, false);
        assertThat(n.eventId()).isEqualTo(TransferEventMapper.nativeEventId(same)).isEqualTo("tron:" + TX1 + ":native");
        assertThat(n.type()).isEqualTo(ChainEvent.EventType.NATIVE_TRANSFER);
        assertThat(n.field("transaction.from")).isEqualTo(ADDR);
        assertThat(n.field("transaction.to")).isEqualTo(OTHER);
        assertThat(n.field("transaction.amount")).isEqualTo(new BigDecimal("5.000000"));
        assertThat(n.field("transaction.token.symbol")).isEqualTo("TRX");
        assertThat(n.field("transaction.fee")).isEqualTo(new BigDecimal("1.100000"));
        assertThat(n.field("block.number")).isEqualTo(FROM + 3);
        assertThat(n.fields()).doesNotContainKey("block.hash");
        assertThat(n.blockNumber()).isEqualTo(FROM + 3);
        assertThat(n.blockHash()).isNull();

        HistoryPage p4 = provider.fetch(a, FROM, TO, p3.nextCursor());
        assertThat(p4.events()).extracting(ChainEvent::eventId).containsExactly("tron:" + "06".repeat(32) + ":native");
        assertThat(p4.events().getFirst().field("transaction.contract_interaction")).isEqualTo(true);
        assertThat(p4.nextCursor()).isNull();
        assertThat(p4.last()).isTrue();
        assertThat(p4.coveredFromBlock()).isEqualTo(FROM);
        assertThat(p4.coveredFromTime()).isEqualTo(Instant.ofEpochMilli(T0));

        assertThat(hits).containsEntry("getblockbynum", 4); // two per first page (p1 was fetched twice)
        assertThat(queries).hasSize(5).allMatch(q -> q.contains("only_confirmed=true") && q.contains("order_by=block_timestamp,desc")
                && q.contains("limit=200") && q.contains("min_timestamp=" + (T0 - 1)) && q.contains("max_timestamp=" + (T_TO + 999)));
        assertThat(queries).filteredOn(q -> q.contains("fingerprint=")).containsExactly(
                "/v1/accounts/" + ADDR + "/transactions/trc20?only_confirmed=true&order_by=block_timestamp,desc&limit=200&min_timestamp="
                        + (T0 - 1) + "&max_timestamp=" + (T_TO + 999) + "&fingerprint=fp-t1",
                "/v1/accounts/" + ADDR + "/transactions?only_confirmed=true&order_by=block_timestamp,desc&limit=200&min_timestamp="
                        + (T0 - 1) + "&max_timestamp=" + (T_TO + 999) + "&fingerprint=fp%3An1%2F%2B%3D");
    }

    @Test void entriesOutsideTheRequestedWindowAreDropped() {
        Address a = new Address(Network.TRON, ADDR);
        // 80000016..80000017 = T0+3000..T0+6000: TX1 (T0+9000, block FROM+3) is outside
        HistoryPage p1 = provider.fetch(a, FROM + 1, FROM + 2, null);
        assertThat(p1.events()).isEmpty();
        HistoryPage p3 = provider.fetch(a, FROM + 1, FROM + 2, "native:" + (T0 + 3000) + ":" + (T0 + 6000) + ":");
        assertThat(p3.events()).isEmpty();
    }

    @Test void rejectsInvalidCursorsAndAddresses() {
        Address a = new Address(Network.TRON, ADDR);
        assertThatThrownBy(() -> provider.fetch(a, FROM, TO, "nope")).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> provider.fetch(a, FROM, TO, "trc20:x:y:")).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> provider.fetch(new Address(Network.TRON, "not-base58"), FROM, TO, null)).isInstanceOf(ProviderException.class);
        assertThat(queries).isEmpty();
    }
}
