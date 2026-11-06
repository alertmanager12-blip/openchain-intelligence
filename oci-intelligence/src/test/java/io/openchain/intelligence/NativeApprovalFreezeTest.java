package io.openchain.intelligence;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.risk.RiskLevel;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.detection.EventFields;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.risk.ScoringConfig;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Native value flows, approvals and issuer freezes through the real pipeline (in memory). */
class NativeApprovalFreezeTest {
    final IntelligencePipeline p = IntelligencePipeline.inMemory(DetectionConfig.defaults(), ScoringConfig.defaults(),
            Clock.fixed(days(1), ZoneOffset.UTC));
    final Address sanctioned = a("5a5a000000000000000000000000000000000001");
    final Address me = a("me00000000000000000000000000000000000001");
    final Address frozen = a("f0f0000000000000000000000000000000000001");

    void sanction(Address x) {
        p.labels().save(new AddressLabel(x, LabelCategory.SANCTIONED, "DEMO sanctioned", "demo-sanctions", 1.0, T0,
                EvidenceLevel.CONFIRMED_BY_SOURCE, "demo://s"));
    }

    static ChainTransaction eth(String hash, Address from, Address to, String ether, Instant at) {
        return new ChainTransaction(NET, hash, 1, "0xblock", at, from, to, new BigDecimal(ether).movePointRight(18).toBigIntegerExact(),
                BigInteger.valueOf(21_000), true, null, false);
    }

    @Test void nativeEthFromASanctionedAddressAboveTheDustThresholdIsExposure() {
        sanction(sanctioned);
        var r = p.onTransaction(eth("0xn1", sanctioned, me, "0.5", min(0)));
        assertThat(r.newSignals()).filteredOn(s -> s.address().equals(me)).extracting(Signal::type).contains(SignalType.SANCTIONED_EXPOSURE);
        assertThat(p.flows().contains("ethereum:0xn1:native")).isTrue();
        assertThat(p.scoring().latest(me).orElseThrow().score()).isPositive();
    }

    @Test void nativeDustBelowThresholdOnlyRecordsDusting() {
        sanction(sanctioned);
        var r = p.onTransaction(eth("0xn2", sanctioned, me, "0.001", min(0))); // < 0.005 ETH
        assertThat(r.newSignals()).filteredOn(s -> s.address().equals(me)).extracting(Signal::type)
                .containsExactly(SignalType.DUSTING);
        assertThat(p.scoring().latest(me).orElseThrow().score()).isZero();
    }

    @Test void failedOrZeroValueTransactionsAreNotFlows() {
        var failed = new ChainTransaction(NET, "0xn3", 1, "0xb", min(0), sanctioned, me, BigInteger.TEN.pow(18), BigInteger.ONE, false, null, false);
        var zero = new ChainTransaction(NET, "0xn4", 1, "0xb", min(0), sanctioned, me, BigInteger.ZERO, BigInteger.ONE, true, null, false);
        p.onTransaction(failed);
        p.onTransaction(zero);
        assertThat(p.flows().involving(me)).isEmpty();
    }

    @Test void unlimitedApprovalToALabelledSpenderIsHigh() {
        Address spender = a("bad0000000000000000000000000000000000bad");
        p.labels().save(new AddressLabel(spender, LabelCategory.PHISHING, "DEMO drainer report", "demo-feed", 0.8, T0,
                EvidenceLevel.RISK_SIGNAL, "demo://p"));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(EventFields.APPROVAL_OWNER, me.value());
        f.put(EventFields.APPROVAL_SPENDER, spender.value());
        f.put(EventFields.APPROVAL_AMOUNT_RAW, new BigDecimal(BigInteger.TWO.pow(256).subtract(BigInteger.ONE)));
        f.put(EventFields.TOKEN_SYMBOL, "USDT");
        f.put(EventFields.TX_HASH, "0xap1");
        var r = p.onEvent(event("ethereum:0xap1:3", EventType.APPROVAL, f));
        assertThat(r.newSignals()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo(SignalType.UNUSUAL_APPROVAL);
            assertThat(s.severity()).isEqualTo(Severity.HIGH);
            assertThat(s.address()).isEqualTo(me);
            assertThat(s.evidence()).anyMatch(ev -> ev.kind().equals("label"));
        });
    }

    static ChainEvent freeze(String id, EventType type, Address target, Instant at, boolean removed) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(EventFields.FREEZE_ADDRESS, target.value());
        f.put(EventFields.FREEZE_TOKEN, "USDT");
        f.put(EventFields.FREEZE_ISSUER, "tether");
        f.put(EventFields.FREEZE_EVENT, type == EventType.STABLECOIN_FREEZE ? "AddedBlackList" : "RemovedBlackList");
        f.put(EventFields.FREEZE_ACTION, type == EventType.STABLECOIN_FREEZE ? "FREEZE" : "UNFREEZE");
        f.put(EventFields.TX_HASH, id.split(":")[1]);
        return new ChainEvent(id, type, NET, 1, "0xblock", at, at, removed, f);
    }

    @Test void freezeLabelsScoresAndExposesCounterpartiesThenUnfreezeDelistsAndRescores() {
        p.onTransfer(transfer("0xpay", 0, me, frozen, "5000", USDT, min(0)));
        assertThat(p.scoring().latest(frozen).orElseThrow().score()).isZero();

        var r = p.onEvent(freeze("ethereum:0xfz:4", EventType.STABLECOIN_FREEZE, frozen, min(10), false));
        AddressLabel l = p.labels().findByAddress(frozen).getFirst();
        assertThat(l.category()).isEqualTo(LabelCategory.ISSUER_FROZEN);
        assertThat(l.source()).isEqualTo("tether-onchain");
        assertThat(l.reference()).isEqualTo("0xfz");
        assertThat(l.observedAt()).isEqualTo(min(10));
        assertThat(l.evidenceLevel()).isEqualTo(EvidenceLevel.CONFIRMED_BY_SOURCE);
        var frozenScore = p.scoring().latest(frozen).orElseThrow();
        assertThat(frozenScore.score()).isEqualTo(60); // weight 0.60 x reliability 1.0, non-decaying
        assertThat(frozenScore.level()).isEqualTo(RiskLevel.MEDIUM);
        // the counterparty that paid the frozen address gets a direct (flagged) exposure
        assertThat(r.newSignals()).filteredOn(s -> s.address().equals(me)).extracting(Signal::type).contains(SignalType.FLAGGED_EXPOSURE);
        assertThat(p.scoring().latest(me).orElseThrow().score()).isPositive();
        assertThat(p.onEvent(freeze("ethereum:0xfz:4", EventType.STABLECOIN_FREEZE, frozen, min(10), false)).duplicate()).isTrue();

        var u = p.onEvent(freeze("ethereum:0xuf:2", EventType.STABLECOIN_UNFREEZE, frozen, min(20), false));
        assertThat(p.labels().findByAddress(frozen)).isEmpty();
        assertThat(u.scores()).singleElement().satisfies(s -> assertThat(s.score()).isZero());
        assertThat(p.scoring().latest(frozen).orElseThrow().score()).isZero();
        assertThat(p.scoring().history(frozen)).extracting(io.openchain.core.risk.RiskScore::score).containsSubsequence(60, 0);
    }

    @Test void staleFreezeAfterUnfreezeIsIgnoredAndReorgRetractionRestoresPreviousState() {
        p.onEvent(freeze("ethereum:0xa:0", EventType.STABLECOIN_FREEZE, frozen, min(10), false));
        p.onEvent(freeze("ethereum:0xb:0", EventType.STABLECOIN_UNFREEZE, frozen, min(20), false));
        p.onEvent(freeze("ethereum:0xold:0", EventType.STABLECOIN_FREEZE, frozen, min(5), false)); // older replay
        assertThat(p.labels().findByAddress(frozen)).isEmpty();

        // the unfreeze is reorged out: the address is frozen again
        p.onEvent(freeze("ethereum:0xb:0", EventType.STABLECOIN_UNFREEZE, frozen, min(20), true));
        assertThat(p.labels().findByAddress(frozen)).extracting(AddressLabel::category).containsExactly(LabelCategory.ISSUER_FROZEN);
        assertThat(p.scoring().latest(frozen).orElseThrow().score()).isEqualTo(60);
        // ...and the new fork re-includes it
        p.onEvent(freeze("ethereum:0xb:0", EventType.STABLECOIN_UNFREEZE, frozen, min(20), false));
        assertThat(p.labels().findByAddress(frozen)).isEmpty();
    }
}
