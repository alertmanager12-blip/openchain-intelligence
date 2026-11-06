package io.openchain.blockchain.tron;

import static io.openchain.blockchain.transport.Json.longValue;
import static io.openchain.blockchain.transport.Json.present;
import static io.openchain.blockchain.transport.Json.text;

import io.openchain.blockchain.evm.EvmCodec;
import io.openchain.blockchain.provider.PollingSubscription;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.blockchain.transport.Json;
import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.core.provider.ProviderException;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** TRON adapter for the TronGrid-compatible HTTP API (java-tron FullNode /wallet + TronGrid /v1). */
public final class TronGridProvider implements BlockchainProvider {
    private static final Token TRX = Token.nativeOf(Network.TRON, "TRX", 6);

    private final String id;
    private final HttpJsonTransport http;
    private final TokenRegistry tokens;
    private final TronConfig config;

    public TronGridProvider(String id, HttpJsonTransport http, TokenRegistry tokens, TronConfig config) {
        this.id = id;
        this.http = http;
        this.tokens = tokens;
        this.config = config;
    }

    @Override public String id() { return id; }
    @Override public Network network() { return Network.TRON; }

    private JsonNode post(String path, String key, Object value) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        if (value instanceof Long l) body.put(key, l);
        else if (value instanceof String s) body.put(key, s);
        JsonNode r = http.post(path, body);
        if (present(r) && present(r.path("Error"))) throw new ProviderException(id + ": " + path + " error", false);
        return r;
    }

    private Optional<BlockRef> blockRef(JsonNode b) {
        if (!present(b) || !present(b.path("blockID"))) return Optional.empty();
        JsonNode raw = b.path("block_header").path("raw_data");
        return Optional.of(new BlockRef(Network.TRON, longValue(raw, "number", 0), text(b, "blockID"),
                text(raw, "parentHash"), Instant.ofEpochMilli(longValue(raw, "timestamp", 0))));
    }

    public long headBlock() {
        return blockRef(post("/wallet/getnowblock", null, null))
                .orElseThrow(() -> new ProviderException(id + ": empty getnowblock", true)).number();
    }

    @Override public Optional<BlockRef> getBlock(long number) { return blockRef(post("/wallet/getblockbynum", "num", number)); }

    @Override public Optional<ChainTransaction> getTransaction(String hash) {
        return getTransactionReceipt(hash).map(TransactionReceipt::transaction);
    }

    /** Only confirmed-in-block transactions (with transaction info) are returned. */
    @Override public Optional<TransactionReceipt> getTransactionReceipt(String hash) {
        JsonNode tx = post("/wallet/gettransactionbyid", "value", hash);
        if (!present(tx) || !present(tx.path("txID"))) return Optional.empty();
        JsonNode info = post("/wallet/gettransactioninfobyid", "value", hash);
        if (!present(info) || !present(info.path("blockNumber"))) return Optional.empty();
        long blockNumber = longValue(info, "blockNumber", 0);
        String blockHash = getBlock(blockNumber).map(BlockRef::hash).orElse(null);
        ChainTransaction ct = toTransaction(tx, blockNumber, blockHash,
                Instant.ofEpochMilli(longValue(info, "blockTimeStamp", 0)), BigInteger.valueOf(longValue(info, "fee", 0)),
                successOf(tx, info));
        return Optional.of(new TransactionReceipt(ct, decodeLogs(info, blockHash), ct.success()));
    }

    private static boolean successOf(JsonNode tx, JsonNode info) {
        String receiptResult = info == null ? null : text(info.path("receipt"), "result");
        String contractRet = text(tx.path("ret").path(0), "contractRet");
        if (receiptResult != null) return "SUCCESS".equals(receiptResult);
        return contractRet == null || "SUCCESS".equals(contractRet);
    }

    private ChainTransaction toTransaction(JsonNode tx, long blockNumber, String blockHash, Instant ts, BigInteger fee,
                                           boolean success) {
        JsonNode contract = tx.path("raw_data").path("contract").path(0);
        String type = text(contract, "type");
        JsonNode v = contract.path("parameter").path("value");
        String owner = text(v, "owner_address");
        String to;
        BigInteger value;
        String selector = null;
        boolean contractCall = false;
        if ("TriggerSmartContract".equals(type)) {
            to = text(v, "contract_address");
            value = BigInteger.valueOf(longValue(v, "call_value", 0));
            String data = text(v, "data");
            if (data != null && data.length() >= 8) selector = "0x" + data.substring(0, 8).toLowerCase();
            contractCall = true;
        } else {
            to = text(v, "to_address");
            value = BigInteger.valueOf(longValue(v, "amount", 0));
            contractCall = !"TransferContract".equals(type);
        }
        return new ChainTransaction(Network.TRON, text(tx, "txID"), blockNumber, blockHash, ts,
                owner == null ? null : addr(owner), to == null ? null : addr(to), value, fee, success, selector, contractCall);
    }

    private static Address addr(String hexOrBase58) {
        return new Address(Network.TRON, hexOrBase58.startsWith("T") ? hexOrBase58 : TronAddress.fromHex(hexOrBase58));
    }

    /** TRC20 Transfer logs of one transaction info; logIndex = position in the tx log list. */
    List<TokenTransfer> decodeLogs(JsonNode info, String blockHash) {
        List<TokenTransfer> out = new ArrayList<>();
        JsonNode logs = info.path("log");
        String txHash = text(info, "id");
        long blockNumber = longValue(info, "blockNumber", 0);
        Instant ts = Instant.ofEpochMilli(longValue(info, "blockTimeStamp", 0));
        for (int i = 0; i < logs.size(); i++) {
            JsonNode log = logs.get(i);
            JsonNode topics = log.path("topics");
            if (topics.size() != 3 || !EvmCodec.isTransferTopic(text(topics.get(0)))) continue;
            String data = text(log, "data");
            if (data == null || EvmCodec.strip0x(data).length() != 64) continue;
            Token token = tokens.resolve(Network.TRON, TronAddress.fromHex(text(log, "address")));
            out.add(new TokenTransfer(Network.TRON, txHash, i, blockNumber, blockHash, ts,
                    new Address(Network.TRON, TronAddress.fromTopic(text(topics.get(1)))),
                    new Address(Network.TRON, TronAddress.fromTopic(text(topics.get(2)))),
                    token, new BigInteger(EvmCodec.strip0x(data), 16)));
        }
        return out;
    }

    @Override public List<TokenTransfer> getTokenTransfers(long fromBlock, long toBlock) {
        List<TokenTransfer> out = new ArrayList<>();
        for (long n = fromBlock; n <= toBlock; n++) {
            Optional<BlockRef> block = getBlock(n);
            if (block.isEmpty()) throw new ProviderException(id + ": block " + n + " not available yet", true);
            JsonNode infos = http.post("/wallet/gettransactioninfobyblocknum", Json.MAPPER.createObjectNode().put("num", n));
            if (!present(infos)) continue;
            if (!infos.isArray()) {
                if (infos.isObject() && infos.isEmpty()) continue; // block without transactions
                throw new ProviderException(id + ": unexpected gettransactioninfobyblocknum payload", false);
            }
            for (JsonNode info : infos) out.addAll(decodeLogs(info, block.get().hash()));
        }
        return List.copyOf(out);
    }

    @Override public Page<ChainTransaction> getAddressTransactions(Address address, String cursor, int limit) {
        JsonNode r = http.get(accountPath(address, "/transactions", cursor, limit));
        List<ChainTransaction> items = new ArrayList<>();
        for (JsonNode tx : r.path("data")) {
            BigInteger fee = BigInteger.valueOf(longValue(tx.path("ret").path(0), "fee", 0));
            items.add(toTransaction(tx, longValue(tx, "blockNumber", -1), null,
                    Instant.ofEpochMilli(longValue(tx, "block_timestamp", 0)), fee, successOf(tx, null)));
        }
        return new Page<>(List.copyOf(items), text(r.path("meta"), "fingerprint"));
    }

    /**
     * TRC20 history from TronGrid's /v1 index. The API exposes no log index nor block number, so
     * {@code logIndex} is the occurrence order within the tx in this page and {@code blockNumber} is -1:
     * suitable for investigation views, NOT as the indexer dedup key (use {@link #getTokenTransfers}).
     */
    public Page<TokenTransfer> getAddressTokenTransfers(Address address, String cursor, int limit) {
        JsonNode r = http.get(accountPath(address, "/transactions/trc20", cursor, limit));
        List<TokenTransfer> items = new ArrayList<>();
        Map<String, Integer> ordinal = new HashMap<>();
        for (JsonNode t : r.path("data")) {
            String txId = text(t, "transaction_id");
            String contract = text(t.path("token_info"), "address");
            Token token = tokens.resolve(Network.TRON, contract);
            items.add(new TokenTransfer(Network.TRON, txId, ordinal.merge(txId, 1, Integer::sum) - 1, -1, null,
                    Instant.ofEpochMilli(longValue(t, "block_timestamp", 0)), addr(text(t, "from")), addr(text(t, "to")),
                    token, new BigInteger(text(t, "value"))));
        }
        return new Page<>(List.copyOf(items), text(r.path("meta"), "fingerprint"));
    }

    private static String accountPath(Address address, String suffix, String cursor, int limit) {
        if (address.network() != Network.TRON || !TronAddress.isValid(address.value())) {
            throw new ProviderException("invalid TRON address", false);
        }
        StringBuilder p = new StringBuilder("/v1/accounts/").append(address.value()).append(suffix);
        if (limit <= 0) return p.toString(); // account lookup: no paging
        p.append("?limit=").append(Math.min(limit, 200));
        if (cursor != null) p.append("&fingerprint=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        return p.toString();
    }

    @Override public List<Balance> getAddressBalance(Address address) {
        long head = headBlock();
        JsonNode r = http.get(accountPath(address, "", null, 0));
        Instant now = Instant.now();
        List<Balance> out = new ArrayList<>();
        JsonNode acc = r.path("data").path(0);
        out.add(new Balance(address, TRX, BigInteger.valueOf(longValue(acc, "balance", 0)), head, now));
        for (JsonNode entry : acc.path("trc20")) {
            for (Map.Entry<String, JsonNode> e : entry.properties()) {
                out.add(new Balance(address, tokens.resolve(Network.TRON, e.getKey()), new BigInteger(text(e.getValue())), head, now));
            }
        }
        return List.copyOf(out);
    }

    @Override public Subscription subscribeToEvents(Consumer<BlockRef> onNewHead) {
        return new PollingSubscription(id, config.pollInterval(), this::headBlock, this::getBlock, onNewHead);
    }

    @Override public NetworkStatus getNetworkStatus() {
        long t0 = System.nanoTime();
        try {
            long head = headBlock();
            long finalized = Math.max(0, head - config.confirmations());
            if (config.useSolidityNode()) {
                try {
                    finalized = blockRef(post("/walletsolidity/getnowblock", null, null)).map(BlockRef::number).orElse(finalized);
                } catch (ProviderException e) {
                    if (e.retryable()) throw e; // endpoint without solidity API: keep confirmation fallback
                }
            }
            return new NetworkStatus(Network.TRON, head, finalized, true, id, (System.nanoTime() - t0) / 1_000_000, Instant.now(), "ok");
        } catch (ProviderException e) {
            return new NetworkStatus(Network.TRON, 0, 0, false, id, (System.nanoTime() - t0) / 1_000_000, Instant.now(), e.getMessage());
        }
    }
}
