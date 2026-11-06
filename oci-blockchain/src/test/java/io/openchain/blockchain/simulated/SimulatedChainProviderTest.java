package io.openchain.blockchain.simulated;

import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.tron.TronAddress;
import io.openchain.core.model.BlockRef;
import io.openchain.core.model.Network;
import io.openchain.core.model.TokenTransfer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatedChainProviderTest {
    @Test void sameSeedSameChain() {
        SimulatedChainProvider a = new SimulatedChainProvider(Network.TRON, 99);
        SimulatedChainProvider b = new SimulatedChainProvider(Network.TRON, 99);
        a.mine(30); b.mine(30);
        assertThat(a.getTokenTransfers(0, 30)).isEqualTo(b.getTokenTransfers(0, 30)).isNotEmpty();
        assertThat(a.getBlock(30)).isEqualTo(b.getBlock(30));
        SimulatedChainProvider c = new SimulatedChainProvider(Network.TRON, 100);
        c.mine(30);
        assertThat(c.getBlock(30)).isNotEqualTo(a.getBlock(30));
    }

    @Test void chainIsLinkedAndAddressesAreValid() {
        SimulatedChainProvider p = new SimulatedChainProvider(Network.TRON, 5);
        p.mine(10);
        for (long n = 1; n <= 10; n++) {
            assertThat(p.getBlock(n).orElseThrow().parentHash()).isEqualTo(p.getBlock(n - 1).orElseThrow().hash());
        }
        for (TokenTransfer t : p.getTokenTransfers(0, 10)) {
            assertThat(TronAddress.isValid(t.from().value())).isTrue();
            assertThat(p.getTransactionReceipt(t.txHash())).isPresent();
        }
    }

    @Test void reorgReplacesOnlyTheTipAndNotifiesSubscribers() {
        SimulatedChainProvider p = new SimulatedChainProvider(Network.ETHEREUM, 5);
        List<BlockRef> heads = new ArrayList<>();
        try (var sub = p.subscribeToEvents(heads::add)) {
            p.mine(10);
        }
        assertThat(heads).hasSize(10);
        BlockRef b7 = p.getBlock(7).orElseThrow(), b8 = p.getBlock(8).orElseThrow();
        p.reorg(3, 4);
        assertThat(p.head()).isEqualTo(11);
        assertThat(p.getBlock(7)).contains(b7);
        assertThat(p.getBlock(8).orElseThrow().hash()).isNotEqualTo(b8.hash());
        assertThat(p.getBlock(8).orElseThrow().parentHash()).isEqualTo(b7.hash());
    }

    @Test void activityIsDeterministicAndIncludesNativeApprovalAndScheduledFreeze() {
        SimulatedChainProvider a = new SimulatedChainProvider(Network.ETHEREUM, 11), b = new SimulatedChainProvider(Network.ETHEREUM, 11);
        for (SimulatedChainProvider p : List.of(a, b)) {
            p.scheduleFreeze(5, p.addressPool().get(1),
                    new io.openchain.core.model.Token(Network.ETHEREUM, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6), "tether", io.openchain.core.model.TokenFreeze.Action.FREEZE, "AddedBlackList");
            p.mine(40);
        }
        var x = a.getBlockActivity(0, 40);
        assertThat(x).isEqualTo(b.getBlockActivity(0, 40));
        assertThat(x.tokenTransfers()).isEqualTo(a.getTokenTransfers(0, 40));
        assertThat(x.nativeTransfers()).isNotEmpty().allSatisfy(t -> {
            assertThat(t.success()).isTrue();
            assertThat(t.value()).isPositive();
            assertThat(a.getTransactionReceipt(t.hash())).isPresent();
        });
        assertThat(x.approvals()).isNotEmpty().anyMatch(io.openchain.core.model.TokenApproval::isUnlimited);
        assertThat(x.freezes()).singleElement().satisfies(z -> assertThat(z.blockNumber()).isEqualTo(5));
        assertThatThrownBy(() -> a.scheduleNative(3, a.addressPool().get(0), a.addressPool().get(1), java.math.BigInteger.ONE))
                .isInstanceOf(IllegalStateException.class);
    }
}
