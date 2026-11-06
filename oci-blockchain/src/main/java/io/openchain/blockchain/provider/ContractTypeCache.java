package io.openchain.blockchain.provider;

import io.openchain.core.model.Address;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Bounded LRU of "is this address a contract" answers, with lookup counters (RPC cost observability).
 * <ul>
 *   <li>"contract" answers are kept until evicted: deployed code does not go away (since EIP-6780, SELFDESTRUCT only
 *       removes code in the creating transaction);</li>
 *   <li>"no code" answers are reused only for {@code noCodeTtlBlocks} blocks after the block they were checked at: an
 *       address without code can receive one later (CREATE2 counterfactual deployments), and a lookup for an OLDER
 *       block than the cached one is answered from the cache as well (no code then either).</li>
 * </ul>
 * Thread-safe.
 */
public final class ContractTypeCache {

    private record Known(boolean contract, long checkedAtBlock) {}

    /** Counters since start: {@code lookups = hits + remoteCalls}; failed remote calls are counted too. */
    public record Stats(long lookups, long hits, long remoteCalls, long failures, int size) {}

    private final int maxEntries;
    private final long noCodeTtlBlocks;
    private final Map<Address, Known> entries;
    private final AtomicLong lookups = new AtomicLong(), hits = new AtomicLong(), remote = new AtomicLong(), failures = new AtomicLong();

    public ContractTypeCache(int maxEntries, long noCodeTtlBlocks) {
        if (maxEntries < 1 || noCodeTtlBlocks < 0) throw new IllegalArgumentException("invalid cache bounds");
        this.maxEntries = maxEntries;
        this.noCodeTtlBlocks = noCodeTtlBlocks;
        this.entries = new LinkedHashMap<>(256, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Address, Known> e) { return size() > ContractTypeCache.this.maxEntries; }
        };
    }

    /** 10 000 addresses, "no code" answers reused for 300 blocks (~1 h on Ethereum, ~15 min on TRON). */
    public static ContractTypeCache defaults() { return new ContractTypeCache(10_000, 300); }

    /**
     * Cached answer, or {@code remote.get()} (stored). {@code atBlock < 0} means "now" (no block-aware source): the
     * no-code TTL then never expires by block and such answers are only reused while cached.
     */
    public boolean get(Address address, long atBlock, Supplier<Boolean> remoteLookup) {
        lookups.incrementAndGet();
        synchronized (entries) {
            Known e = entries.get(address);
            if (e != null && (e.contract() || atBlock <= e.checkedAtBlock() + noCodeTtlBlocks)) {
                hits.incrementAndGet();
                return e.contract();
            }
        }
        remote.incrementAndGet();
        boolean contract;
        try {
            contract = remoteLookup.get();
        } catch (RuntimeException ex) {
            failures.incrementAndGet();
            throw ex;
        }
        synchronized (entries) { entries.put(address, new Known(contract, Math.max(atBlock, 0))); }
        return contract;
    }

    public Optional<Boolean> peek(Address address) {
        synchronized (entries) { return Optional.ofNullable(entries.get(address)).map(Known::contract); }
    }

    public Stats stats() {
        int size;
        synchronized (entries) { size = entries.size(); }
        return new Stats(lookups.get(), hits.get(), remote.get(), failures.get(), size);
    }
}
