package io.openchain.blockchain.evm;

import static io.openchain.blockchain.evm.EvmCodec.*;
import static io.openchain.blockchain.transport.Json.present;
import static io.openchain.blockchain.transport.Json.text;

import io.openchain.blockchain.backfill.FreezeLogScanner;
import io.openchain.blockchain.backfill.RangeTooLargeException;
import io.openchain.blockchain.provider.ContractTypeCache;
import io.openchain.blockchain.provider.PollingSubscription;
import io.openchain.blockchain.token.StablecoinFreezeSources;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.blockchain.transport.Json;
import io.openchain.blockchain.transport.JsonRpcException;
import io.openchain.blockchain.transport.JsonRpcClient;
import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.core.provider.ProviderException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Standard Ethereum JSON-RPC adapter (Ethereum, BSC, Polygon). Vendor-neutral: any compliant node URL works.
 * <p>{@link #getBlockActivity} reads, per block: {@code eth_getBlockByNumber(n, true)} (transactions) and
 * {@code eth_getBlockReceipts(n)} (statuses + logs). Nodes without {@code eth_getBlockReceipts} (JSON-RPC
 * "method not found/not supported") are detected once; the adapter then uses {@code eth_getLogs({blockHash})} for
 * logs and {@code eth_getTransactionReceipt} only for the transactions carrying native value.
 * <p>{@link #isContract}: {@code eth_getCode(address, block)}, cached ({@link ContractTypeCache}); nodes that no longer
 * hold the state of that block (non-archive) are asked for {@code latest} instead.
 * <p>{@link #scanFreezes} (freeze backfill): {@code eth_getLogs({fromBlock, toBlock, address: [official contracts],
 * topics: [[freeze/unfreeze topics]]})}; a "too many results / range too large" error becomes
 * {@link RangeTooLargeException}.
 */
public final class EvmJsonRpcProvider implements BlockchainProvider, FreezeLogScanner {
    private static final Set<Network> SUPPORTED = EnumSet.of(Network.ETHEREUM, Network.BSC, Network.POLYGON);
    private static final int TS_CACHE = 2048;

    private final String id;
    private final Network network;
    private final JsonRpcClient rpc;
    private final TokenRegistry tokens;
    private final EvmConfig config;
    private final Token nativeToken;
    private final StablecoinFreezeSources freezeSources;
    private final LogDecoder decoder;
    private final ContractTypeCache contractTypes;
    /** null = not probed yet; false once the node said eth_getBlockReceipts is not implemented. */
    private volatile Boolean blockReceiptsSupported;
    private final Map<String, Instant> timestampByBlockHash = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Instant> e) { return size() > TS_CACHE; }
            });

    public EvmJsonRpcProvider(String id, Network network, JsonRpcClient rpc, TokenRegistry tokens, EvmConfig config) {
        this(id, network, rpc, tokens, config, StablecoinFreezeSources.defaults());
    }

    public EvmJsonRpcProvider(String id, Network network, JsonRpcClient rpc, TokenRegistry tokens, EvmConfig config,
                              StablecoinFreezeSources freezeSources) {
        this(id, network, rpc, tokens, config, freezeSources, ContractTypeCache.defaults());
    }

    public EvmJsonRpcProvider(String id, Network network, JsonRpcClient rpc, TokenRegistry tokens, EvmConfig config,
                              StablecoinFreezeSources freezeSources, ContractTypeCache contractTypes) {
        if (!SUPPORTED.contains(network)) throw new IllegalArgumentException(network + " is not an EVM network");
        this.id = id;
        this.network = network;
        this.rpc = rpc;
        this.tokens = tokens;
        this.config = config;
        this.nativeToken = Token.nativeOf(network, config.nativeSymbol(), config.nativeDecimals());
        this.freezeSources = freezeSources;
        this.decoder = new LogDecoder(network, tokens, freezeSources, hex -> new Address(network, "0x" + hex), c -> c);
        this.contractTypes = contractTypes;
    }

    /** Contract-code lookups (cache hits vs eth_getCode calls). */
    public ContractTypeCache.Stats contractLookupStats() { return contractTypes.stats(); }

    /** Native coin of this provider (symbol/decimals from {@link EvmConfig}). */
    public Token nativeToken() { return nativeToken; }

    /** Empty until probed; then whether the node implements eth_getBlockReceipts. */
    public Optional<Boolean> blockReceiptsSupported() { return Optional.ofNullable(blockReceiptsSupported); }

    @Override public String id() { return id; }
    @Override public Network network() { return network; }

    public long headBlock() { return quantityLong(text(rpc.call("eth_blockNumber"))); }

    @Override public Optional<BlockRef> getBlock(long number) {
        return blockRef(rpc.call("eth_getBlockByNumber", toQuantity(number), false));
    }

    private Optional<BlockRef> blockRef(JsonNode b) {
        if (!present(b)) return Optional.empty();
        Instant ts = Instant.ofEpochSecond(quantityLong(text(b, "timestamp")));
        String hash = text(b, "hash");
        if (hash != null) timestampByBlockHash.put(hash, ts);
        return Optional.of(new BlockRef(network, quantityLong(text(b, "number")), hash, text(b, "parentHash"), ts));
    }

    private Instant timestampOf(String blockHash) {
        Instant cached = timestampByBlockHash.get(blockHash);
        if (cached != null) return cached;
        JsonNode b = rpc.call("eth_getBlockByHash", blockHash, false);
        if (!present(b)) throw new ProviderException(id + ": block " + blockHash + " not found (reorged?)", true);
        return blockRef(b).orElseThrow().timestamp();
    }

    @Override public Optional<ChainTransaction> getTransaction(String hash) {
        return getTransactionReceipt(hash).map(TransactionReceipt::transaction);
    }

    /** Only mined transactions are returned (pending -> empty). */
    @Override public Optional<TransactionReceipt> getTransactionReceipt(String hash) {
        JsonNode tx = rpc.call("eth_getTransactionByHash", hash);
        if (!present(tx) || !present(tx.path("blockNumber"))) return Optional.empty();
        JsonNode rc = rpc.call("eth_getTransactionReceipt", hash);
        if (!present(rc)) return Optional.empty();
        String blockHash = text(tx, "blockHash");
        Instant ts = timestampOf(blockHash);
        boolean success = "0x1".equals(text(rc, "status"));
        BigInteger price = present(rc.path("effectiveGasPrice")) ? quantity(text(rc, "effectiveGasPrice"))
                : quantity(text(tx, "gasPrice"));
        BigInteger fee = quantity(text(rc, "gasUsed")).multiply(price);
        String input = Optional.ofNullable(text(tx, "input")).orElse("0x");
        String toRaw = text(tx, "to") != null ? text(tx, "to") : text(rc, "contractAddress");
        ChainTransaction ct = new ChainTransaction(network, text(tx, "hash"), quantityLong(text(tx, "blockNumber")), blockHash,
                ts, new Address(network, text(tx, "from")), toRaw == null ? null : new Address(network, toRaw),
                quantity(text(tx, "value")), fee, success, input.length() >= 10 ? input.substring(0, 10) : null,
                input.length() > 2 || text(tx, "to") == null);
        List<TokenTransfer> transfers = new ArrayList<>();
        for (JsonNode log : rc.path("logs")) decode(log, ts).ifPresent(transfers::add);
        return Optional.of(new TransactionReceipt(ct, List.copyOf(transfers), success));
    }

    @Override public Page<ChainTransaction> getAddressTransactions(Address address, String cursor, int limit) {
        throw new ProviderException(id + ": address history is not available through standard JSON-RPC; "
                + "use an explorer/indexer provider", false);
    }

    @Override public List<TokenTransfer> getTokenTransfers(long fromBlock, long toBlock) {
        if (toBlock < fromBlock) return List.of();
        List<TokenTransfer> out = new ArrayList<>();
        for (long start = fromBlock; start <= toBlock; start += config.maxLogRange()) {
            long end = Math.min(toBlock, start + config.maxLogRange() - 1);
            ObjectNode filter = Json.MAPPER.createObjectNode();
            filter.put("fromBlock", toQuantity(start));
            filter.put("toBlock", toQuantity(end));
            ArrayNode topics = filter.putArray("topics");
            topics.add(TRANSFER_TOPIC);
            JsonNode logs = rpc.call("eth_getLogs", filter);
            if (!logs.isArray()) throw new ProviderException(id + ": eth_getLogs returned non-array", true);
            for (JsonNode log : logs) {
                if (log.path("removed").asBoolean(false)) continue;
                String bh = text(log, "blockHash");
                Instant ts = present(log.path("blockTimestamp")) ? Instant.ofEpochSecond(quantityLong(text(log, "blockTimestamp")))
                        : timestampOf(bh);
                decode(log, ts).ifPresent(out::add);
            }
        }
        out.sort(Comparator.comparingLong(TokenTransfer::blockNumber).thenComparingInt(TokenTransfer::logIndex));
        return List.copyOf(out);
    }

    @Override public BlockActivity getBlockActivity(long fromBlock, long toBlock) {
        LogDecoder.Decoded d = new LogDecoder.Decoded();
        List<ChainTransaction> natives = new ArrayList<>();
        for (long n = fromBlock; n <= toBlock; n++) blockActivity(n, d, natives);
        return new BlockActivity(d.transfers, natives, d.approvals, d.freezes, d.adminChanges);
    }

    private void blockActivity(long n, LogDecoder.Decoded d, List<ChainTransaction> natives) {
        JsonNode b = rpc.call("eth_getBlockByNumber", toQuantity(n), true);
        if (!present(b)) throw new ProviderException(id + ": block " + n + " not available yet", true);
        BlockRef ref = blockRef(b).orElseThrow();
        Map<String, JsonNode> receipts = blockReceipts(n);
        LogDecoder.Decoded block = new LogDecoder.Decoded();
        if (receipts != null) {
            for (JsonNode rc : receipts.values()) for (JsonNode log : rc.path("logs")) decodeLog(log, ref, block);
        } else {
            ObjectNode filter = Json.MAPPER.createObjectNode();
            filter.put("blockHash", ref.hash()); // EIP-234: logs of exactly this block, even if the head moved
            ArrayNode topic0 = filter.putArray("topics").addArray();
            topic0.add(TRANSFER_TOPIC).add(APPROVAL_TOPIC);
            freezeSources.topics(network).forEach(topic0::add);
            ADMIN_CHANGE_TOPICS.forEach(topic0::add);
            JsonNode logs = rpc.call("eth_getLogs", filter);
            if (!logs.isArray()) throw new ProviderException(id + ": eth_getLogs returned non-array", true);
            for (JsonNode log : logs) if (!log.path("removed").asBoolean(false)) decodeLog(log, ref, block);
        }
        block.transfers.sort(Comparator.comparingInt(TokenTransfer::logIndex));
        block.approvals.sort(Comparator.comparingInt(TokenApproval::logIndex));
        block.freezes.sort(Comparator.comparingInt(TokenFreeze::logIndex));
        block.adminChanges.sort(Comparator.comparingInt(ContractAdminChange::logIndex));
        d.transfers.addAll(block.transfers);
        d.approvals.addAll(block.approvals);
        d.freezes.addAll(block.freezes);
        d.adminChanges.addAll(block.adminChanges);
        for (JsonNode tx : b.path("transactions")) {
            if (!tx.isObject()) throw new ProviderException(id + ": eth_getBlockByNumber returned hashes, not transactions", false);
            BigInteger value = quantity(text(tx, "value"));
            if (value.signum() <= 0) continue; // zero-value call: no native flow
            String hash = text(tx, "hash");
            JsonNode rc = receipts != null ? receipts.get(hash) : rpc.call("eth_getTransactionReceipt", hash);
            if (!present(rc)) throw new ProviderException(id + ": receipt of " + hash + " not available yet", true);
            // only status 0x1 proves the value moved: 0x0 = reverted; no status = pre-Byzantium receipt (not supported)
            if (!"0x1".equals(text(rc, "status"))) continue;
            String toRaw = text(tx, "to") != null ? text(tx, "to") : text(rc, "contractAddress"); // creation: new contract
            if (toRaw == null) continue;
            BigInteger price = present(rc.path("effectiveGasPrice")) ? quantity(text(rc, "effectiveGasPrice"))
                    : quantity(text(tx, "gasPrice"));
            String input = Optional.ofNullable(text(tx, "input")).orElse("0x");
            String bh = Optional.ofNullable(text(rc, "blockHash")).orElse(ref.hash());
            natives.add(new ChainTransaction(network, hash, n, bh, ref.timestamp(), new Address(network, text(tx, "from")),
                    new Address(network, toRaw), value, quantity(text(rc, "gasUsed")).multiply(price), true,
                    input.length() >= 10 ? input.substring(0, 10) : null, input.length() > 2 || text(tx, "to") == null));
        }
    }

    /** Receipts of block n keyed by tx hash, or null when the node does not implement eth_getBlockReceipts. */
    private Map<String, JsonNode> blockReceipts(long n) {
        if (Boolean.FALSE.equals(blockReceiptsSupported)) return null;
        JsonNode r;
        try {
            r = rpc.call("eth_getBlockReceipts", toQuantity(n));
        } catch (JsonRpcException e) {
            if (!e.methodUnsupported()) throw e;
            blockReceiptsSupported = false;
            return null;
        }
        if (!r.isArray()) throw new ProviderException(id + ": receipts of block " + n + " not available yet", true);
        blockReceiptsSupported = true;
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode rc : r) out.put(text(rc, "transactionHash"), rc);
        return out;
    }

    private void decodeLog(JsonNode log, BlockRef ref, LogDecoder.Decoded out) {
        JsonNode topics = log.path("topics");
        if (!topics.isArray()) return;
        List<String> ts = new ArrayList<>(topics.size());
        for (JsonNode t : topics) ts.add(text(t));
        decoder.decode(text(log, "address"), ts, text(log, "data"), text(log, "transactionHash"),
                (int) quantityLong(text(log, "logIndex")), ref.number(), Optional.ofNullable(text(log, "blockHash")).orElse(ref.hash()),
                ref.timestamp(), out);
    }

    /** ERC20 Transfer only: 3 topics (ERC721 has 4: indexed tokenId) and a 32-byte data word. */
    Optional<TokenTransfer> decode(JsonNode log, Instant ts) {
        JsonNode topics = log.path("topics");
        if (!topics.isArray() || topics.size() != 3 || !isTransferTopic(text(topics.get(0)))) return Optional.empty();
        String data = strip0x(text(log, "data"));
        if (data == null || data.length() != 64) return Optional.empty();
        Token token = tokens.resolve(network, text(log, "address"));
        return Optional.of(new TokenTransfer(network, text(log, "transactionHash"), (int) quantityLong(text(log, "logIndex")),
                quantityLong(text(log, "blockNumber")), text(log, "blockHash"), ts,
                new Address(network, "0x" + topicToAddressHex(text(topics.get(1)))),
                new Address(network, "0x" + topicToAddressHex(text(topics.get(2)))),
                token, new BigInteger(data, 16)));
    }

    @Override public Optional<Boolean> isContract(Address address, long atBlock) {
        if (address.network() != network) throw new ProviderException("address network mismatch", false);
        return Optional.of(contractTypes.get(address, atBlock, () -> {
            String code;
            try {
                code = text(rpc.call("eth_getCode", address.value(), atBlock >= 0 ? toQuantity(atBlock) : "latest"));
            } catch (JsonRpcException e) {
                if (e.retryable() || atBlock < 0) throw e;
                // e.g. "missing trie node" / "header not found": state of that block pruned (non-archive node)
                code = text(rpc.call("eth_getCode", address.value(), "latest"));
            }
            String h = strip0x(code);
            return h != null && !h.isEmpty() && !h.chars().allMatch(c -> c == '0');
        }));
    }

    // ---- freeze backfill ----------------------------------------------------------------------------------------

    @Override public long backfillSafeHead() {
        NetworkStatus st = getNetworkStatus();
        if (!st.healthy()) throw new ProviderException(id + " unhealthy: " + st.detail(), true);
        return st.finalizedBlock();
    }

    @Override public List<TokenFreeze> scanFreezes(long fromBlock, long toBlock) {
        var sources = freezeSources.forNetwork(network);
        if (sources.isEmpty() || toBlock < fromBlock) return List.of();
        ObjectNode filter = Json.MAPPER.createObjectNode();
        filter.put("fromBlock", toQuantity(fromBlock));
        filter.put("toBlock", toQuantity(toBlock));
        ArrayNode addresses = filter.putArray("address");
        sources.forEach(s -> addresses.add(s.contract()));
        ArrayNode topic0 = filter.putArray("topics").addArray();
        freezeSources.topics(network).forEach(topic0::add);
        JsonNode logs;
        try {
            logs = rpc.call("eth_getLogs", filter);
        } catch (JsonRpcException e) {
            if (e.rangeTooLarge()) throw new RangeTooLargeException(id + ": eth_getLogs " + fromBlock + ".." + toBlock + ": " + e.rpcMessage(), e);
            throw e;
        }
        if (!logs.isArray()) throw new ProviderException(id + ": eth_getLogs returned non-array", true);
        LogDecoder.Decoded d = new LogDecoder.Decoded();
        for (JsonNode log : logs) {
            if (log.path("removed").asBoolean(false)) continue;
            JsonNode topics = log.path("topics");
            if (!topics.isArray()) continue;
            List<String> ts = new ArrayList<>(topics.size());
            for (JsonNode t : topics) ts.add(text(t));
            String bh = text(log, "blockHash");
            Instant time = present(log.path("blockTimestamp")) ? Instant.ofEpochSecond(quantityLong(text(log, "blockTimestamp")))
                    : timestampOf(bh);
            decoder.decode(text(log, "address"), ts, text(log, "data"), text(log, "transactionHash"),
                    (int) quantityLong(text(log, "logIndex")), quantityLong(text(log, "blockNumber")), bh, time, d);
        }
        return List.copyOf(d.freezes);
    }

    @Override public List<Balance> getAddressBalance(Address address) {
        if (address.network() != network) throw new ProviderException("address network mismatch", false);
        long head = headBlock();
        String tag = toQuantity(head);
        Instant now = Instant.now();
        List<Balance> out = new ArrayList<>();
        out.add(new Balance(address, nativeToken, quantity(text(rpc.call("eth_getBalance", address.value(), tag))), head, now));
        for (Token t : tokens.known(network)) {
            ObjectNode call = Json.MAPPER.createObjectNode();
            call.put("to", t.contract());
            call.put("data", BALANCE_OF + padAddress(address.value()));
            String res = text(rpc.call("eth_call", call, tag));
            BigInteger v = res == null || strip0x(res).isEmpty() ? BigInteger.ZERO : quantity(res);
            if (v.signum() > 0) out.add(new Balance(address, t, v, head, now));
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
            long finalized = finalizedBlock(head);
            return new NetworkStatus(network, head, finalized, true, id, (System.nanoTime() - t0) / 1_000_000, Instant.now(), "ok");
        } catch (ProviderException e) {
            return new NetworkStatus(network, 0, 0, false, id, (System.nanoTime() - t0) / 1_000_000, Instant.now(), e.getMessage());
        }
    }

    private long finalizedBlock(long head) {
        long fallback = Math.max(0, head - config.confirmations());
        if (!config.useFinalizedTag()) return fallback;
        try {
            JsonNode b = rpc.call("eth_getBlockByNumber", "finalized", false);
            return present(b) ? quantityLong(text(b, "number")) : fallback;
        } catch (ProviderException e) {
            if (e.retryable()) throw e;
            return fallback; // tag unsupported by this node
        }
    }
}
