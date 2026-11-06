package io.openchain.blockchain.evm;

import static io.openchain.blockchain.evm.EvmCodec.*;
import static io.openchain.blockchain.transport.Json.present;
import static io.openchain.blockchain.transport.Json.text;

import io.openchain.blockchain.provider.PollingSubscription;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.blockchain.transport.Json;
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

/** Standard Ethereum JSON-RPC adapter (Ethereum, BSC, Polygon). Vendor-neutral: any compliant node URL works. */
public final class EvmJsonRpcProvider implements BlockchainProvider {
    private static final Set<Network> SUPPORTED = EnumSet.of(Network.ETHEREUM, Network.BSC, Network.POLYGON);
    private static final int TS_CACHE = 2048;

    private final String id;
    private final Network network;
    private final JsonRpcClient rpc;
    private final TokenRegistry tokens;
    private final EvmConfig config;
    private final Token nativeToken;
    private final Map<String, Instant> timestampByBlockHash = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Instant> e) { return size() > TS_CACHE; }
            });

    public EvmJsonRpcProvider(String id, Network network, JsonRpcClient rpc, TokenRegistry tokens, EvmConfig config) {
        if (!SUPPORTED.contains(network)) throw new IllegalArgumentException(network + " is not an EVM network");
        this.id = id;
        this.network = network;
        this.rpc = rpc;
        this.tokens = tokens;
        this.config = config;
        this.nativeToken = Token.nativeOf(network, config.nativeSymbol(), config.nativeDecimals());
    }

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
