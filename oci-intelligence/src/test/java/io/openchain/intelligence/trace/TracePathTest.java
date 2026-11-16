package io.openchain.intelligence.trace;

import static io.openchain.intelligence.trace.TraceEngineTest.a;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import org.junit.jupiter.api.Test;

/** Fund Flow Trace P0-C, T9: a directed, chronological path in one token, or a reasoned "no path". */
class TracePathTest {
    final TraceEngineTest.Src src = new TraceEngineTest.Src();
    final Address A = a("a"), B = a("b"), C = a("c"), D = a("d");

    TracePath.Result path(Address from, Address to, int hops) {
        return TracePath.find(src, from, to, Trace.Window.ALL, null, hops, 20_000, 5_000);
    }

    @Test void aDirectedChronologicalPathIsFoundWithItsTransactions() {
        ValueFlow ab = src.flow(A, B, 100, 1), bc = src.flow(B, C, 80, 2), cd = src.flow(C, D, 60, 3);
        TracePath.Result r = path(A, D, 6);
        assertThat(r.outcome()).isEqualTo(TracePath.Outcome.FOUND);
        assertThat(r.hops()).containsExactly(ab, bc, cd);
    }

    @Test void anUndirectedConnectionIsNoPath() {
        src.flow(A, B, 100, 1);
        src.flow(D, B, 100, 2); // D only sent to B: no value of A can reach D
        assertThat(path(A, D, 6).outcome()).isEqualTo(TracePath.Outcome.NO_PATH);
    }

    @Test void aHopEarlierThanTheValueIsNoPath() {
        src.flow(B, C, 80, 1);
        src.flow(A, B, 100, 2);
        assertThat(path(A, C, 6).outcome()).isEqualTo(TracePath.Outcome.NO_PATH);
    }

    @Test void thePathStaysInOneToken() {
        Token eth = Token.nativeOf(Network.ETHEREUM, "ETH", 0);
        src.flow(A, B, 100, 1);
        src.flow(B, C, 1, eth, 2);
        assertThat(path(A, C, 6).outcome()).isEqualTo(TracePath.Outcome.NO_PATH);
    }

    @Test void theEarliestArrivalIsChosenAndTheResultIsStable() {
        src.flow(A, B, 100, 1); src.flow(B, D, 50, 5);
        ValueFlow ac = src.flow(A, C, 100, 1), cd = src.flow(C, D, 50, 3);
        assertThat(path(A, D, 6).hops()).containsExactly(ac, cd);
        java.util.Collections.reverse(src.flows);
        assertThat(path(A, D, 6).hops()).containsExactly(ac, cd);
    }

    @Test void aLimitIsSaidNotHiddenAsNoPath() {
        src.flow(A, B, 100, 1); src.flow(B, C, 100, 2); src.flow(C, D, 100, 3);
        TracePath.Result r = path(A, D, 2);
        assertThat(r.outcome()).isEqualTo(TracePath.Outcome.LIMIT_REACHED);
        assertThat(r.stops()).anyMatch(s -> s.reason() == Trace.StopReason.DEPTH);
    }
}
