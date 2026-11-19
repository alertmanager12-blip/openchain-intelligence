package io.openchain.intelligence.exposure;

import static io.openchain.intelligence.Fx.a;
import static io.openchain.intelligence.Fx.min;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Token;
import io.openchain.intelligence.Fx;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression cases R1-R9, R11 and R14 of {@code docs/FUND_FLOW_TRACE_P0_A_EXPOSURE.md} § 4 on the shared qualification of
 * exposure paths (the detector and the endpoint both use it, so R13, "same paths", holds by construction).
 */
class ExposurePathsTest {
    static final Token ETH = Token.nativeOf(Fx.NET, "ETH", 18);
    static final Token FAKE = new Token(Fx.NET, "0xfa4e000000000000000000000000000000000001", Token.UNKNOWN_SYMBOL, 6);

    final Fx fx = new Fx();
    final Address me = a("a11ce"), s = a("5a9c"), s2 = a("5a9c2"), b = a("b0b"), x = a("ec5"), exchange = a("e7c4"), c = a("c0ffee");
    DetectionConfig.Exposure cfg;

    @BeforeEach void listed() {
        fx.label(s, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
        cfg = fx.config.exposure();
    }

    @Test void r1DirectInboundFromAListedAddress() {
        ValueFlow f = fx.flow(s, me, "1000", min(0));
        assertThat(ExposurePaths.direct(me, fx.flows, fx.labels, cfg)).singleElement().satisfies(d -> {
            assertThat(d.label().category()).isEqualTo(LabelCategory.SANCTIONED);
            assertThat(d.flows()).containsExactly(f);
            assertThat(d.ignored()).isEmpty();
        });
    }

    @Test void r2DustIsIgnoredNotExposure() {
        ValueFlow dust = fx.flow(s, me, "1", min(0)); // USDT threshold: 10
        assertThat(ExposurePaths.direct(me, fx.flows, fx.labels, cfg)).singleElement().satisfies(d -> {
            assertThat(d.exposed()).isFalse();
            assertThat(d.ignored()).containsExactly(dust);
        });
    }

    @Test void r3AnUnverifiedTokenIsIgnoredWhateverTheAmount() {
        ValueFlow fake = fx.flow(s, me, "5000", FAKE, min(0));
        assertThat(ExposurePaths.direct(me, fx.flows, fx.labels, cfg)).singleElement().satisfies(d -> {
            assertThat(d.exposed()).isFalse();
            assertThat(d.ignored()).containsExactly(fake);
        });
        assertThat(ExposurePaths.indirect(me, fx.flows, fx.labels, cfg)).isEmpty();
    }

    @Test void r4IndirectThroughOneIntermediaryBoundedByTheSmallestHop() {
        ValueFlow up = fx.flow(s, b, "300", min(0));
        ValueFlow down = fx.flow(b, me, "100", min(10));
        assertThat(ExposurePaths.direct(me, fx.flows, fx.labels, cfg)).isEmpty();
        assertThat(ExposurePaths.indirect(me, fx.flows, fx.labels, cfg)).singleElement().satisfies(i -> {
            assertThat(i.path()).containsExactly(up, down);
            assertThat(i.hops()).isEqualTo(2);
            assertThat(i.bottleneck()).isEqualTo(down);
            assertThat(i.confidence()).isEqualTo(0.5);
        });
    }

    @Test void r5FundsReceivedAfterTheOutflowAreNotItsOrigin() {
        fx.flow(b, me, "100", min(0));
        fx.flow(s, b, "300", min(10));
        assertThat(ExposurePaths.indirect(me, fx.flows, fx.labels, cfg)).isEmpty();
    }

    @Test void r6AmountsOfDifferentTokensAreNeverAdded() {
        fx.flow(s, b, "100", min(0));
        fx.flow(x, b, "1", ETH, min(1));
        ValueFlow down = fx.flow(b, me, "100", min(10));
        assertThat(ExposurePaths.indirect(me, fx.flows, fx.labels, cfg)).singleElement().satisfies(i -> {
            assertThat(i.bottleneck().token().symbol()).isEqualTo("USDT");
            assertThat(i.bottleneck().amount()).isEqualByComparingTo(new BigDecimal("100"));
            assertThat(i.path()).allMatch(f -> f.token().symbol().equals("USDT"));
            assertThat(i.path().getLast()).isEqualTo(down);
        });
    }

    @Test void r7TracingStopsAtAnExchange() {
        fx.label(exchange, LabelCategory.EXCHANGE, 0.9, EvidenceLevel.RISK_SIGNAL);
        fx.flow(s, exchange, "500", min(0));
        fx.flow(exchange, me, "200", min(10));
        assertThat(ExposurePaths.indirect(me, fx.flows, fx.labels, cfg)).isEmpty();
        assertThat(ExposurePaths.direct(me, fx.flows, fx.labels, cfg)).as("an exchange is not a risky category").isEmpty();
    }

    @Test void r8TracingStopsAtTheFirstListedAddressMetUpstream() {
        fx.label(s2, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
        fx.flow(s, s2, "500", min(0));
        fx.flow(s2, b, "400", min(5));
        fx.flow(b, me, "300", min(10));
        assertThat(ExposurePaths.indirect(me, fx.flows, fx.labels, cfg)).singleElement()
                .satisfies(i -> assertThat(i.label().address()).isEqualTo(s2));
    }

    @Test void r9AnOutflowToAnExchangeIsNoRiskExposure() {
        fx.label(exchange, LabelCategory.EXCHANGE, 0.9, EvidenceLevel.RISK_SIGNAL);
        fx.flow(me, exchange, "1000", min(0));
        assertThat(ExposurePaths.direct(me, fx.flows, fx.labels, cfg)).isEmpty();
    }

    @Test void r11AFlowAddedTwiceGivesTheSameResult() {
        ValueFlow f = fx.flow(s, me, "1000", min(0));
        var once = ExposurePaths.direct(me, fx.flows, fx.labels, cfg);
        fx.flows.add(f);
        assertThat(ExposurePaths.direct(me, fx.flows, fx.labels, cfg)).isEqualTo(once);
    }

    /** R14: same timestamp (same block) upstream and downstream is accepted today; no log index in ValueFlow yet (D7). */
    @Test void r14SameTimestampUpstreamIsAcceptedAndDocumented() {
        fx.flow(b, me, "100", min(0));
        fx.flow(s, b, "300", min(0));
        assertThat(ExposurePaths.indirect(me, fx.flows, fx.labels, cfg)).hasSize(1);
    }

    @Test void theHopLimitIsTheCallersDepth() {
        fx.flow(s, c, "500", min(0));
        fx.flow(c, b, "400", min(5));
        fx.flow(b, me, "300", min(10));
        assertThat(ExposurePaths.indirect(me, fx.flows, fx.labels, ExposurePaths.withMaxHops(cfg, 2))).isEmpty();
        assertThat(ExposurePaths.indirect(me, fx.flows, fx.labels, ExposurePaths.withMaxHops(cfg, 3))).singleElement()
                .satisfies(i -> assertThat(i.hops()).isEqualTo(3));
    }

    /** Counts the inbound flows the walk actually reads (a hub can hold tens of thousands in the working set). */
    static final class CountingStore implements io.openchain.intelligence.flow.FlowStore {
        final io.openchain.intelligence.flow.FlowStore d; long read;
        CountingStore(io.openchain.intelligence.flow.FlowStore d) { this.d = d; }
        @Override public boolean add(ValueFlow f) { return d.add(f); }
        @Override public boolean contains(String id) { return d.contains(id); }
        @Override public java.util.List<ValueFlow> outgoing(Address a) { var l = d.outgoing(a); read += l.size(); return l; }
        @Override public java.util.List<ValueFlow> incoming(Address a) { var l = d.incoming(a); read += l.size(); return l; }
        @Override public java.util.List<ValueFlow> recentIncoming(Address a, int max) { var l = d.recentIncoming(a, max); read += l.size(); return l; }
    }

    @Test void theWalkReadsNoMoreInboundFlowsThanItsBudgetEvenOnAHub() {
        // production 2026-11-19: hubs with thousands of inbound flows made each transfer copy and scan them all
        for (int i = 0; i < 2_000; i++) fx.flow(a("f" + Integer.toHexString(0x1000 + i)), me, "50", min(i));
        ValueFlow up = fx.flow(s, b, "300", min(2_000));
        ValueFlow down = fx.flow(b, me, "100", min(2_010)); // the newest inbound flow: within any budget
        var budgeted = new DetectionConfig.Exposure(cfg.maxHops(), cfg.hopDecay(), cfg.minConfidence(), cfg.maxPaths(), 100,
                cfg.stopAt(), Map.of());
        var store = new CountingStore(fx.flows);
        assertThat(ExposurePaths.indirect(me, store, fx.labels, budgeted)).singleElement()
                .satisfies(x -> assertThat(x.path()).containsExactly(up, down));
        assertThat(store.read).as("inbound flows read").isLessThanOrEqualTo(2L * 100);
    }

    @Test void recentIncomingIsNewestFirstAndBounded() {
        ValueFlow f1 = fx.flow(b, me, "10", min(1)), f2 = fx.flow(c, me, "10", min(2)), f3 = fx.flow(x, me, "10", min(3));
        assertThat(fx.flows.recentIncoming(me, 2)).containsExactly(f3, f2);
        assertThat(fx.flows.recentIncoming(me, 10)).containsExactly(f3, f2, f1);
        assertThat(fx.flows.recentIncoming(me, 0)).isEmpty();
        assertThat(fx.flows.recentIncoming(a("0ne"), 5)).isEmpty();
    }
}
