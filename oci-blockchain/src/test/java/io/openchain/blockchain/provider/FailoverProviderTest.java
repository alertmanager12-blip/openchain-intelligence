package io.openchain.blockchain.provider;

import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.core.model.Network;
import io.openchain.core.provider.ProviderException;
import java.util.List;
import org.junit.jupiter.api.Test;

class FailoverProviderTest {
    SimulatedChainProvider a = new SimulatedChainProvider("a", Network.ETHEREUM, 1, 3, 5, new DefaultTokenRegistry());
    SimulatedChainProvider b = new SimulatedChainProvider("b", Network.ETHEREUM, 1, 3, 5, new DefaultTokenRegistry());
    FailoverProvider fp = new FailoverProvider("eth", List.of(a, b));

    @Test void failsOverOnRetryableAndSticksToHealthySource() {
        a.mine(5); b.mine(5);
        a.failNext(10, true);
        assertThat(fp.getBlock(3)).isPresent();
        assertThat(fp.activeDelegateId()).isEqualTo("b");
        b.failNext(1, true); // b fails once while a is still down -> no source left
        assertThatThrownBy(() -> fp.getBlock(3)).isInstanceOf(ProviderException.class);
        assertThat(fp.getBlock(3)).isPresent(); // b recovered
        assertThat(fp.activeDelegateId()).isEqualTo("b");
    }

    @Test void permanentErrorsAreNotFailedOver() {
        a.failNext(1, false);
        assertThatThrownBy(() -> fp.getBlock(0)).isInstanceOf(ProviderException.class).hasMessageContaining("injected");
        assertThat(fp.activeDelegateId()).isEqualTo("a");
    }

    @Test void allSourcesDownRaisesRetryable() {
        a.failNext(1, true);
        b.failNext(1, true);
        assertThatThrownBy(() -> fp.getBlock(0)).isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).retryable()).isTrue())
                .hasMessageContaining("all 2 sources failed");
    }

    @Test void statusPicksFirstHealthySource() {
        a.failNext(1, true);
        assertThat(fp.getNetworkStatus().providerId()).isEqualTo("b");
    }

    @Test void rejectsMixedNetworks() {
        assertThatThrownBy(() -> new FailoverProvider("x", List.of(a, new SimulatedChainProvider(Network.TRON, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
