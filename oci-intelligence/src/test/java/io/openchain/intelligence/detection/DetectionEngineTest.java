package io.openchain.intelligence.detection;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.intelligence.Fx;
import io.openchain.intelligence.flow.ValueFlow;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetectionEngineTest {
    final Fx fx = new Fx();
    final Address me = a("me"), src = a("5ac"), dst = a("d57");

    @Test void signalIdsAreDeterministic() {
        assertThat(SignalIds.of("x@1", me, "0xabc")).isEqualTo(SignalIds.of("x@1", me, "0xabc"))
                .isNotEqualTo(SignalIds.of("x@2", me, "0xabc"))
                .isNotEqualTo(SignalIds.of("x@1", src, "0xabc"))
                .isNotEqualTo(SignalIds.of("x@1", me, "0xabd"));
    }

    @Test void rerunningOnSameDataProducesNoNewSignals() {
        var engine = DetectionEngine.withDefaultDetectors(new InMemorySignalRepository());
        fx.flow(src, me, "1000", min(0));
        ValueFlow out = fx.flow(me, dst, "1000", min(2));
        DetectionResult first = engine.run(fx.ctx(me, out));
        assertThat(first.newSignals()).extracting(Signal::type).contains(SignalType.FAST_FORWARDING);
        DetectionResult second = engine.run(fx.ctx(me, out));
        assertThat(second.newSignals()).isEmpty();
        assertThat(second.duplicatesSuppressed()).isEqualTo(first.newSignals().size());
    }

    @Test void windowedSignalsAreDedupedAcrossTriggersInSameWindow() {
        var repo = new InMemorySignalRepository();
        var engine = new DetectionEngine(List.of(new io.openchain.intelligence.detection.detectors.StructuringDetector()), repo);
        fx.flow(me, a("1"), "9500", min(0));
        fx.flow(me, a("2"), "9600", min(1));
        List<Signal> all = new ArrayList<>();
        all.addAll(engine.run(fx.ctx(me, fx.flow(me, a("3"), "9700", min(2)))).newSignals());
        all.addAll(engine.run(fx.ctx(me, fx.flow(me, a("4"), "9800", min(3)))).newSignals());
        assertThat(all).hasSize(1);
        assertThat(repo.findByAddress(me)).hasSize(1);
    }

    @Test void failingDetectorIsIsolated() {
        Detector boom = new Detector() {
            public String id() { return "boom"; }
            public int version() { return 1; }
            public List<Signal> detect(DetectionContext c) { throw new IllegalStateException("provider down"); }
        };
        var engine = new DetectionEngine(List.of(boom, new io.openchain.intelligence.detection.detectors.FastForwardingDetector()),
                new InMemorySignalRepository());
        fx.flow(src, me, "1000", min(0));
        DetectionResult r = engine.run(fx.ctx(me, fx.flow(me, dst, "1000", min(1))));
        assertThat(r.detectorErrors()).containsKey("boom@1");
        assertThat(r.newSignals()).hasSize(1);
    }
}
