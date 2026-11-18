package io.openchain.intelligence.simulation;

import static io.openchain.intelligence.trace.TraceEngineTest.a;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.flow.InMemoryFlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.InMemoryLabelRepository;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.core.model.Token;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-0023 amendment 2: "who would be exposed if X were listed?" is SIMULATED, carries its hypothesis, and stores nothing. */
class WhatIfTest {
    static final Token USD = new Token(io.openchain.core.model.Network.ETHEREUM, "0xusd0000000000000000000000000000000000001", "USDT", 0);
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    final InMemoryFlowStore flows = new InMemoryFlowStore();
    final InMemoryLabelRepository labels = new InMemoryLabelRepository();
    final Address x = a("ee"), b = a("b0b"), c = a("c0c"), d = a("d0d"), payer = a("9a7e"), far = a("fa7");
    int seq;

    void flow(Address from, Address to, long amount, int minute) {
        String tx = "0x%064x".formatted(++seq);
        flows.add(new ValueFlow("ethereum:" + tx + ":0", from.network(), tx, T0.plusSeconds(60L * minute), from, to, USD,
                BigInteger.valueOf(amount)));
    }

    DetectionConfig.Exposure cfg(int hops) {
        var ex = DetectionConfig.defaults().exposure();
        return new DetectionConfig.Exposure(hops, ex.hopDecay(), 0.0, ex.maxPaths(), ex.maxVisitedFlows(), ex.stopAt(), Map.of("USDT", BigDecimal.ONE));
    }

    @Test void counterpartiesAndDownstreamWalletsWouldBeExposedAndNothingIsStored() {
        flow(payer, x, 500, 0); // payer sent to x: direct exposure (both directions)
        flow(x, b, 300, 1);     // b received from x: direct
        flow(b, c, 200, 2);     // c received value that came from x: indirect, 2 hops
        flow(c, d, 100, 3);     // d: 3 hops, beyond a depth of 2
        flow(far, d, 50, 4);    // unrelated
        int before = labels.size();

        WhatIf.Result r = WhatIf.exposedIfListed(x, LabelCategory.SANCTIONED, flows, labels, cfg(2), 100);
        assertThat(r.level()).isEqualTo("SIMULATED");
        assertThat(r.hypothesis().address()).isEqualTo(x);
        assertThat(r.hypothesis().category()).isEqualTo(LabelCategory.SANCTIONED);
        assertThat(r.exposed()).extracting(e -> e.address().value() + ":" + e.hops())
                .containsExactlyInAnyOrder(payer.value() + ":1", b.value() + ":1", c.value() + ":2");
        assertThat(r.exposed()).allMatch(e -> e.level().equals("SIMULATED"));
        assertThat(r.truncated()).isFalse();
        assertThat(labels.size()).as("the hypothesis is never stored").isEqualTo(before);
        assertThat(labels.findByAddress(x)).isEmpty();

        assertThat(WhatIf.exposedIfListed(x, LabelCategory.SANCTIONED, flows, labels, cfg(3), 100).exposed())
                .extracting(e -> e.address()).contains(d);
    }

    @Test void walletsAlreadyExposedThroughARealLabelAreTold() {
        labels.save(new AddressLabel(x, LabelCategory.SCAM, "real", "community", 0.6, T0, io.openchain.core.detection.Signal.EvidenceLevel.RISK_SIGNAL, "ref"));
        flow(x, b, 300, 1);
        WhatIf.Result r = WhatIf.exposedIfListed(x, LabelCategory.SANCTIONED, flows, labels, cfg(2), 100);
        assertThat(r.exposed()).singleElement().satisfies(e -> {
            assertThat(e.address()).isEqualTo(b);
            assertThat(e.alreadyExposedTo()).containsExactly("SCAM");
        });
    }

    @Test void aCandidateLimitIsStatedAsTruncation() {
        for (int i = 0; i < 30; i++) flow(x, a("1" + i), 10, i);
        WhatIf.Result r = WhatIf.exposedIfListed(x, LabelCategory.SANCTIONED, flows, labels, cfg(2), 10);
        assertThat(r.truncated()).isTrue();
        assertThat(r.exposed()).hasSizeLessThanOrEqualTo(10);
    }
}
