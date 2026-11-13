package io.openchain.intelligence.flow;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Removal by event id (chain reorganisation) is indexed: it touches the lists of the two parties only (code review I16). */
class InMemoryFlowStoreTest {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    static Address a(String v) { return new Address(Network.TRON, v); }
    static ValueFlow flow(String id, Address from, Address to, int sec) {
        return new ValueFlow(id, Network.TRON, "tx-" + id, T0.plusSeconds(sec), from, to, USDT, BigInteger.TEN);
    }

    @Test void removeTakesTheFlowOutOfBothPartiesAndLeavesTheOthersUntouched() {
        InMemoryFlowStore s = new InMemoryFlowStore();
        Address x = a("X"), y = a("Y"), z = a("Z");
        ValueFlow xy = flow("1", x, y, 0), yz = flow("2", y, z, 1), xz = flow("3", x, z, 2), self = flow("4", z, z, 3);
        for (ValueFlow f : new ValueFlow[] {xy, yz, xz, self}) assertThat(s.add(f)).isTrue();

        assertThat(s.remove("1")).contains(xy);
        assertThat(s.contains("1")).isFalse();
        assertThat(s.size()).isEqualTo(3);
        assertThat(s.outgoing(x)).containsExactly(xz);
        assertThat(s.incoming(y)).isEmpty();
        assertThat(s.outgoing(y)).containsExactly(yz);
        assertThat(s.incoming(z)).containsExactly(yz, xz, self);

        assertThat(s.remove("1")).isEmpty();            // idempotent
        assertThat(s.remove("unknown")).isEmpty();
        assertThat(s.remove("4")).contains(self);       // self-transfer: in both lists of the same address
        assertThat(s.outgoing(z)).isEmpty();
        assertThat(s.incoming(z)).containsExactly(yz, xz);
        assertThat(s.add(xy)).isTrue();                 // the id is forgotten: a re-included transfer is a new flow
        assertThat(s.incoming(y)).containsExactly(xy);
    }

    @Test void evictionForgetsTheIdsItRemoves() {
        InMemoryFlowStore s = new InMemoryFlowStore();
        Address x = a("X"), y = a("Y");
        s.add(flow("old", x, y, 0));
        s.add(flow("new", x, y, 1_000));
        assertThat(s.evictBefore(n -> T0.plusSeconds(500), k -> false)).isEqualTo(1);
        assertThat(s.contains("old")).isFalse();
        assertThat(s.contains("new")).isTrue();
        assertThat(s.remove("old")).isEmpty();
        assertThat(s.size()).isEqualTo(1);
    }
}
