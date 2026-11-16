package io.openchain.intelligence.trace;

import static io.openchain.intelligence.trace.TraceEngineTest.a;
import static io.openchain.intelligence.trace.TraceEngineTest.at;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.intelligence.trace.Trace.Direction;
import io.openchain.intelligence.trace.Trace.FindingType;
import org.junit.jupiter.api.Test;

/** Fund Flow Trace P0-D, second part: scenarios 3, 4, 6, 7 of docs/FUND_FLOW_TRACE_P0_D_ENGINE.md § 5, and loops. */
class TracePatternsTest {
    final TraceEngineTest.Src src = new TraceEngineTest.Src();
    final Address A = a("a"), B = a("b"), C = a("c"), D = a("d"), E = a("e"), S = a("5"), X = a("ee");

    static long count(Trace.Result r, FindingType t) { return r.findings().stream().filter(f -> f.type() == t).count(); }

    Trace.Result fwd(Address seed, int depth) {
        return TraceEngine.run(Trace.Request.of(seed, Direction.FORWARD).withLimits(new Trace.Limits(depth, 500, 20_000)), src);
    }

    /** 3: three recipients, shares 30/30/40, no dispersion; 25 recipients: dispersion. */
    @Test void s3Dispersion() {
        src.flow(A, B, 30, 1); src.flow(A, C, 30, 1); src.flow(A, D, 40, 1);
        Trace.Result r = fwd(A, 1);
        assertThat(at(r, B).high()).isEqualTo(Fraction.of(30));
        assertThat(at(r, D).high()).isEqualTo(Fraction.of(40));
        assertThat(count(r, FindingType.DISPERSION)).isZero();
        for (int i = 0; i < 22; i++) src.flow(A, a("d1" + i), 10, 2);
        Trace.Result many = fwd(A, 1);
        assertThat(many.findings()).filteredOn(f -> f.type() == FindingType.DISPERSION).singleElement()
                .satisfies(f -> { assertThat(f.level()).isEqualTo("INFERRED"); assertThat(f.addresses()).containsExactly(A); assertThat(f.eventIds()).hasSize(25); });
    }

    /** 4: three sources 30/30/40, no consolidation; 25 sources: consolidation. */
    @Test void s4Consolidation() {
        src.flow(B, A, 30, 1); src.flow(C, A, 30, 1); src.flow(D, A, 40, 1);
        Trace.Result r = TraceEngine.run(Trace.Request.of(A, Direction.BACKWARD), src);
        assertThat(at(r, C).high()).isEqualTo(Fraction.of(30));
        assertThat(count(r, FindingType.CONSOLIDATION)).isZero();
        for (int i = 0; i < 22; i++) src.flow(a("c1" + i), A, 10, 2);
        assertThat(count(TraceEngine.run(Trace.Request.of(A, Direction.BACKWARD), src), FindingType.CONSOLIDATION)).isEqualTo(1);
    }

    /** 6: value that comes back to the seed: one circulation finding, the trace ends there. */
    @Test void s6CirculationThroughTheSeed() {
        src.flow(A, B, 100, 1); src.flow(B, C, 100, 2); src.flow(C, A, 100, 3);
        Trace.Result r = fwd(A, 4);
        assertThat(r.findings()).filteredOn(f -> f.type() == FindingType.CIRCULATION).singleElement()
                .satisfies(f -> assertThat(f.addresses()).containsExactly(A, B, C));
        assertThat(r.stops()).anyMatch(s -> s.reason() == Trace.StopReason.CYCLE);
        assertThat(r.attributions()).extracting(Trace.Attribution::address).containsExactlyInAnyOrder(B, C);
    }

    /** A loop that does not go through the seed: the value goes on, but is not received twice by the same address. */
    @Test void aLoopIsNotCountedTwice() {
        src.flow(S, A, 100, 1); src.flow(A, B, 100, 2); src.flow(B, A, 100, 3); src.flow(A, X, 100, 4);
        Trace.Result r = fwd(S, 4);
        assertThat(at(r, A).high()).as("A received S's 100 once").isEqualTo(Fraction.of(100));
        assertThat(at(r, X).high()).as("the value that went round still reaches X").isEqualTo(Fraction.of(100));
        assertThat(count(r, FindingType.CIRCULATION)).isEqualTo(1);
        assertThat(r.status()).isEqualTo(Trace.Status.PARTIAL);
    }

    /** 7: three regular detachments of 50: peel chain, exact parts; irregular detachments: none. */
    @Test void s7PeelChain() {
        Address p1 = a("p1"), p2 = a("p2"), p3 = a("p3");
        src.flow(A, B, 1_000, 1);
        src.flow(B, C, 950, 2); src.flow(B, p1, 50, 2);
        src.flow(C, D, 900, 3); src.flow(C, p2, 50, 3);
        src.flow(D, E, 850, 4); src.flow(D, p3, 50, 4);
        Trace.Result r = fwd(A, 4);
        assertThat(r.findings()).filteredOn(f -> f.type() == FindingType.PEEL_CHAIN).singleElement()
                .satisfies(f -> { assertThat(f.addresses()).containsExactly(B, C, D); assertThat(f.eventIds()).hasSize(6); });
        assertThat(at(r, p1).high()).isEqualTo(Fraction.of(50));
        assertThat(at(r, p3).high()).isEqualTo(Fraction.of(50));
        assertThat(at(r, E).high()).isEqualTo(Fraction.of(850));

        TraceEngineTest.Src irregular = new TraceEngineTest.Src();
        irregular.flow(A, B, 1_000, 1);
        irregular.flow(B, C, 950, 2); irregular.flow(B, p1, 50, 2);
        irregular.flow(C, D, 940, 3); irregular.flow(C, p2, 10, 3);
        irregular.flow(D, E, 790, 4); irregular.flow(D, p3, 150, 4);
        Trace.Result ri = TraceEngine.run(Trace.Request.of(A, Direction.FORWARD).withLimits(new Trace.Limits(4, 500, 20_000)), irregular);
        assertThat(count(ri, FindingType.PEEL_CHAIN)).isZero();
    }
}
