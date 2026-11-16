package io.openchain.intelligence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CapsTest {
    @Test void underTheCapNothingGoes() { assertThat(Caps.cutoff(new long[] {1, 2, 3}, 3)).isNull(); }

    @Test void theOldestGo() { assertThat(Caps.cutoff(new long[] {1, 2, 3, 4}, 2)).isEqualTo(Instant.ofEpochMilli(3)); }

    @Test void aSharedBoundaryInstantGoesWholeSoTheCapHolds() {
        // keeping "3 and later" would leave 4 items for a cap of 3: the instant 3 goes too
        assertThat(Caps.cutoff(new long[] {1, 3, 3, 4, 5}, 3)).isEqualTo(Instant.ofEpochMilli(4));
    }

    @Test void zeroCapRemovesEverything() { assertThat(Caps.cutoff(new long[] {1, 2}, 0)).isEqualTo(Instant.ofEpochMilli(3)); }
}
