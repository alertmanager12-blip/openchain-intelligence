package io.openchain.app.chain;

import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.core.model.Address;
import io.openchain.core.model.Balance;
import io.openchain.core.model.BlockRef;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.Network;
import io.openchain.core.model.NetworkStatus;
import io.openchain.core.model.Page;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.core.provider.BlockchainProvider;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Demo-only ledger over {@link SimulatedChainProvider}. The simulator draws random transfers without any notion of
 * balance, so wallets end up "sending" more than they ever received (negative balances, balance ratios > 1, noisy
 * {@code wallet-draining} alerts). This wrapper makes the demo chain balance-consistent without touching
 * oci-blockchain:
 * <ul>
 *   <li>block 1 carries a funding transfer from a fictional faucet to every pool address, for every token;</li>
 *   <li>a simulated transfer that would overdraw its sender is dropped (deterministic, processed in block order);</li>
 *   <li>scenario transfers can be injected into a future (not yet processed) block; they carry the block hash so the
 *       indexer accepts them, and may be backdated (historical activity) for dormancy scenarios.</li>
 * </ul>
 * Balances, address transactions and receipts are answered from this ledger. Not reorg-aware: demo use only.
 */
public final class DemoLedgerProvider implements BlockchainProvider {

    /** A transfer to add to a block; {@code backdate} shifts its timestamp into the past (historical activity). */
    public record Injection(String txHash, Address from, Address to, Token token, BigInteger rawAmount, Duration backdate) {}

    private record Entry(ChainTransaction tx, TokenTransfer transfer) {}

    private final SimulatedChainProvider sim;
    private final Address faucet;
    private final BigInteger fundingUnits;
    private final Map<Long, List<Injection>> injections = new HashMap<>();
    private final Map<Long, List<Entry>> blocks = new HashMap<>();
    private final Map<String, List<Entry>> byTx = new HashMap<>();
    private final Map<Address, List<Entry>> byAddress = new HashMap<>();
    private final Map<Address, Map<Token, BigInteger>> balances = new HashMap<>();
    private long processedUpTo = 0;
    private long dropped;

    /**
     * @param faucet       fictional funding source (exempt from balance checks)
     * @param fundingUnits whole token units given to each pool address for each token at block 1
     */
    public DemoLedgerProvider(SimulatedChainProvider sim, Address faucet, long fundingUnits) {
        this.sim = sim;
        this.faucet = faucet;
        this.fundingUnits = BigInteger.valueOf(fundingUnits);
    }

    public Address faucet() { return faucet; }
    public SimulatedChainProvider simulator() { return sim; }
    public synchronized long droppedOverdrafts() { return dropped; }
    public synchronized long processedUpTo() { return processedUpTo; }

    /** Adds a transfer to block {@code blockNumber}, which must not have been processed yet. */
    public synchronized void inject(long blockNumber, Injection i) {
        if (blockNumber <= processedUpTo) throw new IllegalStateException("block " + blockNumber + " already processed");
        injections.computeIfAbsent(blockNumber, k -> new ArrayList<>()).add(i);
    }

    /** Deterministic fictional transaction hash for scenario transfers. */
    public String hashOf(String seed) {
        String hex = sha256(seed);
        return sim.network() == Network.TRON ? hex : "0x" + hex;
    }

    // ---- ledger -------------------------------------------------------------------------------------------------

    private void ensure(long upTo) {
        long head = sim.head();
        for (long n = processedUpTo + 1; n <= Math.min(upTo, head); n++) {
            BlockRef ref = sim.getBlock(n).orElseThrow();
            List<Entry> out = new ArrayList<>();
            int logIndex = 10_000; // ledger-generated transfers get their own log index range
            if (n == 1) {
                List<Token> tokens = sim.getTokenTransfers(1, Math.min(head, 200)).stream().map(TokenTransfer::token).distinct().toList();
                for (Address a : sim.addressPool()) {
                    for (Token t : tokens) {
                        BigInteger raw = fundingUnits.multiply(BigInteger.TEN.pow(t.decimals()));
                        String hash = hashOf("fund:" + a.value() + ":" + t.symbol());
                        out.add(apply(entry(ref, hash, logIndex++, faucet, a, t, raw, ref.timestamp())));
                    }
                }
            }
            for (TokenTransfer t : sim.getTokenTransfers(n, n)) {
                if (!t.from().equals(faucet) && balance(t.from(), t.token()).compareTo(t.rawAmount()) < 0) {
                    dropped++;
                    continue;
                }
                out.add(apply(entry(ref, t.txHash(), t.logIndex(), t.from(), t.to(), t.token(), t.rawAmount(), ref.timestamp())));
            }
            for (Injection i : injections.getOrDefault(n, List.of())) {
                Instant ts = i.backdate() == null ? ref.timestamp() : ref.timestamp().minus(i.backdate());
                out.add(apply(entry(ref, i.txHash(), logIndex++, i.from(), i.to(), i.token(), i.rawAmount(), ts)));
            }
            blocks.put(n, out);
            processedUpTo = n;
        }
    }

    private Entry entry(BlockRef ref, String hash, int logIndex, Address from, Address to, Token token, BigInteger raw, Instant ts) {
        ChainTransaction tx = new ChainTransaction(sim.network(), hash, ref.number(), ref.hash(), ts, from,
                token.contract() == null ? to : new Address(sim.network(), token.contract()), BigInteger.ZERO,
                BigInteger.valueOf(1_000), true, "0xa9059cbb", token.contract() != null);
        return new Entry(tx, new TokenTransfer(sim.network(), hash, logIndex, ref.number(), ref.hash(), ts, from, to, token, raw));
    }

    private Entry apply(Entry e) {
        TokenTransfer t = e.transfer();
        if (!t.from().equals(faucet)) balances.computeIfAbsent(t.from(), k -> new LinkedHashMap<>()).merge(t.token(), t.rawAmount().negate(), BigInteger::add);
        balances.computeIfAbsent(t.to(), k -> new LinkedHashMap<>()).merge(t.token(), t.rawAmount(), BigInteger::add);
        byTx.computeIfAbsent(t.txHash(), k -> new ArrayList<>()).add(e);
        byAddress.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(e);
        if (!t.to().equals(t.from())) byAddress.computeIfAbsent(t.to(), k -> new ArrayList<>()).add(e);
        return e;
    }

    private BigInteger balance(Address a, Token t) {
        return balances.getOrDefault(a, Map.of()).getOrDefault(t, BigInteger.ZERO);
    }

    // ---- BlockchainProvider -------------------------------------------------------------------------------------

    @Override public String id() { return sim.id(); }
    @Override public Network network() { return sim.network(); }
    @Override public Optional<BlockRef> getBlock(long number) { return sim.getBlock(number); }

    @Override public synchronized Optional<ChainTransaction> getTransaction(String hash) {
        return getTransactionReceipt(hash).map(TransactionReceipt::transaction);
    }

    @Override public synchronized Optional<TransactionReceipt> getTransactionReceipt(String hash) {
        ensure(sim.head());
        List<Entry> es = byTx.get(hash);
        if (es == null || es.isEmpty()) return Optional.empty();
        return Optional.of(new TransactionReceipt(es.getFirst().tx(), es.stream().map(Entry::transfer).toList(), true));
    }

    /** Newest first; cursor = offset. */
    @Override public synchronized Page<ChainTransaction> getAddressTransactions(Address address, String cursor, int limit) {
        ensure(sim.head());
        List<Entry> all = byAddress.getOrDefault(address, List.of());
        List<ChainTransaction> txs = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = all.size() - 1; i >= 0; i--) if (seen.add(all.get(i).tx().hash())) txs.add(all.get(i).tx());
        int offset = cursor == null ? 0 : Integer.parseInt(cursor);
        int end = Math.min(txs.size(), offset + Math.max(1, limit));
        int from = Math.min(offset, end);
        return new Page<>(List.copyOf(txs.subList(from, end)), end < txs.size() ? Integer.toString(end) : null);
    }

    @Override public synchronized List<TokenTransfer> getTokenTransfers(long fromBlock, long toBlock) {
        ensure(toBlock);
        List<TokenTransfer> out = new ArrayList<>();
        for (long n = Math.max(1, fromBlock); n <= toBlock; n++) for (Entry e : blocks.getOrDefault(n, List.of())) out.add(e.transfer());
        return out;
    }

    @Override public synchronized List<Balance> getAddressBalance(Address address) {
        ensure(sim.head());
        long head = sim.head();
        Instant at = sim.getBlock(head).map(BlockRef::timestamp).orElse(Instant.EPOCH);
        return balances.getOrDefault(address, Map.of()).entrySet().stream()
                .map(e -> new Balance(address, e.getKey(), e.getValue(), head, at)).toList();
    }

    @Override public Subscription subscribeToEvents(Consumer<BlockRef> onNewHead) { return sim.subscribeToEvents(onNewHead); }

    @Override public NetworkStatus getNetworkStatus() { return sim.getNetworkStatus(); }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
