package io.openchain.intelligence.detection;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Token;
import io.openchain.intelligence.Fx;
import io.openchain.intelligence.detection.detectors.*;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.LabelCategory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DetectorsTest {
    final Fx fx = new Fx();
    final Address me = a("me"), src = a("5ac"), dst = a("d57");

    static void assertWellFormed(Signal s, String detector) {
        assertThat(s.detectorId()).isEqualTo(detector);
        assertThat(s.id()).startsWith("sig_");
        assertThat(s.evidence()).isNotEmpty();
        assertThat(s.evidence()).anyMatch(e -> e.reference() != null);
        assertThat(s.confidence()).isBetween(0.0, 1.0);
    }

    @Nested class UnusualAmount {
        final Detector d = new UnusualAmountDetector();

        @Test void flagsAmountFarAboveOwnBaseline() {
            for (int i = 0; i < 12; i++) fx.flow(src, me, String.valueOf(100 + i), min(i));
            ValueFlow big = fx.flow(src, me, "5000", min(100));
            List<Signal> out = d.detect(fx.ctx(me, big));
            assertThat(out).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.UNUSUAL_AMOUNT);
                assertThat(s.evidenceLevel()).isEqualTo(EvidenceLevel.ANOMALY);
                assertThat(s.txHash()).isEqualTo(big.txHash());
                assertThat(s.evidence()).anyMatch(e -> e.kind().equals("statistic") && e.description().contains("robust z"));
                assertWellFormed(s, "unusual-amount@1");
            });
        }

        @Test void ignoresNormalAmountAndShortHistory() {
            for (int i = 0; i < 12; i++) fx.flow(src, me, String.valueOf(100 + i), min(i));
            assertThat(d.detect(fx.ctx(me, fx.flow(src, me, "108", min(100))))).isEmpty();
            Fx fresh = new Fx();
            fresh.flow(src, me, "1", min(0));
            assertThat(d.detect(fresh.ctx(me, fresh.flow(src, me, "1000000", min(1))))).isEmpty();
        }

        @Test void constantHistoryUsesFallbackScale() {
            for (int i = 0; i < 12; i++) fx.flow(src, me, "100", min(i));
            assertThat(d.detect(fx.ctx(me, fx.flow(src, me, "100", min(50))))).isEmpty();
            assertThat(d.detect(fx.ctx(me, fx.flow(src, me, "900", min(60))))).hasSize(1);
        }
    }

    @Nested class Structuring {
        final Detector d = new StructuringDetector();

        @Test void flagsRepeatedJustBelowThreshold() {
            fx.flow(me, a("1"), "9500", min(0));
            fx.flow(me, a("2"), "9900", min(30));
            ValueFlow last = fx.flow(me, a("3"), "9999.99", min(60));
            assertThat(d.detect(fx.ctx(me, last))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.STRUCTURING);
                assertThat(s.evidence().stream().filter(e -> e.kind().equals("flow"))).hasSize(3);
                assertWellFormed(s, "structuring@1");
            });
        }

        @Test void ignoresAmountsAtOrAboveThresholdOrOutsideWindow() {
            fx.flow(me, a("1"), "10000", min(0));
            fx.flow(me, a("2"), "9500", days(-3).plusSeconds(0));
            fx.flow(me, a("3"), "5000", min(20));
            ValueFlow last = fx.flow(me, a("4"), "9900", min(60));
            assertThat(d.detect(fx.ctx(me, last))).isEmpty();
        }
    }

    @Nested class FastForwarding {
        final Detector d = new FastForwardingDetector();

        @Test void flagsQuickPassThrough() {
            fx.flow(src, me, "1000", min(0));
            ValueFlow out = fx.flow(me, dst, "990", min(5));
            assertThat(d.detect(fx.ctx(me, out))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.FAST_FORWARDING);
                assertThat(s.evidence()).anyMatch(e -> e.description().startsWith("received"));
                assertThat(s.evidence()).anyMatch(e -> e.description().startsWith("forwarded"));
                assertWellFormed(s, "fast-forwarding@1");
            });
        }

        @Test void ignoresPartialOrLateForwarding() {
            fx.flow(src, me, "1000", min(0));
            assertThat(d.detect(fx.ctx(me, fx.flow(me, dst, "300", min(5))))).isEmpty();
            Fx late = new Fx();
            late.flow(src, me, "1000", min(0));
            assertThat(d.detect(late.ctx(me, late.flow(me, dst, "1000", min(120))))).isEmpty();
        }
    }

    @Nested class Dormancy {
        final Detector d = new DormantReactivationDetector();

        @Test void flagsReactivationAfterLongSilence() {
            fx.flow(src, me, "10", days(0));
            ValueFlow wake = fx.flow(me, dst, "10", days(200));
            assertThat(d.detect(fx.ctx(me, wake))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.DORMANT_REACTIVATION);
                assertThat(s.evidenceLevel()).isEqualTo(EvidenceLevel.ANOMALY);
                assertWellFormed(s, "dormant-reactivation@1");
            });
        }

        @Test void ignoresRegularActivityAndFirstActivity() {
            fx.flow(src, me, "10", days(0));
            assertThat(d.detect(fx.ctx(me, fx.flow(me, dst, "10", days(30))))).isEmpty();
            Fx fresh = new Fx();
            assertThat(d.detect(fresh.ctx(me, fresh.flow(me, dst, "1", days(0))))).isEmpty();
        }
    }

    @Nested class RapidDrain {
        final Detector d = new RapidDrainDetector();

        @Test void flagsDrainOfExistingBalance() {
            fx.flow(src, me, "1000", days(0));
            fx.flow(me, a("x1"), "500", days(10));
            ValueFlow last = fx.flow(me, a("x2"), "350", days(10).plusSeconds(600));
            assertThat(d.detect(fx.ctx(me, last))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.RAPID_DRAIN);
                assertThat(s.evidence()).anyMatch(e -> e.description().contains("85%"));
                assertWellFormed(s, "rapid-drain@1");
            });
        }

        @Test void ignoresSmallShareAndWalletsWithoutPriorBalance() {
            fx.flow(src, me, "1000", days(0));
            assertThat(d.detect(fx.ctx(me, fx.flow(me, dst, "100", days(10))))).isEmpty();
            Fx fresh = new Fx();
            fresh.flow(src, me, "1000", min(0));
            assertThat(d.detect(fresh.ctx(me, fresh.flow(me, dst, "1000", min(1))))).isEmpty();
        }
    }

    @Nested class FanOut {
        final Detector d = new FanOutDetector();

        @Test void flagsManyDistinctRecipients() {
            fx.config = fx.config.withFanOut(new DetectionConfig.FanOut(5, Duration.ofHours(1)));
            ValueFlow last = null;
            for (int i = 0; i < 5; i++) last = fx.flow(me, a("r" + i), "1", min(i));
            assertThat(d.detect(fx.ctx(me, last))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.FLOW_CONCENTRATION);
                assertWellFormed(s, "fan-out@1");
            });
        }

        @Test void repeatedRecipientIsCountedOnce() {
            fx.config = fx.config.withFanOut(new DetectionConfig.FanOut(5, Duration.ofHours(1)));
            ValueFlow last = null;
            for (int i = 0; i < 10; i++) last = fx.flow(me, a("r" + (i % 2)), "1", min(i));
            assertThat(d.detect(fx.ctx(me, last))).isEmpty();
        }
    }

    @Nested class Circular {
        final Detector d = new CircularFlowDetector();

        @Test void flagsValueReturningThroughIntermediaries() {
            Address b = a("b"), c = a("c");
            fx.flow(me, b, "100", min(0));
            fx.flow(b, c, "99", min(10));
            ValueFlow back = fx.flow(c, me, "98", min(20));
            assertThat(d.detect(fx.ctx(me, back))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.CIRCULAR_FLOW);
                assertThat(s.evidence()).anyMatch(e -> e.kind().equals("path") && e.description().contains("3-hop"));
                assertWellFormed(s, "circular-flow@1");
            });
        }

        @Test void ignoresTimeInconsistentOrTooDeepPaths() {
            Address b = a("b"), c = a("c");
            fx.flow(me, b, "100", min(30));
            fx.flow(b, c, "99", min(10)); // happened BEFORE value arrived at b
            ValueFlow back = fx.flow(c, me, "98", min(40));
            assertThat(d.detect(fx.ctx(me, back))).isEmpty();

            Fx deep = new Fx();
            deep.config = new DetectionConfig(deep.config.unusualAmount(), deep.config.structuring(), deep.config.fastForwarding(),
                    deep.config.dormancy(), deep.config.rapidDrain(), deep.config.fanOut(),
                    new DetectionConfig.CircularFlow(2, Duration.ofDays(7), true, 1000), deep.config.exposure(), deep.config.approval(), 20);
            deep.flow(me, b, "100", min(0));
            deep.flow(b, c, "99", min(10));
            assertThat(d.detect(deep.ctx(me, deep.flow(c, me, "98", min(20))))).isEmpty();
        }
    }

    @Nested class Exposure {
        final Detector d = new ExposureDetector();
        final Address sanctioned = a("5a9c");

        @Test void skipsTheGraphWalkWhenNothingIsListed() {
            // no label at all: no exposure is possible, and the costly upstream walk must not run
            assertThat(fx.labels.hasRisky()).isFalse();
            assertThat(d.detect(fx.ctx(me, fx.flow(a("h1"), me, "1000", min(0))))).isEmpty();
            fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            assertThat(fx.labels.hasRisky()).isTrue();
            fx.labels.remove(sanctioned, LabelCategory.SANCTIONED, fx.labels.findByAddress(sanctioned).getFirst().source());
            assertThat(fx.labels.hasRisky()).isFalse();
        }

        @Test void directSanctionedExposureIsConfirmedBySource() {
            fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            ValueFlow f = fx.flow(sanctioned, me, "50", min(0));
            assertThat(d.detect(fx.ctx(me, f))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.SANCTIONED_EXPOSURE);
                assertThat(s.severity()).isEqualTo(Severity.CRITICAL);
                assertThat(s.evidenceLevel()).isEqualTo(EvidenceLevel.CONFIRMED_BY_SOURCE);
                assertThat(s.evidence()).anyMatch(e -> e.kind().equals("label"));
                assertThat(s.evidence()).anyMatch(e -> e.kind().equals("flow") && e.reference().equals(f.txHash()));
                assertWellFormed(s, "exposure@1");
            });
        }

        @Test void communityFlagIsOnlyRiskSignal() {
            fx.label(dst, LabelCategory.FLAGGED_COMMUNITY, 0.4, EvidenceLevel.RISK_SIGNAL);
            assertThat(d.detect(fx.ctx(me, fx.flow(me, dst, "5", min(0))))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.FLAGGED_EXPOSURE);
                assertThat(s.evidenceLevel()).isEqualTo(EvidenceLevel.RISK_SIGNAL);
                assertThat(s.confidence()).isEqualTo(0.4);
            });
        }

        @Test void indirectExposureListsPathAndDecaysPerHop() {
            fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            Address h1 = a("h1"), h2 = a("h2");
            ValueFlow f1 = fx.flow(sanctioned, h2, "1000", min(0));
            ValueFlow f2 = fx.flow(h2, h1, "900", min(10));
            ValueFlow f3 = fx.flow(h1, me, "800", min(20));
            List<Signal> out = d.detect(fx.ctx(me, f3));
            assertThat(out).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.INDIRECT_EXPOSURE);
                assertThat(s.evidenceLevel()).isEqualTo(EvidenceLevel.RISK_SIGNAL);
                assertThat(s.confidence()).isEqualTo(0.25); // 1.0 * 0.5^2
                assertThat(s.evidence().stream().filter(e -> e.kind().equals("flow")).map(Signal.Evidence::reference))
                        .containsExactly(f1.txHash(), f2.txHash(), f3.txHash());
                assertThat(s.evidence()).anyMatch(e -> e.kind().equals("hops") && e.description().equals("3"));
            });
            // h1 is 2 hops away: higher confidence
            assertThat(d.detect(fx.ctx(h1, f2))).singleElement().satisfies(s -> assertThat(s.confidence()).isEqualTo(0.5));
        }

        @Test void noIndirectExposureWithoutTimeConsistentValueFlow() {
            fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            Address h1 = a("h1");
            ValueFlow f = fx.flow(h1, me, "800", min(0));
            fx.flow(sanctioned, h1, "1000", min(30)); // arrived at h1 AFTER h1 paid me
            assertThat(d.detect(fx.ctx(me, f))).noneMatch(s -> s.type() == SignalType.INDIRECT_EXPOSURE);
        }

        @Test void beyondMaxHopsAndThroughExchangesIsNotPropagated() {
            fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            Address h1 = a("h1"), h2 = a("h2"), h3 = a("h3");
            // amounts above the dust threshold, so only the hop limit and the exchange stop can explain "no signal"
            fx.flow(sanctioned, h3, "1000", min(0));
            fx.flow(h3, h2, "1000", min(1));
            fx.flow(h2, h1, "1000", min(2));
            assertThat(d.detect(fx.ctx(me, fx.flow(h1, me, "1000", min(3))))).isEmpty(); // 4 hops > 3

            Fx ex = new Fx();
            Address exchange = a("e1");
            ex.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            ex.label(exchange, LabelCategory.EXCHANGE, 0.9, EvidenceLevel.RISK_SIGNAL);
            ex.flow(sanctioned, exchange, "1000", min(0));
            assertThat(d.detect(ex.ctx(me, ex.flow(exchange, me, "1000", min(1))))).isEmpty();
        }

        // ---- anti-griefing (audit probes 1 and 2): anyone can send dust or fake-token transfers to any wallet ----

        @Test void unsolicitedDustFromSanctionedIsOnlyAZeroWeightDustingAnomaly() {
            fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            ValueFlow dust = fx.flow(sanctioned, me, "0.000001", min(0));
            assertThat(d.detect(fx.ctx(me, dust))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.DUSTING);
                assertThat(s.severity()).isEqualTo(Severity.INFO);
                assertThat(s.evidenceLevel()).isEqualTo(EvidenceLevel.ANOMALY);
                assertThat(s.evidence()).anyMatch(e -> e.kind().equals("flow") && e.reference().equals(dust.txHash()));
                assertThat(s.evidence()).anyMatch(e -> e.kind().equals("pattern") && e.description().contains("below the dust threshold"));
                assertWellFormed(s, "exposure@1");
            });
        }

        @Test void unverifiedTokenTransferFromSanctionedCreatesNoExposure() {
            fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            Token fake = new Token(NET, "0xfa4e000000000000000000000000000000000000", Token.UNKNOWN_SYMBOL, 0);
            ValueFlow spoofed = fx.flow(sanctioned, me, "1000000", fake, min(0));
            assertThat(d.detect(fx.ctx(me, spoofed))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.DUSTING);
                assertThat(s.evidence()).anyMatch(e -> e.kind().equals("pattern") && e.description().contains("1 of an unverified token contract"));
            });
        }

        @Test void outboundTransferToSanctionedCountsWhateverTheAmount() {
            fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            // the wallet chose to send it: no dust exemption for outbound value
            assertThat(d.detect(fx.ctx(me, fx.flow(me, sanctioned, "0.5", min(0))))).singleElement()
                    .satisfies(s -> assertThat(s.type()).isEqualTo(SignalType.SANCTIONED_EXPOSURE));
        }

        @Test void dustIsNotTracedIndirectly() {
            fx.label(sanctioned, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE);
            Address h1 = a("h1");
            fx.flow(sanctioned, h1, "0.01", min(0));  // dust into the intermediary
            assertThat(d.detect(fx.ctx(me, fx.flow(h1, me, "5000", min(5))))).noneMatch(s -> s.type() == SignalType.INDIRECT_EXPOSURE);
        }

        @Test void noLabelsNoSignal() {
            assertThat(d.detect(fx.ctx(me, fx.flow(src, me, "1", min(0))))).isEmpty();
        }
    }

    @Nested class Approval {
        final Detector d = new UnusualApprovalDetector();
        final String max = "115792089237316195423570985008687907853269984665640564039457584007913129639935";

        Map<String, Object> fields(String amount, Address spender) {
            return Map.of(EventFields.APPROVAL_OWNER, me.value(), EventFields.APPROVAL_SPENDER, spender.value(),
                    EventFields.APPROVAL_AMOUNT_RAW, amount, EventFields.TX_HASH, "0xappr", EventFields.TOKEN_SYMBOL, "USDT");
        }

        @Test void flagsUnlimitedApprovalAndEscalatesListedSpender() {
            assertThat(d.detect(fx.ctx(me, event("e1", EventType.APPROVAL, fields(max, dst))))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.UNUSUAL_APPROVAL);
                assertThat(s.address()).isEqualTo(me);
                assertThat(s.severity()).isEqualTo(Severity.LOW);
                assertWellFormed(s, "unusual-approval@1");
            });
            fx.label(dst, LabelCategory.PHISHING, 0.7, EvidenceLevel.RISK_SIGNAL);
            assertThat(d.detect(fx.ctx(me, event("e2", EventType.APPROVAL, fields(max, dst))))).singleElement()
                    .satisfies(s -> assertThat(s.severity()).isEqualTo(Severity.HIGH));
        }

        @Test void ignoresBoundedApprovalAndOtherEvents() {
            assertThat(d.detect(fx.ctx(me, event("e1", EventType.APPROVAL, fields("1000000", dst))))).isEmpty();
            assertThat(d.detect(fx.ctx(me, event("e2", EventType.CONTRACT_CALL, fields(max, dst))))).isEmpty();
            assertThat(d.detect(fx.ctx(me, event("e3", EventType.APPROVAL, Map.of())))).isEmpty();
        }
    }

    @Nested class Ownership {
        final Detector d = new OwnershipChangeDetector();
        final Address contract = a("c0de");

        @Test void flagsAdminChange() {
            var e = event("e1", EventType.CONTRACT_ADMIN_CHANGE, Map.of(EventFields.ADMIN_CONTRACT, contract.value(),
                    EventFields.ADMIN_PREVIOUS, "0xold", EventFields.ADMIN_NEW, "0xnew", EventFields.TX_HASH, "0xown"));
            assertThat(d.detect(fx.ctx(contract, e))).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo(SignalType.OWNERSHIP_CHANGE);
                assertThat(s.address()).isEqualTo(contract);
                assertThat(s.severity()).isEqualTo(Severity.MEDIUM);
                assertThat(s.txHash()).isEqualTo("0xown");
                assertWellFormed(s, "ownership-change@1");
            });
            var renounce = event("e2", EventType.CONTRACT_ADMIN_CHANGE, Map.of(EventFields.ADMIN_CONTRACT, contract.value(),
                    EventFields.ADMIN_NEW, "0x0000000000000000000000000000000000000000", EventFields.TX_HASH, "0xren"));
            assertThat(d.detect(fx.ctx(contract, renounce))).singleElement().satisfies(s -> assertThat(s.severity()).isEqualTo(Severity.INFO));
        }

        @Test void tronRenouncementToTheBase58ZeroAddressIsInfoNotMedium() {
            // TRON adapters decode address(0) of a TVM log as base58 T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb (0x41 + 20 zero bytes):
            // the former EVM-only "0x0+" check reported a TRON renounceOwnership as a MEDIUM ownership change
            Address tronContract = new Address(io.openchain.core.model.Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
            var renounce = new io.openchain.core.event.ChainEvent("t1", EventType.CONTRACT_ADMIN_CHANGE, io.openchain.core.model.Network.TRON,
                    1, "b", T0, T0, false, Map.of(EventFields.ADMIN_CONTRACT, tronContract.value(), EventFields.ADMIN_ROLE, "owner",
                    EventFields.ADMIN_PREVIOUS, "TJDENsfBJs4RFETt1X1W8wMDc8M5XnJhCe",
                    EventFields.ADMIN_NEW, io.openchain.core.model.Address.TRON_ZERO, EventFields.TX_HASH, "ab"));
            assertThat(d.detect(fx.ctx(tronContract, renounce))).singleElement().satisfies(s -> {
                assertThat(s.severity()).isEqualTo(Severity.INFO);
                assertThat(s.evidence().getFirst().description()).contains("(renounced)");
            });
            var transfer = new io.openchain.core.event.ChainEvent("t2", EventType.CONTRACT_ADMIN_CHANGE, io.openchain.core.model.Network.TRON,
                    2, "b", T0, T0, false, Map.of(EventFields.ADMIN_CONTRACT, tronContract.value(),
                    EventFields.ADMIN_NEW, "TJDENsfBJs4RFETt1X1W8wMDc8M5XnJhCe", EventFields.TX_HASH, "ac"));
            assertThat(d.detect(fx.ctx(tronContract, transfer))).singleElement().satisfies(s -> assertThat(s.severity()).isEqualTo(Severity.MEDIUM));
            // an EIP-1967 upgrade to address(0) is not a renouncement
            var upgrade = event("e3", EventType.CONTRACT_ADMIN_CHANGE, Map.of(EventFields.ADMIN_CONTRACT, contract.value(),
                    EventFields.ADMIN_ROLE, "implementation", EventFields.ADMIN_NEW, "0x0000000000000000000000000000000000000000"));
            assertThat(d.detect(fx.ctx(contract, upgrade))).singleElement().satisfies(s -> assertThat(s.severity()).isEqualTo(Severity.MEDIUM));
        }

        @Test void ignoresOtherEventsAndRetracted() {
            assertThat(d.detect(fx.ctx(contract, event("e1", EventType.APPROVAL, Map.of(EventFields.ADMIN_CONTRACT, contract.value()))))).isEmpty();
            assertThat(d.detect(fx.ctx(contract, fx.flow(src, contract, "1", min(0))))).isEmpty();
            var retracted = new io.openchain.core.event.ChainEvent("e9", EventType.CONTRACT_ADMIN_CHANGE, NET, 1, "0xb", T0, T0, true,
                    Map.of(EventFields.ADMIN_CONTRACT, contract.value(), EventFields.ADMIN_NEW, "0xnew"));
            assertThat(d.detect(fx.ctx(contract, retracted))).isEmpty();
        }
    }
}
