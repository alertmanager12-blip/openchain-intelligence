package io.openchain.intelligence;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.detection.EventFields;
import io.openchain.intelligence.flow.InMemoryFlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.profile.WalletProfile;
import io.openchain.intelligence.risk.ScoringConfig;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P0 item 2 (docs/P0_REORG_DESIGN.md): a chain reorganisation reverts a flow and everything derived from it. */
class ReorgRetractionTest {
    final IntelligencePipeline p = IntelligencePipeline.inMemory(DetectionConfig.defaults(), ScoringConfig.defaults(),
            Clock.fixed(days(2), ZoneOffset.UTC));
    final Address x = a("ee01"), mule = a("ee02"), y = a("ee03");

    static ValueFlow flow(String tx, int log, Address from, Address to, String amount, java.time.Instant at) {
        return ValueFlow.of(transfer(tx, log, from, to, amount, USDT, at));
    }

    static ChainEvent retracted(ValueFlow f) {
        return new ChainEvent(f.eventId(), EventType.TOKEN_TRANSFER, f.network(), 1, "0xorphan", f.timestamp(), f.timestamp(), true,
                Map.of(EventFields.TX_HASH, f.txHash()));
    }

    @Test void flowStoreRemoveIsIdempotentAndForgetsTheId() {
        var store = new InMemoryFlowStore();
        ValueFlow f = flow("0xa1", 0, x, mule, "10", min(0));
        store.add(f);
        assertThat(store.remove(f.eventId())).contains(f);
        assertThat(store.remove(f.eventId())).isEmpty();
        assertThat(store.contains(f.eventId())).isFalse();
        assertThat(store.outgoing(x)).isEmpty();
        assertThat(store.add(f)).as("re-inclusion is a new flow").isTrue();
    }

    @Test void profilesAreRestoredExactlyIncludingTransactionsWithSeveralFlows() {
        p.onFlow(flow("0xb1", 0, x, mule, "100", min(0)));
        WalletProfile before = p.profiles().profile(mule).orElseThrow();
        ValueFlow a = flow("0xb2", 0, x, mule, "40", min(10)), b = flow("0xb2", 1, x, mule, "2", min(10)); // one tx, two transfers
        p.onFlow(a); p.onFlow(b);
        assertThat(p.profiles().profile(mule).orElseThrow().transactionCount()).isEqualTo(2);
        p.retractFlow(a.eventId());
        WalletProfile mid = p.profiles().profile(mule).orElseThrow();
        assertThat(mid.transactionCount()).as("the tx still has one flow").isEqualTo(2);
        assertThat(mid.inboundTransfers()).isEqualTo(2);
        p.retractFlow(b.eventId());
        WalletProfile after = p.profiles().profile(mule).orElseThrow();
        assertThat(after.transactionCount()).isEqualTo(before.transactionCount());
        assertThat(after.inboundTransfers()).isEqualTo(before.inboundTransfers());
        assertThat(after.tokens().values().iterator().next().inflow()).isEqualByComparingTo("100");
        assertThat(after.lastActivity()).isEqualTo(before.lastActivity());
        assertThat(after.topCounterparties().getFirst().received()).isEqualTo(1);
    }

    @Test void aggregatedSignalWithOneTransactionRetractedIsRecomputedFromTheRemainingEvidence() {
        Address e = a("ee10");
        p.onFlow(flow("0xc0", 0, a("ee19"), e, "50000", min(0)));
        ValueFlow t1 = flow("0xc1", 0, e, a("ee11"), "9500", min(60)), t2 = flow("0xc2", 0, e, a("ee12"), "9600", min(90)),
                t3 = flow("0xc3", 0, e, a("ee13"), "9800", min(120)), t4 = flow("0xc4", 0, e, a("ee14"), "9900", min(150));
        for (ValueFlow f : List.of(t1, t2, t3, t4)) p.onFlow(f);
        Signal original = only(e, SignalType.STRUCTURING);
        assertThat(original.evidence()).anyMatch(ev -> "0xc3".equals(ev.reference()));

        var r = p.retractFlow(t3.eventId());

        assertThat(r.applied()).isTrue();
        assertThat(r.recomputed()).extracting(Signal::id).containsExactly(original.id()); // same id: still justified
        assertThat(r.removed()).isEmpty();
        Signal now = only(e, SignalType.STRUCTURING);
        assertThat(now.evidence()).noneMatch(ev -> "0xc3".equals(ev.reference()))
                .anyMatch(ev -> "0xc4".equals(ev.reference()));
    }

    @Test void signalNoLongerJustifiedIsRemovedAndTheScoreRecomputed() {
        ValueFlow in = flow("0xd1", 0, x, mule, "50000", min(0));
        p.onFlow(in);
        p.onFlow(flow("0xd2", 0, mule, y, "49500", min(10)));
        assertThat(p.signals().findByAddress(mule)).extracting(Signal::type).contains(SignalType.FAST_FORWARDING);
        int scoreBefore = p.scoring().latest(mule).orElseThrow().score();

        var r = p.retractFlow(in.eventId());

        assertThat(r.removed()).extracting(Signal::type).contains(SignalType.FAST_FORWARDING);
        assertThat(p.signals().findByAddress(mule)).extracting(Signal::type).doesNotContain(SignalType.FAST_FORWARDING);
        assertThat(p.scoring().latest(mule).orElseThrow().score()).isLessThan(scoreBefore);
    }

    @Test void signalsDerivedOnOtherWalletsAreRevaluated() {
        Address mix = a("ee20"), w = a("ee21"), z = a("ee22");
        p.labels().save(new AddressLabel(mix, LabelCategory.MIXER, "SYNTHETIC mixer", "synthetic", 0.9, days(-1), Signal.EvidenceLevel.RISK_SIGNAL, null));
        ValueFlow fromMixer = flow("0xe1", 0, mix, w, "30000", min(0));
        p.onFlow(fromMixer);
        p.onFlow(flow("0xe2", 0, w, z, "20000", min(60)));
        assertThat(p.signals().findByAddress(z)).extracting(Signal::type).contains(SignalType.INDIRECT_EXPOSURE);

        var r = p.retractFlow(fromMixer.eventId());

        assertThat(r.touched()).contains(w, z);
        assertThat(p.signals().findByAddress(w)).extracting(Signal::type).doesNotContain(SignalType.MIXER_EXPOSURE);
        assertThat(p.signals().findByAddress(z)).extracting(Signal::type).doesNotContain(SignalType.INDIRECT_EXPOSURE);
    }

    @Test void repeatedRetractionDoesNothingTheSecondTime() {
        ValueFlow in = flow("0xf1", 0, x, mule, "50000", min(0));
        p.onFlow(in);
        p.onFlow(flow("0xf2", 0, mule, y, "49500", min(10)));
        assertThat(p.retractFlow(in.eventId()).applied()).isTrue();
        var again = p.retractFlow(in.eventId());
        assertThat(again.applied()).isFalse();
        assertThat(again.removed()).isEmpty();
        assertThat(p.onEvent(retracted(in)).duplicate()).isTrue();
    }

    @Test void reinclusionInAnotherBlockIsCountedOnceAndRestoresTheSignal() {
        ValueFlow in = flow("0xg1", 0, x, mule, "50000", min(0));
        p.onFlow(in);
        p.onFlow(flow("0xg2", 0, mule, y, "49500", min(10)));
        WalletProfile before = p.profiles().profile(mule).orElseThrow();
        String ffId = only(mule, SignalType.FAST_FORWARDING).id();

        p.onEvent(retracted(in));
        p.onEvent(retracted(in));                     // duplicate notice
        var back = p.onFlow(in);                      // the same transaction in another canonical block
        assertThat(back.duplicate()).isFalse();
        assertThat(p.onFlow(in).duplicate()).as("a second delivery of the re-included flow").isTrue();

        WalletProfile after = p.profiles().profile(mule).orElseThrow();
        assertThat(after.inboundTransfers()).isEqualTo(before.inboundTransfers());
        assertThat(after.transactionCount()).isEqualTo(before.transactionCount());
        assertThat(after.tokens().values().iterator().next().inflow()).isEqualByComparingTo(before.tokens().values().iterator().next().inflow());
        assertThat(p.flows().incoming(mule)).hasSize(1);
        assertThat(p.signals().findByAddress(mule).stream().filter(s -> s.type() == SignalType.FAST_FORWARDING))
                .singleElement().satisfies(s -> assertThat(s.id()).isEqualTo(ffId));
    }

    @Test void retractedApprovalDropsTheSignalItAloneJustified() {
        Address owner = a("ee30");
        ChainEvent approval = new ChainEvent("ev-ap-1", EventType.APPROVAL, NET, 1, "0xb", days(1), days(1), false, Map.of(
                EventFields.APPROVAL_OWNER, owner.value(), EventFields.APPROVAL_SPENDER, "0xspender",
                EventFields.APPROVAL_AMOUNT_RAW, "0x" + "f".repeat(64), EventFields.TX_HASH, "0xap1"));
        p.onEvent(approval);
        assertThat(p.signals().findByAddress(owner)).extracting(Signal::type).containsExactly(SignalType.UNUSUAL_APPROVAL);
        ChainEvent gone = new ChainEvent("ev-ap-1", EventType.APPROVAL, NET, 1, "0xb", days(1), days(1), true, approval.fields());
        assertThat(p.onEvent(gone).duplicate()).isFalse();
        assertThat(p.signals().findByAddress(owner)).isEmpty();
        assertThat(p.onEvent(gone).duplicate()).as("idempotent").isTrue();
        assertThat(p.onEvent(approval).newSignals()).as("re-inclusion").extracting(Signal::type).containsExactly(SignalType.UNUSUAL_APPROVAL);
    }

    private Signal only(Address a, SignalType t) {
        return p.signals().findByAddress(a).stream().filter(s -> s.type() == t).reduce((u, v) -> { throw new AssertionError("several " + t); })
                .orElseThrow(() -> new AssertionError("no " + t + " for " + a));
    }

}
