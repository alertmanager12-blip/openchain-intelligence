package io.openchain.intelligence.detection;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalText;
import io.openchain.core.detection.SignalType;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Token;
import io.openchain.intelligence.Fx;
import io.openchain.intelligence.detection.detectors.*;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Analyst-facing wording: every detector gives a plain-language title and summary built from its evidence. */
class DetectorSummariesTest {
    final Fx fx = new Fx();
    final Address me = a("me"), src = a("5ac"), dst = a("d57"), sanctioned = a("5a9c");

    static Signal one(List<Signal> out) {
        assertThat(out).hasSize(1);
        Signal s = out.getFirst();
        assertThat(s.title()).isEqualTo(SignalText.title(s.type()));
        assertThat(s.summary()).doesNotContain("q=", "@1", "RISK_SIGNAL", "robust z").endsWith(".");
        return s;
    }

    @Test void unusualAmount() {
        for (int i = 0; i < 12; i++) fx.flow(src, me, String.valueOf(100 + i), min(i));
        Signal s = one(new UnusualAmountDetector().detect(fx.ctx(me, fx.flow(src, me, "5000", min(100)))));
        assertThat(s.title()).isEqualTo("Unusual amount");
        assertThat(s.summary()).isEqualTo("Received 5,000 USDT in one transfer, far above its usual amount (median 105.5 USDT over 12 earlier transfers).");
    }

    @Test void structuring() {
        fx.flow(me, a("1"), "9500", min(0));
        fx.flow(me, a("2"), "9900", min(30));
        Signal s = one(new StructuringDetector().detect(fx.ctx(me, fx.flow(me, a("3"), "9999.99", min(60)))));
        assertThat(s.title()).isEqualTo("Possible structuring");
        assertThat(s.summary()).isEqualTo("Sent 3 transfers just below the 10,000 USDT threshold within 24 hours (total 29,399.99 USDT).");
    }

    @Test void fastForwarding() {
        fx.flow(src, me, "20000", min(0));
        Signal s = one(new FastForwardingDetector().detect(fx.ctx(me, fx.flow(me, dst, "19600", min(3)))));
        assertThat(s.title()).isEqualTo("Fast forwarding");
        assertThat(s.summary()).isEqualTo("Forwarded 98% of the 20,000 USDT it received to 1 address within 3 minutes.");
    }

    @Test void dormantReactivation() {
        fx.flow(src, me, "10", days(0));
        Signal s = one(new DormantReactivationDetector().detect(fx.ctx(me, fx.flow(me, dst, "7.5", days(200)))));
        assertThat(s.title()).isEqualTo("Dormant wallet reactivated");
        assertThat(s.summary()).isEqualTo("Sent 7.5 USDT after 200 days without any observed activity.");
    }

    @Test void rapidDrain() {
        fx.flow(src, me, "36800", days(0));
        fx.flow(me, a("x1"), "20000", days(10));
        Signal s = one(new RapidDrainDetector().detect(fx.ctx(me, fx.flow(me, a("x2"), "15000", days(10).plusSeconds(90)))));
        assertThat(s.title()).isEqualTo("Rapid drain");
        assertThat(s.summary()).isEqualTo("Sent 95% of its USDT balance (35,000 USDT) to 2 addresses within 1 hour.");
    }

    @Test void fanOut() {
        fx.config = fx.config.withFanOut(new DetectionConfig.FanOut(5, Duration.ofHours(1)));
        ValueFlow last = null;
        for (int i = 0; i < 6; i++) last = fx.flow(me, a("r" + i), "1", min(i));
        Signal s = one(new FanOutDetector().detect(fx.ctx(me, last)));
        assertThat(s.title()).isEqualTo("Fan-out to many recipients");
        assertThat(s.summary()).isEqualTo("Sent funds to 6 different addresses within 1 hour.");
    }

    @Test void circularFlow() {
        Address b = a("b"), c = a("c");
        fx.flow(me, b, "1000", min(0));
        fx.flow(b, c, "990", min(10));
        Signal s = one(new CircularFlowDetector().detect(fx.ctx(me, fx.flow(c, me, "980", min(20)))));
        assertThat(s.title()).isEqualTo("Circular flow");
        assertThat(s.summary()).isEqualTo("Sent 1,000 USDT that came back to it (980 USDT) through 2 intermediary addresses within 20 minutes.");
    }

    @Test void directExposureReceivedAndSent() {
        fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
        Signal s = one(new ExposureDetector().detect(fx.ctx(me, fx.flow(sanctioned, me, "12500", min(0)))));
        assertThat(s.type()).isEqualTo(SignalType.SANCTIONED_EXPOSURE);
        assertThat(s.title()).isEqualTo("Direct exposure to a sanctioned address");
        assertThat(s.summary()).isEqualTo("Received 12,500 USDT directly from an address listed as sanctioned by demo-source-sanctioned.");

        Fx out = new Fx();
        out.label(dst, LabelCategory.MIXER, 0.6, EvidenceLevel.RISK_SIGNAL);
        out.flow(me, dst, "300", min(0));
        Signal m = one(new ExposureDetector().detect(out.ctx(me, out.flow(dst, me, "100", min(5)))));
        assertThat(m.title()).isEqualTo("Direct exposure to a mixer");
        assertThat(m.summary()).isEqualTo("Received 100 USDT from and sent 300 USDT directly to an address listed as a mixer by demo-source-mixer (2 transfers).");
    }

    @Test void indirectExposure() {
        fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
        Address h1 = a("h1");
        fx.flow(sanctioned, h1, "1000", min(0));
        List<Signal> out = new ExposureDetector().detect(fx.ctx(me, fx.flow(h1, me, "800", min(20))));
        Signal s = out.stream().filter(x -> x.type() == SignalType.INDIRECT_EXPOSURE).findFirst().orElseThrow();
        assertThat(s.title()).isEqualTo("Indirect exposure to a listed address");
        assertThat(s.summary()).isEqualTo("Received funds that trace back, through 1 intermediary address, to an address listed as sanctioned "
                + "by demo-source-sanctioned (at most 800 USDT traced).");
    }

    @Test void dusting() {
        fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
        Signal s = one(new ExposureDetector().detect(fx.ctx(me, fx.flow(sanctioned, me, "0.000001", min(0)))));
        assertThat(s.title()).isEqualTo("Dusting from a listed address");
        assertThat(s.summary()).isEqualTo("Received 1 unsolicited transfer from an address listed as sanctioned by demo-source-sanctioned "
                + "(below the dust threshold); not counted as exposure because anyone can send such transfers.");
        Token fake = new Token(NET, "0xfa4e000000000000000000000000000000000000", Token.UNKNOWN_SYMBOL, 0);
        Fx f2 = new Fx();
        f2.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
        assertThat(one(new ExposureDetector().detect(f2.ctx(me, f2.flow(sanctioned, me, "1000000", fake, min(0))))).summary())
                .contains("(unverified token)");
    }

    @Test void unusualApproval() {
        String max = "115792089237316195423570985008687907853269984665640564039457584007913129639935";
        Address spender = a("5be0000000000000000000000000000000000001");
        Map<String, Object> f = Map.of(EventFields.APPROVAL_OWNER, me.value(), EventFields.APPROVAL_SPENDER, spender.value(),
                EventFields.APPROVAL_AMOUNT_RAW, max, EventFields.TX_HASH, "0xappr", EventFields.TOKEN_SYMBOL, "USDT");
        Signal s = one(new UnusualApprovalDetector().detect(fx.ctx(me, event("e1", EventType.APPROVAL, f))));
        assertThat(s.title()).isEqualTo("Unlimited token approval");
        assertThat(s.summary()).isEqualTo("Granted an unlimited USDT allowance to 0x5be0…0001, which can move all of its USDT at any time.");
        fx.label(spender, LabelCategory.PHISHING, 0.7, EvidenceLevel.RISK_SIGNAL);
        assertThat(one(new UnusualApprovalDetector().detect(fx.ctx(me, event("e2", EventType.APPROVAL, f)))).summary())
                .isEqualTo("Granted an unlimited USDT allowance to 0x5be0…0001, an address listed as phishing by demo-source-phishing.");
    }

    @Test void ownershipChange() {
        Address contract = a("c0de");
        var e = event("e1", EventType.CONTRACT_ADMIN_CHANGE, Map.of(EventFields.ADMIN_CONTRACT, contract.value(),
                EventFields.ADMIN_PREVIOUS, "0x1111111111111111111111111111111111111111",
                EventFields.ADMIN_NEW, "0x2222222222222222222222222222222222222222", EventFields.TX_HASH, "0xown"));
        Signal s = one(new OwnershipChangeDetector().detect(fx.ctx(contract, e)));
        assertThat(s.title()).isEqualTo("Contract ownership change");
        assertThat(s.summary()).isEqualTo("The owner of this contract changed from 0x1111…1111 to 0x2222…2222.");
        var renounce = event("e2", EventType.CONTRACT_ADMIN_CHANGE, Map.of(EventFields.ADMIN_CONTRACT, contract.value(),
                EventFields.ADMIN_NEW, "0x0000000000000000000000000000000000000000", EventFields.TX_HASH, "0xren"));
        assertThat(one(new OwnershipChangeDetector().detect(fx.ctx(contract, renounce))).summary())
                .isEqualTo("The owner of this contract was renounced (set to the zero address).");
        var upgrade = event("e3", EventType.CONTRACT_ADMIN_CHANGE, Map.of(EventFields.ADMIN_CONTRACT, contract.value(),
                EventFields.ADMIN_ROLE, "implementation", EventFields.ADMIN_NEW, "0x3333333333333333333333333333333333333333"));
        assertThat(one(new OwnershipChangeDetector().detect(fx.ctx(contract, upgrade))).summary())
                .isEqualTo("The implementation of this contract was upgraded to 0x3333…3333.");
    }

    @Test void numberAndDurationFormatting() {
        assertThat(SignalText.number(new BigDecimal("35000"))).isEqualTo("35,000");
        assertThat(SignalText.number(new BigDecimal("1234567.891"))).isEqualTo("1,234,567.89");
        assertThat(SignalText.number(new BigDecimal("105.5"))).isEqualTo("105.5");
        assertThat(SignalText.number(new BigDecimal("0.000001"))).isEqualTo("0.000001");
        assertThat(SignalText.percent(0.9999)).isEqualTo("99%");
        assertThat(SignalText.duration(Duration.ofMinutes(2))).isEqualTo("2 minutes");
        assertThat(SignalText.duration(Duration.ofSeconds(45))).isEqualTo("45 seconds");
        assertThat(SignalText.labelTitle("SANCTIONED")).isEqualTo("Listed as sanctioned");
        assertThat(SignalText.labelTitle("ISSUER_FROZEN")).isEqualTo("Frozen by the stablecoin issuer");
    }
}
