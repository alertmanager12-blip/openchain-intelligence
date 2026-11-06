package io.openchain.blockchain.simulated;

import io.openchain.blockchain.evm.EvmCodec;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.blockchain.tron.TronAddress;
import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.core.provider.ProviderException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Deterministic in-memory chain: same seed + same sequence of {@link #mine}/{@link #reorg} calls = same blocks,
 * hashes and transfers. For demos and tests. Supports reorg and failure injection. Thread-safe.
 */
public final class SimulatedChainProvider implements BlockchainProvider {
    private record SimBlock(BlockRef ref, List<ChainTransaction> txs, List<TokenTransfer> transfers) {}

    private final String id;
    private final Network network;
    private final long seed;
    private final int maxTransfersPerBlock;
    private final Instant genesisTime;
    private final Duration blockTime;
    private final List<Address> addresses;
    private final List<Token> tokens;
    private final List<SimBlock> chain = new ArrayList<>();
    private final List<Consumer<BlockRef>> listeners = new CopyOnWriteArrayList<>();
    private int fork;
    private int failuresToInject;
    private boolean injectRetryable;
    private final Map<Long, Integer> duplicateTransfersAt = new HashMap<>();

    public SimulatedChainProvider(Network network, long seed) {
        this("sim-" + network.id(), network, seed, 4, 12, new DefaultTokenRegistry());
    }

    public SimulatedChainProvider(String id, Network network, long seed, int maxTransfersPerBlock, int addressPoolSize,
                                  TokenRegistry registry) {
        this.id = id;
        this.network = network;
        this.seed = seed;
        this.maxTransfersPerBlock = maxTransfersPerBlock;
        this.genesisTime = Instant.parse("2026-01-01T00:00:00Z");
        this.blockTime = network == Network.TRON ? Duration.ofSeconds(3) : Duration.ofSeconds(12);
        SplittableRandom rnd = new SplittableRandom(seed);
        List<Address> pool = new ArrayList<>();
        for (int i = 0; i < addressPoolSize; i++) {
            byte[] b = new byte[20];
            for (int j = 0; j < 20; j++) b[j] = (byte) rnd.nextInt(256);
            pool.add(addressOf(EvmCodec.encodeHex(b)));
        }
        this.addresses = List.copyOf(pool);
        List<Token> known = new ArrayList<>(registry.known(network));
        known.sort(Comparator.comparing(Token::contract));
        this.tokens = known.isEmpty() ? List.of(new Token(network, pool.getFirst().value(), "SIM", 6)) : List.copyOf(known);
        appendBlocks(1); // genesis = block 0
    }

    private Address addressOf(String hex40) {
        return switch (network) {
            case TRON -> new Address(network, TronAddress.fromHex(hex40));
            case ETHEREUM, BSC, POLYGON -> new Address(network, "0x" + hex40);
            default -> new Address(network, "sim" + hex40);
        };
    }

    // ---- simulation control ------------------------------------------------------------------------------------

    /** Mines {@code count} blocks on the canonical chain and notifies subscribers. */
    public void mine(int count) {
        List<BlockRef> added;
        synchronized (this) { added = appendBlocks(count); }
        for (BlockRef b : added) for (Consumer<BlockRef> l : listeners) l.accept(b);
    }

    /** Replaces the last {@code depth} blocks with a new fork of {@code newLength} blocks (different hashes/transfers). */
    public synchronized void reorg(int depth, int newLength) {
        if (depth < 1 || depth >= chain.size()) throw new IllegalArgumentException("invalid reorg depth");
        for (int i = 0; i < depth; i++) chain.removeLast();
        fork++;
        appendBlocks(newLength);
    }

    public void reorg(int depth) { reorg(depth, depth); }

    /** The next {@code count} provider calls fail with a (non-)retryable ProviderException. */
    public synchronized void failNext(int count, boolean retryable) {
        this.failuresToInject = count;
        this.injectRetryable = retryable;
    }

    /** Makes getTokenTransfers return each transfer of the block twice (provider-side duplicate). */
    public synchronized void duplicateTransfersOf(long blockNumber) { duplicateTransfersAt.put(blockNumber, 2); }

    public synchronized long head() { return chain.getLast().ref().number(); }

    public List<Address> addressPool() { return addresses; }

    private List<BlockRef> appendBlocks(int count) {
        List<BlockRef> out = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            long number = chain.size();
            String parent = chain.isEmpty() ? hashHex("genesis-parent", seed, 0, 0) : EvmCodec.strip0x(chain.getLast().ref().hash());
            String hash = hashHex(parent, seed, number, fork);
            Instant ts = genesisTime.plus(blockTime.multipliedBy(number));
            BlockRef ref = new BlockRef(network, number, fmt(hash), fmt(parent), ts);
            SplittableRandom rnd = new SplittableRandom(seed ^ new BigInteger(hash.substring(0, 15), 16).longValue());
            List<ChainTransaction> txs = new ArrayList<>();
            List<TokenTransfer> transfers = new ArrayList<>();
            int n = number == 0 ? 0 : rnd.nextInt(maxTransfersPerBlock + 1);
            for (int i = 0; i < n; i++) {
                Address from = addresses.get(rnd.nextInt(addresses.size()));
                Address to = addresses.get(rnd.nextInt(addresses.size()));
                Token token = tokens.get(rnd.nextInt(tokens.size()));
                BigInteger raw = BigInteger.valueOf(rnd.nextLong(1, 5_000_000L)).multiply(BigInteger.TEN.pow(token.decimals()))
                        .divide(BigInteger.valueOf(100));
                String txHash = fmt(hashHex(hash, seed, i, fork));
                txs.add(new ChainTransaction(network, txHash, number, ref.hash(), ts, from, new Address(network, token.contract()),
                        BigInteger.ZERO, BigInteger.valueOf(rnd.nextLong(1_000, 100_000)), true, "0xa9059cbb", true));
                transfers.add(new TokenTransfer(network, txHash, i, number, ref.hash(), ts, from, to, token, raw));
            }
            chain.add(new SimBlock(ref, List.copyOf(txs), List.copyOf(transfers)));
            out.add(ref);
        }
        return out;
    }

    private String fmt(String hex) { return network == Network.TRON ? hex : "0x" + hex; }

    private static String hashHex(String parent, long seed, long number, int fork) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(parent.getBytes(StandardCharsets.UTF_8));
            md.update(ByteBuffer.allocate(20).putLong(seed).putLong(number).putInt(fork).array());
            return EvmCodec.encodeHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized void maybeFail(String op) {
        if (failuresToInject > 0) {
            failuresToInject--;
            throw new ProviderException(id + ": injected failure on " + op, injectRetryable);
        }
    }

    // ---- BlockchainProvider -----------------------------------------------------------------------------------

    @Override public String id() { return id; }
    @Override public Network network() { return network; }

    @Override public synchronized Optional<BlockRef> getBlock(long number) {
        maybeFail("getBlock");
        return number >= 0 && number < chain.size() ? Optional.of(chain.get((int) number).ref()) : Optional.empty();
    }

    @Override public synchronized Optional<ChainTransaction> getTransaction(String hash) {
        maybeFail("getTransaction");
        return getTransactionReceipt0(hash).map(TransactionReceipt::transaction);
    }

    @Override public synchronized Optional<TransactionReceipt> getTransactionReceipt(String hash) {
        maybeFail("getTransactionReceipt");
        return getTransactionReceipt0(hash);
    }

    private Optional<TransactionReceipt> getTransactionReceipt0(String hash) {
        for (SimBlock b : chain) {
            for (int i = 0; i < b.txs().size(); i++) {
                if (b.txs().get(i).hash().equals(hash)) {
                    return Optional.of(new TransactionReceipt(b.txs().get(i), List.of(b.transfers().get(i)), true));
                }
            }
        }
        return Optional.empty();
    }

    @Override public synchronized Page<ChainTransaction> getAddressTransactions(Address address, String cursor, int limit) {
        maybeFail("getAddressTransactions");
        List<ChainTransaction> all = new ArrayList<>();
        for (SimBlock b : chain) {
            for (int i = 0; i < b.txs().size(); i++) {
                TokenTransfer t = b.transfers().get(i);
                if (t.from().equals(address) || t.to().equals(address)) all.add(b.txs().get(i));
            }
        }
        int offset = cursor == null ? 0 : Integer.parseInt(cursor);
        int end = Math.min(all.size(), offset + Math.max(1, limit));
        return new Page<>(List.copyOf(all.subList(Math.min(offset, end), end)), end < all.size() ? Integer.toString(end) : null);
    }

    @Override public synchronized List<TokenTransfer> getTokenTransfers(long fromBlock, long toBlock) {
        maybeFail("getTokenTransfers");
        List<TokenTransfer> out = new ArrayList<>();
        for (long n = Math.max(0, fromBlock); n <= toBlock && n < chain.size(); n++) {
            List<TokenTransfer> ts = chain.get((int) n).transfers();
            for (int r = 0; r < duplicateTransfersAt.getOrDefault(n, 1); r++) out.addAll(ts);
        }
        return List.copyOf(out);
    }

    /** Net flow per known token from a notional opening balance of 0 (may be negative: it is a simulation). */
    @Override public synchronized List<Balance> getAddressBalance(Address address) {
        maybeFail("getAddressBalance");
        Map<Token, BigInteger> net = new LinkedHashMap<>();
        for (SimBlock b : chain) {
            for (TokenTransfer t : b.transfers()) {
                if (t.to().equals(address)) net.merge(t.token(), t.rawAmount(), BigInteger::add);
                if (t.from().equals(address)) net.merge(t.token(), t.rawAmount().negate(), BigInteger::add);
            }
        }
        long h = head();
        Instant now = chain.getLast().ref().timestamp();
        return net.entrySet().stream().map(e -> new Balance(address, e.getKey(), e.getValue(), h, now)).toList();
    }

    @Override public Subscription subscribeToEvents(Consumer<BlockRef> onNewHead) {
        listeners.add(onNewHead);
        return () -> listeners.remove(onNewHead);
    }

    @Override public synchronized NetworkStatus getNetworkStatus() {
        try {
            maybeFail("getNetworkStatus");
        } catch (ProviderException e) {
            return new NetworkStatus(network, 0, 0, false, id, 0, Instant.now(), e.getMessage());
        }
        long h = head();
        return new NetworkStatus(network, h, Math.max(0, h - network.defaultConfirmations()), true, id, 0,
                chain.getLast().ref().timestamp(), "simulated");
    }
}
