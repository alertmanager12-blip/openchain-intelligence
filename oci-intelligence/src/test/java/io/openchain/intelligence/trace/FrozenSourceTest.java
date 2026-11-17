package io.openchain.intelligence.trace;

import static io.openchain.intelligence.trace.TraceEngineTest.a;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.trace.Trace.Direction;
import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-0022 amendment 1: the engine run again on the frozen evidence gives the same result, whatever the live data became. */
class FrozenSourceTest {
    final TraceEngineTest.Src src = new TraceEngineTest.Src();
    final Address A = a("a"), B = a("b"), C = a("c"), D = a("d"), E = a("e"), X = a("ee");

    void world() {
        src.flow(A, B, 100, 1); src.flow(B, C, 60, 2); src.flow(B, D, 40, 3); src.flow(C, E, 30, 4);
        src.flow(X, B, 25, 2); // dilutes B
        src.flow(A, C, 7, Token.nativeOf(Network.ETHEREUM, "ETH", 0), 5);
        src.label(D, LabelCategory.EXCHANGE);   // stop category
        src.incomplete.add(C);                  // coverage gap
        src.opening.put(B, Optional.empty());   // unknown opening balance
        src.opening.put(E, Optional.of(BigInteger.TEN));
    }

    @Test void replayOnTheFrozenEvidenceGivesTheSameResult() {
        world();
        for (Direction d : Direction.values()) {
            Address seed = d == Direction.FORWARD ? A : E;
            Trace.Request req = Trace.Request.of(seed, d).withLimits(new Trace.Limits(4, 500, 20_000));
            var recorder = FrozenSource.recording(src);
            Trace.Result live = TraceEngine.run(req, recorder);
            FrozenSource.Snapshot frozen = recorder.snapshot();

            Trace.Result replay = TraceEngine.run(req, new FrozenSource(frozen));
            assertThat(replay).as(d.name()).isEqualTo(live);
            assertThat(replay.toString()).isEqualTo(live.toString());
            assertThat(frozen.flows()).isNotEmpty();
        }
    }

    @Test void theLiveDataChangingDoesNotChangeTheReplay() {
        world();
        Trace.Request req = Trace.Request.of(A, Direction.FORWARD).withLimits(new Trace.Limits(4, 500, 20_000));
        var recorder = FrozenSource.recording(src);
        Trace.Result live = TraceEngine.run(req, recorder);
        FrozenSource.Snapshot frozen = recorder.snapshot();

        src.flows.removeIf(f -> f.from().equals(B) && f.to().equals(C)); // a reorganisation later
        src.flow(B, a("ff"), 50, 3);
        assertThat(TraceEngine.run(req, src)).isNotEqualTo(live);
        assertThat(TraceEngine.run(req, new FrozenSource(frozen))).isEqualTo(live);
    }
}
