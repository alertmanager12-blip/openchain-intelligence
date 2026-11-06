package io.openchain.app.chain;

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
import io.openchain.core.model.TokenTransfer;
import io.openchain.core.provider.BlockchainProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Demo helper: shifts every timestamp of a (simulated) provider by a fixed offset so that a deterministic
 * simulated chain, whose genesis is fixed in the past, looks like it is producing blocks right now.
 */
public final class TimeShiftedProvider implements BlockchainProvider {

    private final BlockchainProvider delegate;
    private final Duration offset;

    public TimeShiftedProvider(BlockchainProvider delegate, Duration offset) {
        this.delegate = delegate;
        this.offset = offset;
    }

    public BlockchainProvider delegate() { return delegate; }

    private Instant s(Instant t) { return t == null ? null : t.plus(offset); }

    private BlockRef s(BlockRef b) { return new BlockRef(b.network(), b.number(), b.hash(), b.parentHash(), s(b.timestamp())); }

    private ChainTransaction s(ChainTransaction t) {
        return new ChainTransaction(t.network(), t.hash(), t.blockNumber(), t.blockHash(), s(t.timestamp()), t.from(), t.to(),
                t.value(), t.fee(), t.success(), t.methodSelector(), t.contractInteraction());
    }

    private TokenTransfer s(TokenTransfer t) {
        return new TokenTransfer(t.network(), t.txHash(), t.logIndex(), t.blockNumber(), t.blockHash(), s(t.timestamp()),
                t.from(), t.to(), t.token(), t.rawAmount());
    }

    @Override public String id() { return delegate.id(); }
    @Override public Network network() { return delegate.network(); }
    @Override public Optional<BlockRef> getBlock(long number) { return delegate.getBlock(number).map(this::s); }
    @Override public Optional<ChainTransaction> getTransaction(String hash) { return delegate.getTransaction(hash).map(this::s); }

    @Override public Optional<TransactionReceipt> getTransactionReceipt(String hash) {
        return delegate.getTransactionReceipt(hash).map(r ->
                new TransactionReceipt(s(r.transaction()), r.transfers().stream().map(this::s).toList(), r.success()));
    }

    @Override public Page<ChainTransaction> getAddressTransactions(Address address, String cursor, int limit) {
        Page<ChainTransaction> p = delegate.getAddressTransactions(address, cursor, limit);
        return new Page<>(p.items().stream().map(this::s).toList(), p.nextCursor());
    }

    @Override public List<TokenTransfer> getTokenTransfers(long fromBlock, long toBlock) {
        return delegate.getTokenTransfers(fromBlock, toBlock).stream().map(this::s).toList();
    }

    @Override public BlockActivity getBlockActivity(long fromBlock, long toBlock) {
        BlockActivity a = delegate.getBlockActivity(fromBlock, toBlock);
        return new BlockActivity(a.tokenTransfers().stream().map(this::s).toList(), a.nativeTransfers().stream().map(this::s).toList(),
                a.approvals().stream().map(x -> new TokenApproval(x.network(), x.txHash(), x.logIndex(), x.blockNumber(), x.blockHash(),
                        s(x.timestamp()), x.owner(), x.spender(), x.token(), x.rawAmount())).toList(),
                a.freezes().stream().map(x -> new TokenFreeze(x.network(), x.txHash(), x.logIndex(), x.blockNumber(), x.blockHash(),
                        s(x.timestamp()), x.target(), x.token(), x.issuer(), x.action(), x.eventName())).toList(),
                a.adminChanges().stream().map(x -> new io.openchain.core.model.ContractAdminChange(x.network(), x.txHash(), x.logIndex(),
                        x.blockNumber(), x.blockHash(), s(x.timestamp()), x.contract(), x.role(), x.previous(), x.newValue(),
                        x.eventName())).toList());
    }

    @Override public Optional<Boolean> isContract(Address address, long atBlock) { return delegate.isContract(address, atBlock); }

    @Override public List<Balance> getAddressBalance(Address address) {
        return delegate.getAddressBalance(address).stream()
                .map(b -> new Balance(b.address(), b.token(), b.rawAmount(), b.atBlock(), s(b.observedAt()))).toList();
    }

    @Override public Subscription subscribeToEvents(Consumer<BlockRef> onNewHead) {
        return delegate.subscribeToEvents(b -> onNewHead.accept(s(b)));
    }

    @Override public NetworkStatus getNetworkStatus() {
        NetworkStatus st = delegate.getNetworkStatus();
        return new NetworkStatus(st.network(), st.headBlock(), st.finalizedBlock(), st.healthy(), st.providerId(),
                st.latencyMillis(), st.headBlock() == 0 && !st.healthy() ? st.checkedAt() : s(st.checkedAt()), st.detail());
    }
}
