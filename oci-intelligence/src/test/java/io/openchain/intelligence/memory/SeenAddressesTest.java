package io.openchain.intelligence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import org.junit.jupiter.api.Test;

class SeenAddressesTest {
    static Address a(Network n, int i) { return new Address(n, String.format("0x%040x", i)); }

    @Test void neverReportsASeenAddressAsNewAndKeepsErrorsNearOnePercentAtDesignLoad() {
        int n = 100_000;
        SeenAddresses seen = new SeenAddresses((long) (n * 9.6));
        for (int i = 0; i < n; i++) seen.add(a(Network.ETHEREUM, i));
        for (int i = 0; i < n; i++) assertThat(seen.mightContain(a(Network.ETHEREUM, i))).isTrue(); // no false "new"
        int wrong = 0;
        for (int i = n; i < 2 * n; i++) if (seen.mightContain(a(Network.ETHEREUM, i))) wrong++;
        assertThat(wrong / (double) n).isLessThan(0.02);
        assertThat(seen.falsePositiveRate()).isLessThan(0.02);
        assertThat(seen.inserted()).isBetween((long) (n * 0.98), (long) n);
        assertThat(seen.sizeBytes()).isLessThan(130_000);
    }

    @Test void theSameValueOnAnotherNetworkIsAnotherAddress() {
        SeenAddresses seen = new SeenAddresses(1 << 16);
        seen.add(a(Network.ETHEREUM, 7));
        assertThat(seen.mightContain(a(Network.ETHEREUM, 7))).isTrue();
        assertThat(seen.mightContain(a(Network.POLYGON, 7))).isFalse();
        assertThat(seen.add(a(Network.ETHEREUM, 7))).isTrue();   // already there
        assertThat(seen.add(a(Network.POLYGON, 7))).isFalse();   // new
        assertThat(seen.inserted()).isEqualTo(2);
    }
}
