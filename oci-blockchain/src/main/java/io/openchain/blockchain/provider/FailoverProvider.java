package io.openchain.blockchain.provider;

import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.core.provider.ProviderException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Wraps several providers of the SAME network (independent RPC sources). Calls go to the current preferred
 * delegate; on a retryable failure the next one is tried and becomes preferred on success (sticky failover).
 * Permanent failures (bad request, unsupported) are rethrown immediately: another source would not help.
 */
public final class FailoverProvider implements BlockchainProvider {
    private final String id;
    private final Network network;
    private final List<BlockchainProvider> delegates;
    private final Duration pollInterval;
    private volatile int preferred;

    public FailoverProvider(String id, List<BlockchainProvider> delegates) { this(id, delegates, Duration.ofSeconds(3)); }

    public FailoverProvider(String id, List<BlockchainProvider> delegates, Duration pollInterval) {
        if (delegates == null || delegates.isEmpty()) throw new IllegalArgumentException("at least one delegate required");
        Network n = delegates.getFirst().network();
        for (BlockchainProvider d : delegates) {
            if (d.network() != n) throw new IllegalArgumentException("all delegates must target " + n + ", got " + d.id());
        }
        this.id = id;
        this.network = n;
        this.delegates = List.copyOf(delegates);
        this.pollInterval = pollInterval;
    }

    /** Id of the delegate currently preferred (observability). */
    public String activeDelegateId() { return delegates.get(preferred).id(); }

    private <T> T call(String op, Function<BlockchainProvider, T> fn) {
        int start = preferred;
        ProviderException last = null;
        for (int i = 0; i < delegates.size(); i++) {
            int idx = (start + i) % delegates.size();
            try {
                T r = fn.apply(delegates.get(idx));
                if (idx != preferred) preferred = idx;
                return r;
            } catch (ProviderException e) {
                if (!e.retryable()) throw e;
                last = e;
            }
        }
        throw new ProviderException(id + ": all " + delegates.size() + " sources failed for " + op
                + " (last: " + last.getMessage() + ")", true, last);
    }

    @Override public String id() { return id; }
    @Override public Network network() { return network; }
    @Override public Optional<BlockRef> getBlock(long number) { return call("getBlock", p -> p.getBlock(number)); }
    @Override public Optional<ChainTransaction> getTransaction(String hash) { return call("getTransaction", p -> p.getTransaction(hash)); }
    @Override public Optional<TransactionReceipt> getTransactionReceipt(String hash) { return call("getTransactionReceipt", p -> p.getTransactionReceipt(hash)); }
    @Override public Page<ChainTransaction> getAddressTransactions(Address a, String cursor, int limit) {
        return call("getAddressTransactions", p -> p.getAddressTransactions(a, cursor, limit));
    }
    @Override public List<TokenTransfer> getTokenTransfers(long from, long to) { return call("getTokenTransfers", p -> p.getTokenTransfers(from, to)); }
    @Override public List<Balance> getAddressBalance(Address a) { return call("getAddressBalance", p -> p.getAddressBalance(a)); }

    /** First healthy status starting from the preferred delegate; the last unhealthy one otherwise. */
    @Override public NetworkStatus getNetworkStatus() {
        int start = preferred;
        NetworkStatus last = null;
        for (int i = 0; i < delegates.size(); i++) {
            int idx = (start + i) % delegates.size();
            NetworkStatus s;
            try {
                s = delegates.get(idx).getNetworkStatus();
            } catch (ProviderException e) {
                s = new NetworkStatus(network, 0, 0, false, delegates.get(idx).id(), 0, java.time.Instant.now(), e.getMessage());
            }
            if (s.healthy()) { preferred = idx; return s; }
            last = s;
        }
        return last;
    }

    @Override public Subscription subscribeToEvents(Consumer<BlockRef> onNewHead) {
        return new PollingSubscription(id, pollInterval, () -> {
            NetworkStatus s = getNetworkStatus();
            if (!s.healthy()) throw new ProviderException("no healthy source", true);
            return s.headBlock();
        }, this::getBlock, onNewHead);
    }
}
