package io.openchain.blockchain.tron;

import static io.openchain.blockchain.transport.Json.longValue;
import static io.openchain.blockchain.transport.Json.present;
import static io.openchain.blockchain.transport.Json.text;

import io.openchain.blockchain.backfill.FreezeLogScanner;
import io.openchain.blockchain.backfill.RangeTooLargeException;
import io.openchain.blockchain.evm.EvmCodec;
import io.openchain.blockchain.provider.ContractTypeCache;
import io.openchain.blockchain.evm.LogDecoder;
import io.openchain.blockchain.indexer.TransferEventMapper;
import io.openchain.blockchain.provider.PollingSubscription;
import io.openchain.blockchain.token.StablecoinFreezeSources;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.blockchain.transport.Json;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.history.AddressHistorySource;
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

/**
 * TRON adapter for the TronGrid-compatible HTTP API (java-tron FullNode /wallet + TronGrid /v1).
 * <p>{@link #getBlockActivity} reads, per block, {@code /wallet/getblockbynum} (transactions with their
 * {@code ret[0].contractRet}) and {@code /wallet/gettransactioninfobyblocknum} (fees + TVM logs). Native TRX value:
 * {@code TransferContract.amount} and {@code TriggerSmartContract.call_value} of transactions whose
 * {@code contractRet} is {@code SUCCESS}; TRC-10 assets and internal transactions are not covered.
 * <p>{@link #isContract}: {@code POST /wallet/getcontract {value, visible:true}}, which "returns an empty object when no
 * contract information is found for the address" (https://developers.tron.network/reference/wallet-getcontract);
 * current state only (no historical block), cached ({@link ContractTypeCache}).
 * <p>{@link #scanFreezes} (freeze backfill): TronGrid {@code GET /v1/contracts/{address}/events} with
 * {@code event_name}, {@code only_confirmed=true}, {@code min_timestamp}/{@code max_timestamp} (ms) of the block range,
 * {@code order_by=block_timestamp,asc}, {@code limit=200} and {@code fingerprint} paging
 * (https://developers.tron.network/reference/get-events-by-contract-address). The API has no block-range filter: the
 * range is converted to block timestamps (two {@code getblockbynum} calls) and results are re-filtered by block number.
 * <p>{@link #fetch} (address history, ADR-0020): TronGrid account endpoints, TRC-20 transfers then native TRX
 * transfers (see the method).
 */
public final class TronGridProvider implements BlockchainProvider, FreezeLogScanner, AddressHistorySource {
    /** Pages of 200 events per (contract, event name, chunk) before the chunk is reported as too large. */
    static final int MAX_EVENT_PAGES = 25;
    private static final Token TRX = Token.nativeOf(Network.TRON, "TRX", 6);

    private final String id;
    private final HttpJsonTransport http;
    private final TokenRegistry tokens;
    private final TronConfig config;
    private final LogDecoder decoder;
    private final StablecoinFreezeSources freezeSources;
    private final ContractTypeCache contractTypes;
    private final TransferEventMapper mapper;

    public TronGridProvider(String id, HttpJsonTransport http, TokenRegistry tokens, TronConfig config) {
        this(id, http, tokens, config, StablecoinFreezeSources.defaults());
    }

    public TronGridProvider(String id, HttpJsonTransport http, TokenRegistry tokens, TronConfig config,
                            StablecoinFreezeSources freezeSources) {
        this(id, http, tokens, config, freezeSources, ContractTypeCache.defaults());
    }

    public TronGridProvider(String id, HttpJsonTransport http, TokenRegistry tokens, TronConfig config,
                            StablecoinFreezeSources freezeSources, ContractTypeCache contractTypes) {
        this.freezeSources = freezeSources;
        this.contractTypes = contractTypes;
        this.id = id;
        this.http = http;
        this.tokens = tokens;
        this.config = config;
        this.decoder = new LogDecoder(Network.TRON, tokens, freezeSources, hex -> new Address(Network.TRON, TronAddress.fromHex(hex)),
                TronAddress::fromHex);
        this.mapper = new TransferEventMapper(tokens);
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

    @Override public BlockActivity getBlockActivity(long fromBlock, long toBlock) {
        LogDecoder.Decoded d = new LogDecoder.Decoded();
        List<ChainTransaction> natives = new ArrayList<>();
        for (long blockNo = fromBlock; blockNo <= toBlock; blockNo++) {
            final long n = blockNo;
            JsonNode b = post("/wallet/getblockbynum", "num", n);
            BlockRef ref = blockRef(b).orElseThrow(() -> new ProviderException(id + ": block " + n + " not available yet", true));
            JsonNode infos = http.post("/wallet/gettransactioninfobyblocknum", Json.MAPPER.createObjectNode().put("num", n));
            Map<String, Long> fees = new HashMap<>();
            if (present(infos) && infos.isArray()) {
                for (JsonNode info : infos) {
                    String txId = text(info, "id");
                    fees.put(txId, longValue(info, "fee", 0));
                    JsonNode logs = info.path("log");
                    for (int i = 0; i < logs.size(); i++) {
                        JsonNode log = logs.get(i);
                        List<String> topics = new ArrayList<>();
                        for (JsonNode t : log.path("topics")) topics.add(text(t));
                        decoder.decode(text(log, "address"), topics, text(log, "data"), txId, i, n, ref.hash(), ref.timestamp(), d);
                    }
                }
            } else if (present(infos) && !(infos.isObject() && infos.isEmpty())) {
                throw new ProviderException(id + ": unexpected gettransactioninfobyblocknum payload", false);
            }
            for (JsonNode tx : b.path("transactions")) {
                if (!"SUCCESS".equals(text(tx.path("ret").path(0), "contractRet"))) continue; // failed: no value moved
                JsonNode c = tx.path("raw_data").path("contract").path(0);
                String type = text(c, "type");
                JsonNode v = c.path("parameter").path("value");
                long amount = "TransferContract".equals(type) ? longValue(v, "amount", 0)
                        : "TriggerSmartContract".equals(type) ? longValue(v, "call_value", 0) : 0;
                if (amount <= 0) continue;
                ChainTransaction ct = toTransaction(tx, n, ref.hash(), ref.timestamp(),
                        BigInteger.valueOf(fees.getOrDefault(text(tx, "txID"), 0L)), true);
                if (ct.from() != null && ct.to() != null) natives.add(ct);
            }
        }
        return new BlockActivity(d.transfers, natives, d.approvals, d.freezes, d.adminChanges);
    }

    /** Contract lookups (cache hits vs /wallet/getcontract calls). */
    public ContractTypeCache.Stats contractLookupStats() { return contractTypes.stats(); }

    @Override public Optional<Boolean> isContract(Address address, long atBlock) {
        if (address.network() != Network.TRON || !TronAddress.isValid(address.value())) {
            throw new ProviderException("invalid TRON address", false);
        }
        return Optional.of(contractTypes.get(address, atBlock, () -> {
            JsonNode r = http.post("/wallet/getcontract", Json.MAPPER.createObjectNode().put("value", address.value()).put("visible", true));
            if (present(r) && present(r.path("Error"))) throw new ProviderException(id + ": /wallet/getcontract error", false);
            return present(r) && r.isObject() && (present(r.path("contract_address")) || present(r.path("bytecode"))
                    || present(r.path("code_hash")));
        }));
    }

    // ---- freeze backfill ----------------------------------------------------------------------------------------

    @Override public long backfillSafeHead() {
        NetworkStatus st = getNetworkStatus();
        if (!st.healthy()) throw new ProviderException(id + " unhealthy: " + st.detail(), true);
        return st.finalizedBlock();
    }

    @Override public List<TokenFreeze> scanFreezes(long fromBlock, long toBlock) {
        var sources = freezeSources.forNetwork(Network.TRON);
        if (sources.isEmpty() || toBlock < fromBlock) return List.of();
        long minTs = blockTime(fromBlock) - 1;   // TRON blocks are >= 3 s apart: the ms windows of two blocks never overlap
        long maxTs = blockTime(toBlock) + 999;
        Map<Long, String> hashes = new HashMap<>();
        List<TokenFreeze> out = new ArrayList<>();
        for (StablecoinFreezeSources.Source s : sources) {
            for (String event : List.of(s.freezeEvent(), s.unfreezeEvent())) {
                TokenFreeze.Action action = event.equals(s.freezeEvent()) ? TokenFreeze.Action.FREEZE : TokenFreeze.Action.UNFREEZE;
                String fingerprint = null;
                int pages = 0;
                do {
                    if (++pages > MAX_EVENT_PAGES) {
                        throw new RangeTooLargeException(id + ": more than " + MAX_EVENT_PAGES * 200 + " " + event + " events in blocks "
                                + fromBlock + ".." + toBlock, null);
                    }
                    StringBuilder q = new StringBuilder("/v1/contracts/").append(s.contract()).append("/events?event_name=")
                            .append(URLEncoder.encode(event, StandardCharsets.UTF_8))
                            .append("&only_confirmed=true&order_by=block_timestamp,asc&limit=200&min_timestamp=").append(minTs)
                            .append("&max_timestamp=").append(maxTs);
                    if (fingerprint != null) q.append("&fingerprint=").append(URLEncoder.encode(fingerprint, StandardCharsets.UTF_8));
                    JsonNode r = http.get(q.toString());
                    if (present(r.path("success")) && !r.path("success").asBoolean(true)) {
                        throw new ProviderException(id + ": contract events query failed", true);
                    }
                    for (JsonNode ev : r.path("data")) {
                        long bn = longValue(ev, "block_number", -1);
                        if (bn < fromBlock || bn > toBlock || !event.equals(text(ev, "event_name"))) continue;
                        if (!sameContract(s.contract(), text(ev, "contract_address"))) continue;
                        Optional<String> target = eventAddress(ev);
                        if (target.isEmpty()) continue;
                        String bh = hashes.computeIfAbsent(bn, b -> getBlock(b).map(BlockRef::hash).orElse(null));
                        out.add(new TokenFreeze(Network.TRON, text(ev, "transaction_id"), (int) longValue(ev, "event_index", 0), bn, bh,
                                Instant.ofEpochMilli(longValue(ev, "block_timestamp", 0)), new Address(Network.TRON, target.get()),
                                tokens.resolve(Network.TRON, s.contract()), s.issuer(), action, event));
                    }
                    fingerprint = text(r.path("meta"), "fingerprint");
                } while (fingerprint != null && !fingerprint.isBlank());
            }
        }
        return out;
    }

    private long blockTime(long n) {
        return getBlock(n).orElseThrow(() -> new ProviderException(id + ": block " + n + " not available", true)).timestamp().toEpochMilli();
    }

    private static boolean sameContract(String base58, String reported) {
        if (reported == null) return true; // field missing: the path already scoped the query to this contract
        try {
            return base58.equals(reported.startsWith("T") ? reported : TronAddress.fromHex(reported));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The single address parameter of a blacklist event ({@code result_type} = address), in base58. */
    static Optional<String> eventAddress(JsonNode ev) {
        JsonNode result = ev.path("result"), types = ev.path("result_type");
        List<String> candidates = new ArrayList<>();
        if (types.isObject() && !types.isEmpty()) {
            for (Map.Entry<String, JsonNode> t : types.properties()) {
                if ("address".equals(text(t.getValue()))) candidates.add(text(result, t.getKey()));
            }
        } else if (result.isObject()) {
            for (Map.Entry<String, JsonNode> e : result.properties()) if (!e.getKey().matches("\\d+")) candidates.add(text(e.getValue()));
        }
        if (candidates.size() != 1 || candidates.getFirst() == null) return Optional.empty();
        String v = candidates.getFirst();
        try {
            return Optional.of(v.startsWith("T") ? (TronAddress.isValid(v) ? v : null) : TronAddress.fromHex(v));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
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
        StringBuilder p = new StringBuilder(accountPath(address)).append(suffix);
        if (limit <= 0) return p.toString(); // account lookup: no paging
        p.append("?limit=").append(Math.min(limit, 200));
        if (cursor != null) p.append("&fingerprint=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        return p.toString();
    }

    private static String accountPath(Address address) {
        if (address.network() != Network.TRON || !TronAddress.isValid(address.value())) {
            throw new ProviderException("invalid TRON address", false);
        }
        return "/v1/accounts/" + address.value();
    }

    // ---- address history (ADR-0020) -----------------------------------------------------------------------------

    private static final Coverage HISTORY_COVERAGE = new Coverage(true, false, true, false);
    private static final String PHASE_TRC20 = "trc20", PHASE_NATIVE = "native";

    @Override public String sourceId() { return "trongrid"; }

    @Override public Coverage coverage() { return HISTORY_COVERAGE; }

    /** Solidified block, as for the freeze backfill. */
    @Override public long historySafeHead() { return backfillSafeHead(); }

    /**
     * One TronGrid page (200 entries max) of the history of {@code address}, newest first, confirmed only. TronGrid
     * account endpoints filter by time, not block: the first call converts {@code [fromBlock, toBlock]} to the block
     * timestamps (two {@code getblockbynum}), which the cursor then carries. Phase 1 pages
     * {@code /v1/accounts/{address}/transactions/trc20}, phase 2 {@code /v1/accounts/{address}/transactions}, both with
     * {@code only_confirmed=true&order_by=block_timestamp,desc&limit=200&min_timestamp&max_timestamp&fingerprint}.
     * <ul>
     *   <li>TRC-20: TronGrid exposes neither the block number nor the log index, so the event id is
     *       {@code tron:<txid>:trc20:<h>:<n>} ({@code h} = 16 hex chars of SHA-256 over {@code from|to|contract|value},
     *       {@code n} = occurrence of that same tuple within the tx in that page), stable whatever the order of the
     *       entries, and never colliding with live ids ({@code tron:<txid>:<logIndex>}): a transfer already indexed live
     *       is seen twice under two ids (and two identical transfers of one tx split across two pages share n = 0). Block number is -1 and block hash null (no {@code block.number},
     *       {@code block.hash}, {@code transaction.log_index} fields); entries whose {@code type} is not
     *       {@code Transfer} are skipped.</li>
     *   <li>Native TRX: successful {@code TransferContract} amounts and {@code TriggerSmartContract} call values above
     *       zero, as the live path; id {@code tron:<txid>:native} (same as live); block hash null (not returned).</li>
     * </ul>
     * Cursor format: {@code <trc20|native>:<fromBlockTimeMs>:<toBlockTimeMs>:<fingerprint>} (empty fingerprint = first
     * page of the phase). {@code coveredFromBlock} stays {@code toBlock + 1} until the last native page, then is
     * {@code fromBlock}, with {@code coveredFromTime} = time of {@code fromBlock}.
     */
    @Override public HistoryPage fetch(Address address, long fromBlock, long toBlock, String cursor) {
        String base = accountPath(address);
        if (fromBlock < 0 || toBlock < fromBlock) return new HistoryPage(List.of(), fromBlock, null, null, 0);
        int queries = 0;
        String phase = PHASE_TRC20, fingerprint = null;
        long fromMs, toMs;
        if (cursor == null) {
            fromMs = blockTime(fromBlock);
            toMs = fromBlock == toBlock ? fromMs : blockTime(toBlock);
            queries += fromBlock == toBlock ? 1 : 2;
        } else {
            String[] c = cursor.split(":", 4);
            try {
                if (c.length != 4 || !(PHASE_TRC20.equals(c[0]) || PHASE_NATIVE.equals(c[0]))) throw new NumberFormatException();
                phase = c[0];
                fromMs = Long.parseLong(c[1]);
                toMs = Long.parseLong(c[2]);
                fingerprint = c[3].isEmpty() ? null : c[3];
            } catch (NumberFormatException e) {
                throw new ProviderException(id + ": invalid history cursor " + cursor, false);
            }
        }
        long minTs = fromMs - 1, maxTs = toMs + 999; // same window as scanFreezes: blocks are >= 3 s apart
        boolean trc20 = PHASE_TRC20.equals(phase);
        StringBuilder q = new StringBuilder(base).append(trc20 ? "/transactions/trc20" : "/transactions")
                .append("?only_confirmed=true&order_by=block_timestamp,desc&limit=200&min_timestamp=").append(minTs)
                .append("&max_timestamp=").append(maxTs);
        if (fingerprint != null) q.append("&fingerprint=").append(URLEncoder.encode(fingerprint, StandardCharsets.UTF_8));
        JsonNode r = http.get(q.toString());
        queries++;
        if (present(r.path("success")) && !r.path("success").asBoolean(true)) {
            throw new ProviderException(id + ": account history query failed", true);
        }
        Instant now = Instant.now();
        List<ChainEvent> events = trc20 ? trc20Events(r.path("data"), minTs, maxTs, now)
                : nativeEvents(r.path("data"), fromBlock, toBlock, minTs, maxTs, now);
        String nextFp = text(r.path("meta"), "fingerprint");
        boolean more = nextFp != null && !nextFp.isBlank();
        String next = more ? phase + ":" + fromMs + ":" + toMs + ":" + nextFp
                : trc20 ? PHASE_NATIVE + ":" + fromMs + ":" + toMs + ":" : null;
        return next == null ? new HistoryPage(events, fromBlock, Instant.ofEpochMilli(fromMs), null, queries)
                : new HistoryPage(events, toBlock + 1, null, next, queries);
    }

    private List<ChainEvent> trc20Events(JsonNode data, long minTs, long maxTs, Instant ingestedAt) {
        List<ChainEvent> out = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (JsonNode t : data) {
            String type = text(t, "type");
            if (type != null && !"Transfer".equals(type)) continue;
            String txId = text(t, "transaction_id"), from = text(t, "from"), to = text(t, "to"), value = text(t, "value");
            String contract = text(t.path("token_info"), "address");
            long ms = longValue(t, "block_timestamp", -1);
            if (txId == null || from == null || to == null || value == null || contract == null || ms < minTs || ms > maxTs) continue;
            String tuple = tupleHash(from, to, contract, value);
            int n = occurrences.merge(txId + ":" + tuple, 1, Integer::sum) - 1;
            Instant ts = Instant.ofEpochMilli(ms);
            TokenTransfer tt = new TokenTransfer(Network.TRON, txId, n, -1, null, ts, addr(from), addr(to),
                    tokens.resolve(Network.TRON, contract), new BigInteger(value));
            ChainEvent e = mapper.map(tt, new BlockRef(Network.TRON, -1, "", null, ts), ingestedAt);
            out.add(withoutBlock(e, "tron:" + txId + ":trc20:" + tuple + ":" + n, -1, "block.number", "transaction.log_index"));
        }
        return out;
    }

    /** First 8 bytes (hex) of SHA-256 over {@code from|to|contract|value}: tells apart distinct transfers of one tx. */
    static String tupleHash(String from, String to, String contract, String value) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", from, to, contract, value).getBytes(StandardCharsets.UTF_8));
            return EvmCodec.encodeHex(Arrays.copyOf(h, 8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ChainEvent> nativeEvents(JsonNode data, long fromBlock, long toBlock, long minTs, long maxTs, Instant ingestedAt) {
        List<ChainEvent> out = new ArrayList<>();
        for (JsonNode tx : data) {
            if (!present(tx.path("txID")) || !present(tx.path("raw_data"))) continue; // internal transactions: other layout
            if (!"SUCCESS".equals(text(tx.path("ret").path(0), "contractRet"))) continue;
            JsonNode c = tx.path("raw_data").path("contract").path(0);
            String type = text(c, "type");
            JsonNode v = c.path("parameter").path("value");
            long amount = "TransferContract".equals(type) ? longValue(v, "amount", 0)
                    : "TriggerSmartContract".equals(type) ? longValue(v, "call_value", 0) : 0;
            if (amount <= 0) continue;
            long bn = longValue(tx, "blockNumber", -1), ms = longValue(tx, "block_timestamp", -1);
            if (bn >= 0 ? bn < fromBlock || bn > toBlock : ms < minTs || ms > maxTs) continue;
            Instant ts = Instant.ofEpochMilli(ms);
            ChainTransaction ct = toTransaction(tx, bn, null, ts, BigInteger.valueOf(longValue(tx.path("ret").path(0), "fee", 0)), true);
            if (ct.from() == null || ct.to() == null) continue;
            ChainEvent e = mapper.mapNative(ct, new BlockRef(Network.TRON, bn, "", null, ts), ingestedAt);
            out.add(withoutBlock(e, e.eventId(), bn));
        }
        return out;
    }

    /** TronGrid account endpoints do not return block hashes: rebuilds the event with a null hash, minus those fields. */
    private static ChainEvent withoutBlock(ChainEvent e, String eventId, long blockNumber, String... dropFields) {
        Map<String, Object> f = new LinkedHashMap<>(e.fields());
        f.remove("block.hash");
        for (String d : dropFields) f.remove(d);
        return new ChainEvent(eventId, e.type(), e.network(), blockNumber, null, e.eventTime(), e.ingestedAt(), false, f);
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
