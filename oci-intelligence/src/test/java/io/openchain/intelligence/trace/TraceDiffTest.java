package io.openchain.intelligence.trace;

import static io.openchain.intelligence.trace.TraceEngineTest.a;
import static io.openchain.intelligence.trace.TraceEngineTest.at;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.trace.Trace.Direction;
import org.junit.jupiter.api.Test;

/** Fund Flow Trace P0-D, third part: scenarios 14 (reorganisation) and 15 (re-inclusion). */
class TraceDiffTest {
    final Address A = a("a"), B = a("b"), C = a("c");

    static Trace.Result fwd(TraceEngineTest.Src src) { return TraceEngine.run(Trace.Request.of(a("a"), Direction.FORWARD), src); }

    /** 14: the flow B -> C left the chain: the second run stops at B, without phantom data, and the diff says what went. */
    @Test void s14AFlowRemovedByAReorganisation() {
        TraceEngineTest.Src first = new TraceEngineTest.Src();
        first.flow(A, B, 100, 1);
        ValueFlow bc = first.flow(B, C, 100, 2);
        Trace.Result r1 = fwd(first);

        TraceEngineTest.Src second = new TraceEngineTest.Src();
        second.flows.add(first.flows.get(0));
        Trace.Result r2 = fwd(second);
        assertThat(at(r2, C)).isNull();
        assertThat(r2.edges()).extracting(Trace.Edge::eventId).doesNotContain(bc.eventId());

        TraceDiff.Diff d = TraceDiff.compare(r1, r2);
        assertThat(d.evidence()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(TraceDiff.Kind.REMOVED);
            assertThat(c.beforeEventId()).isEqualTo(bc.eventId());
        });
        assertThat(d.attributions()).singleElement().satisfies(c -> {
            assertThat(c.address()).isEqualTo(C);
            assertThat(c.beforeHigh()).isEqualTo(Fraction.of(100));
            assertThat(c.afterHigh()).isEqualTo(Fraction.ZERO);
        });
    }

    /** 15: B -> C comes back in another block with another log index: same path and amounts, evidence MOVED. */
    @Test void s15AFlowReincludedElsewhere() {
        TraceEngineTest.Src first = new TraceEngineTest.Src();
        ValueFlow ab = first.flow(A, B, 100, 1);
        ValueFlow bc = first.flow(B, C, 100, 2);
        Trace.Result r1 = fwd(first);

        TraceEngineTest.Src second = new TraceEngineTest.Src();
        second.flows.add(ab);
        ValueFlow moved = new ValueFlow(bc.network().id() + ":" + bc.txHash() + ":7", bc.network(), bc.txHash(), bc.timestamp().plusSeconds(12),
                bc.from(), bc.to(), bc.token(), bc.rawAmount());
        second.flows.add(moved);
        Trace.Result r2 = fwd(second);
        assertThat(at(r2, C).high()).isEqualTo(at(r1, C).high());

        TraceDiff.Diff d = TraceDiff.compare(r1, r2);
        assertThat(d.attributions()).isEmpty();
        assertThat(d.evidence()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(TraceDiff.Kind.MOVED);
            assertThat(c.beforeEventId()).isEqualTo(bc.eventId());
            assertThat(c.afterEventId()).isEqualTo(moved.eventId());
        });
        assertThat(TraceDiff.compare(r1, r1).empty()).isTrue();
    }
}
