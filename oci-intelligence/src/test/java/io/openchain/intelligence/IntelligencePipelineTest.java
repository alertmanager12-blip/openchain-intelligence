package io.openchain.intelligence;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Address;
import io.openchain.core.risk.RiskLevel;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.detection.EventFields;
import io.openchain.intelligence.label.LabelFileLoader;
import io.openchain.intelligence.risk.ScoringConfig;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntelligencePipelineTest {
    final IntelligencePipeline p = IntelligencePipeline.inMemory(DetectionConfig.defaults(), ScoringConfig.defaults(),
            Clock.fixed(days(1), ZoneOffset.UTC));
    final Address sanctioned = new Address(NET, "0xdemo00000000000000000000000000000000a001");
    final Address mule = a("m01e"), me = a("me");

    @Test void endToEndExposureDetectionAndScoring() throws Exception {
        p.labels().saveAll(LabelFileLoader.fromClasspath("labels/demo-labels.csv"));
        var r1 = p.onTransfer(transfer("0x1", 0, sanctioned, mule, "1000", USDT, min(0)));
        assertThat(r1.newSignals()).extracting(Signal::type).contains(SignalType.SANCTIONED_EXPOSURE);
        var r2 = p.onTransfer(transfer("0x2", 0, mule, me, "995", USDT, min(3)));
        assertThat(r2.newSignals()).extracting(Signal::type).contains(SignalType.FAST_FORWARDING, SignalType.INDIRECT_EXPOSURE);
        assertThat(r2.detectorErrors()).isEmpty();

        var muleScore = p.scoring().latest(mule).orElseThrow();
        var meScore = p.scoring().latest(me).orElseThrow();
        assertThat(muleScore.level()).isIn(RiskLevel.HIGH, RiskLevel.CRITICAL);
        assertThat(meScore.score()).isPositive().isLessThan(muleScore.score());
        assertThat(meScore.contributions()).anyMatch(c -> c.factor().equals("signal:INDIRECT_EXPOSURE"));
        // the sanctioned address itself is scored from its own label only
        assertThat(p.scoring().rescore(sanctioned).level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(p.profiles().profile(me).orElseThrow().riskScoreHistory()).isNotEmpty();

        var dup = p.onTransfer(transfer("0x2", 0, mule, me, "995", USDT, min(3)));
        assertThat(dup.duplicate()).isTrue();
        assertThat(p.profiles().profile(me).orElseThrow().inboundTransfers()).isEqualTo(1);
    }

    @Test void approvalEventsAreDedupedAndScored() {
        var e = event("ev-1", EventType.APPROVAL, Map.of(EventFields.APPROVAL_OWNER, me.value(), EventFields.APPROVAL_SPENDER, "0xspender",
                EventFields.APPROVAL_AMOUNT_RAW, "0x" + "f".repeat(64), EventFields.TX_HASH, "0xap"));
        var r = p.onEvent(e);
        assertThat(r.newSignals()).extracting(Signal::type).containsExactly(SignalType.UNUSUAL_APPROVAL);
        assertThat(r.scores()).singleElement().satisfies(s -> assertThat(s.score()).isPositive());
        assertThat(p.onEvent(e).duplicate()).isTrue();
    }

    /** Audit probes 1 and 2, end to end: dust or fake-token transfers from a sanctioned address no longer score the victim. */
    @Test void dustingAndSpoofedTransfersDoNotRaiseTheVictimsScore() throws Exception {
        p.labels().saveAll(LabelFileLoader.fromClasspath("labels/demo-labels.csv"));
        var dust = p.onTransfer(transfer("0xd1", 0, sanctioned, me, "0.000001", USDT, min(0)));
        assertThat(dust.newSignals()).extracting(Signal::type).containsOnly(SignalType.DUSTING);
        var fake = new io.openchain.core.model.Token(NET, "0xfa4e000000000000000000000000000000000000", io.openchain.core.model.Token.UNKNOWN_SYMBOL, 0);
        p.onTransfer(transfer("0xd2", 0, sanctioned, me, "1", fake, min(1)));
        var score = p.scoring().rescore(me);
        assertThat(score.score()).isZero();
        assertThat(score.contributions()).noneMatch(c -> c.factor().contains("EXPOSURE") && c.points() > 0);
        // a real, material transfer still is exposure
        p.onTransfer(transfer("0xd3", 0, sanctioned, me, "5000", USDT, min(2)));
        assertThat(p.scoring().rescore(me).level()).isIn(RiskLevel.HIGH, RiskLevel.CRITICAL);
    }
}
