package io.openchain.app.chain;

import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.core.model.Address;
import io.openchain.core.model.Balance;
import io.openchain.core.model.BlockActivity;
import io.openchain.core.model.TokenApproval;
import io.openchain.core.model.TokenFreeze;
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
 *   <li>block 1 carries a funding transfer from a fictional faucet to every pool address, for every token and for the
 *       native coin;</li>
 *   <li>a simulated token or native transfer that would overdraw its sender is dropped (deterministic, processed in
 *       block order);</li>
 *   <li>routine simulated transfers get realistic sizes: the simulator draws amounts uniformly up to 50,000 tokens, so
 *       young demo wallets routinely moved 100k+ USDT an hour and low-balance wallets were swept several times an hour
 *       (hundreds of {@code suspicious-wallet} / {@code wallet-draining} alerts on a long-running demo). The ledger keeps
 *       {@value #LARGE_TRANSFER_PERCENT}% of them as drawn (occasional large payments), scales the others down by
 *       {@value #ROUTINE_SCALE_DOWN}, and never lets a routine transfer send more than 1/{@value #MAX_SHARE_DIVISOR} of
 *       the sender's current balance (routine payments do not empty a wallet). Deterministic (derived from the tx hash).
 *       Scenario transfers are injected as scripted, unchanged;</li>
 *   <li>on networks with fast blocks (TRON: 3 s) only a share of the routine simulated transfers is kept, so that each
 *       demo wallet transacts about as often as on Ethereum (12 s blocks) instead of several times a minute (which
 *       made chance fan-outs of 10+ recipients in 3 minutes, i.e. {@code fund-splitting} alerts, routine);</li>
 *   <li>routine simulated transfers get realistic sizes: the simulator draws amounts uniformly up to 50,000 tokens,
 *       so young demo wallets routinely moved 100k+ USDT an hour and wallets were swept empty several times an hour
 *       (hundreds of {@code suspicious-wallet} / {@code wallet-draining} alerts on a long-running demo). The ledger keeps
 *       {@value #LARGE_TRANSFER_PERCENT}% of them as drawn (occasional large payments) and scales the others down by
 *       {@value #ROUTINE_SCALE_DOWN}; no routine transfer sends more than 1/{@value #MAX_SHARE_DIVISOR} of the sender's
 *       current balance (people do not empty their wallet in routine payments). Deterministic (derived from the tx
 *       hash). Scenario transfers are injected as scripted, unchanged;</li>
 *   <li>simulated approvals, scheduled freezes and scheduled contract admin changes are passed through; scenario approvals and issuer freezes can be
 *       injected like scenario transfers;</li>
 *   <li>scenario transfers can be injected into a future (not yet processed) block; they carry the block hash so the
 *       indexer accepts them, and may be backdated (historical activity) for dormancy scenarios.</li>
 * </ul>
 * Balances, address transactions and receipts are answered from this ledger. Not reorg-aware: demo use only.
 */
public final class DemoLedgerProvider implements BlockchainProvider {

    /** A transfer to add to a block; {@code backdate} shifts its timestamp into the past (historical activity). */
    public record Injection(String txHash, Address from, Address to, Token token, BigInteger rawAmount, Duration backdate) {}

    private record Entry(ChainTransaction tx, TokenTransfer transfer) {}

    /** An approval to add to a block. */
    public record ApprovalInjection(String txHash, Address owner, Address spender, Token token, BigInteger rawAmount) {}

    /** An issuer freeze/unfreeze to add to a block (as the real adapters decode it from the official contract). */
    public record FreezeInjection(String txHash, Address target, Token token, String issuer, TokenFreeze.Action action, String eventName) {}

    /** Share of routine simulated transfers kept at their drawn size (the others are scaled down). */
    static final int LARGE_TRANSFER_PERCENT = 3;
    static final int ROUTINE_SCALE_DOWN = 25;
    /** A routine simulated transfer never sends more than balance / MAX_SHARE_DIVISOR. */
    static final int MAX_SHARE_DIVISOR = 4;

    /** Percentage of routine simulated transfers kept on fast-block networks (TRON); 100 elsewhere. */
    static final int FAST_CHAIN_KEEP_PERCENT = 25;

    /** Whole native units given to each pool address at block 1. */
    public static final long NATIVE_FUNDING_UNITS = 1_000;

    private final SimulatedChainProvider sim;
    private final Address faucet;
    private final BigInteger fundingUnits;
    private final Map<Long, List<Injection>> injections = new HashMap<>();
    private final Map<Long, List<Entry>> blocks = new HashMap<>();
    private final Map<Long, List<ApprovalInjection>> approvalInjections = new HashMap<>();
    private final Map<Long, List<FreezeInjection>> freezeInjections = new HashMap<>();
    private final Map<Long, List<ChainTransaction>> nativeBlocks = new HashMap<>();
    private final Map<Long, List<TokenApproval>> approvalBlocks = new HashMap<>();
    private final Map<Long, List<TokenFreeze>> freezeBlocks = new HashMap<>();
    private final Map<Long, List<io.openchain.core.model.ContractAdminChange>> adminBlocks = new HashMap<>();
    private final Map<String, ChainTransaction> nativeByTx = new HashMap<>();
    private final Map<String, List<Entry>> byTx = new HashMap<>();
    private final Map<Address, List<Entry>> byAddress = new HashMap<>();
    private final Map<Address, Map<Token, BigInteger>> balances = new HashMap<>();
    private long processedUpTo = 0;
    private long dropped;
    private long thinned;

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

    /** Adds an approval to block {@code blockNumber}, which must not have been processed yet. */
    public synchronized void injectApproval(long blockNumber, ApprovalInjection i) {
        if (blockNumber <= processedUpTo) throw new IllegalStateException("block " + blockNumber + " already processed");
        approvalInjections.computeIfAbsent(blockNumber, k -> new ArrayList<>()).add(i);
    }

    /** Adds an issuer freeze/unfreeze to block {@code blockNumber}, which must not have been processed yet. */
    public synchronized void injectFreeze(long blockNumber, FreezeInjection i) {
        if (blockNumber <= processedUpTo) throw new IllegalStateException("block " + blockNumber + " already processed");
        freezeInjections.computeIfAbsent(blockNumber, k -> new ArrayList<>()).add(i);
    }

    /** Freezes/unfreezes indexed for a transaction (scenario evidence). */
    public synchronized List<TokenFreeze> freezesOf(String txHash) {
        ensure(sim.head());
        return freezeBlocks.values().stream().flatMap(List::stream).filter(z -> z.txHash().equals(txHash)).toList();
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
            BlockActivity simActivity = sim.getBlockActivity(n, n);
            Token nat = sim.nativeToken();
            List<ChainTransaction> natives = new ArrayList<>();
            if (n == 1) {
                for (Address a : sim.addressPool()) {
                    BigInteger raw = BigInteger.valueOf(NATIVE_FUNDING_UNITS).multiply(BigInteger.TEN.pow(nat.decimals()));
                    natives.add(applyNative(new ChainTransaction(sim.network(), hashOf("fund-native:" + a.value()), n, ref.hash(),
                            ref.timestamp(), faucet, a, raw, BigInteger.valueOf(1_000), true, null, false)));
                }
            }
            for (ChainTransaction t : simActivity.nativeTransfers()) {
                if (!t.from().equals(faucet) && thinned(t.hash())) continue;
                BigInteger value = t.from().equals(faucet) ? t.value() : routine(t.hash(), t.value(), balance(t.from(), nat));
                if (value.signum() <= 0) {
                    dropped++;
                    continue;
                }
                natives.add(applyNative(value.equals(t.value()) ? t : new ChainTransaction(t.network(), t.hash(), t.blockNumber(),
                        t.blockHash(), t.timestamp(), t.from(), t.to(), value, t.fee(), t.success(), t.methodSelector(),
                        t.contractInteraction())));
            }
            nativeBlocks.put(n, natives);
            List<TokenApproval> approvals = new ArrayList<>(simActivity.approvals());
            List<TokenFreeze> freezes = new ArrayList<>(simActivity.freezes());
            for (TokenTransfer t : simActivity.tokenTransfers()) {
                if (!t.from().equals(faucet) && thinned(t.txHash())) continue;
                BigInteger raw = t.from().equals(faucet) ? t.rawAmount() : routine(t.txHash(), t.rawAmount(), balance(t.from(), t.token()));
                if (raw.signum() <= 0) {
                    dropped++;
                    continue;
                }
                out.add(apply(entry(ref, t.txHash(), t.logIndex(), t.from(), t.to(), t.token(), raw, ref.timestamp())));
            }
            for (Injection i : injections.getOrDefault(n, List.of())) {
                Instant ts = i.backdate() == null ? ref.timestamp() : ref.timestamp().minus(i.backdate());
                out.add(apply(entry(ref, i.txHash(), logIndex++, i.from(), i.to(), i.token(), i.rawAmount(), ts)));
            }
            for (ApprovalInjection i : approvalInjections.getOrDefault(n, List.of())) {
                approvals.add(new TokenApproval(sim.network(), i.txHash(), logIndex++, n, ref.hash(), ref.timestamp(), i.owner(),
                        i.spender(), i.token(), i.rawAmount()));
            }
            for (FreezeInjection i : freezeInjections.getOrDefault(n, List.of())) {
                freezes.add(new TokenFreeze(sim.network(), i.txHash(), logIndex++, n, ref.hash(), ref.timestamp(), i.target(),
                        i.token(), i.issuer(), i.action(), i.eventName()));
            }
            approvalBlocks.put(n, approvals);
            freezeBlocks.put(n, freezes);
            adminBlocks.put(n, simActivity.adminChanges());
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

    private ChainTransaction applyNative(ChainTransaction t) {
        Token nat = sim.nativeToken();
        if (!t.from().equals(faucet)) balances.computeIfAbsent(t.from(), k -> new LinkedHashMap<>()).merge(nat, t.value().negate(), BigInteger::add);
        balances.computeIfAbsent(t.to(), k -> new LinkedHashMap<>()).merge(nat, t.value(), BigInteger::add);
        nativeByTx.put(t.hash(), t);
        return t;
    }

    /**
     * Realistic size of a routine simulated transfer (see class doc): kept as drawn for a few, scaled down for the others,
     * and never more than balance / {@link #MAX_SHARE_DIVISOR}. Zero when the sender cannot afford any of it (dropped).
     */
    /** True when a routine simulated transfer is left out to keep a realistic per-wallet rate (fast-block networks). */
    private boolean thinned(String txHash) {
        if (sim.network() != Network.TRON) return false;
        if (Math.floorMod(txHash.hashCode() * 31 + 7, 100) < FAST_CHAIN_KEEP_PERCENT) return false;
        thinned++;
        return true;
    }

    /** Routine simulated transfers left out by {@link #thinned} (realistic per-wallet rate). */
    public synchronized long thinnedTransfers() { return thinned; }

    static BigInteger routine(String txHash, BigInteger drawn, BigInteger balance) {
        int bucket = Math.floorMod(txHash.hashCode(), 100);
        BigInteger v = bucket < LARGE_TRANSFER_PERCENT ? drawn : drawn.divide(BigInteger.valueOf(ROUTINE_SCALE_DOWN));
        BigInteger cap = balance.divide(BigInteger.valueOf(MAX_SHARE_DIVISOR));
        if (v.compareTo(cap) > 0) v = cap;
        return v.signum() > 0 ? v : BigInteger.ZERO;
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
        if (es == null || es.isEmpty()) {
            ChainTransaction nat = nativeByTx.get(hash);
            return nat == null ? Optional.empty() : Optional.of(new TransactionReceipt(nat, List.of(), true));
        }
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

    @Override public synchronized BlockActivity getBlockActivity(long fromBlock, long toBlock) {
        ensure(toBlock);
        List<TokenTransfer> transfers = new ArrayList<>();
        List<ChainTransaction> natives = new ArrayList<>();
        List<TokenApproval> approvals = new ArrayList<>();
        List<TokenFreeze> freezes = new ArrayList<>();
        List<io.openchain.core.model.ContractAdminChange> admins = new ArrayList<>();
        for (long n = Math.max(1, fromBlock); n <= toBlock; n++) {
            for (Entry e : blocks.getOrDefault(n, List.of())) transfers.add(e.transfer());
            natives.addAll(nativeBlocks.getOrDefault(n, List.of()));
            approvals.addAll(approvalBlocks.getOrDefault(n, List.of()));
            freezes.addAll(freezeBlocks.getOrDefault(n, List.of()));
            admins.addAll(adminBlocks.getOrDefault(n, List.of()));
        }
        return new BlockActivity(transfers, natives, approvals, freezes, admins);
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
