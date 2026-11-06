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
}
