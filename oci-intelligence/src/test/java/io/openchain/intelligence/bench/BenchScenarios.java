package io.openchain.intelligence.bench;

import static io.openchain.intelligence.Fx.*;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The scenario library of the detection bench. SYNTHETIC DATA ONLY: every address, label and amount is fictional.
 * Each scenario states the behaviour it encodes; expectations follow what an analyst would want, not what the
 * detectors currently do (gaps show up as false negatives or missing capabilities).
 */
public final class BenchScenarios {
    private BenchScenarios() {}

    /**
     * Business rule R1 (decision of 2026-11-10): UNUSUAL_AMOUNT is a statistical anomaly (EvidenceLevel ANOMALY), not a
     * qualification of suspicious activity. It is legitimate when the amount is at least 3 times the median of the
     * wallet's transfers in the same direction, and must stay LOW at most; it remains a false positive below that ratio
     * (B01) or when the baseline mixes directions (CON1, CON2 of S01). Changed S01/HUB from "forbid" to "tolerate".
     */
    static final String RULE_ANOMALY = "R1: statistical anomaly (>= 3x own same-direction median), ANOMALY level, LOW at most";

    static final Token LINK = new Token(NET, "0xdemo0000000000000000000000000000000link", "LINK", 18);
    private static final AtomicInteger SEQ = new AtomicInteger();

    static ValueFlow usdt(Address from, Address to, String amount, Instant at) { return tok(from, to, amount, USDT, at); }

    static ValueFlow tok(Address from, Address to, String amount, Token t, Instant at) {
        return ValueFlow.of(transfer("0xbench" + SEQ.incrementAndGet(), 0, from, to, amount, t, at));
    }

    static AddressLabel label(Address a, LabelCategory c, double reliability, EvidenceLevel lvl) {
        return new AddressLabel(a, c, "SYNTHETIC " + c, "synthetic-bench", reliability, T0.minus(Duration.ofDays(30)), lvl, "synthetic://" + a.value());
    }

    static Instant at(long seconds) { return T0.plus(Duration.ofSeconds(seconds)); }

    public static List<BenchScenario> all() {
        return List.of(layeringHub(), muleFastForward(), sanctionedDirectAndIndirect(), outboundToMixer(), receivedThenDrained(),
                structuringUsdt(), coordinatedFunding(), savingsDrained(), constantAmountMerchant(), dustThenSpend(), nonStablecoinBelowTenThousand(),
                spoofedDustFromSanctioned());
    }

    /** Mixer-funded hub disperses to 20 wallets, two collectors consolidate, exits to a mixer and an exchange, part comes back, structuring, peel chain. */
    static BenchScenario layeringHub() {
        Address mix0 = a("b1a0"), src = a("b1a1"), hub = a("b1a2"), c1 = a("b1a3"), c2 = a("b1a4"), exch = a("b1a5"), mix1 = a("b1a6");
        var s = new BenchScenario("S01-layering-hub", BenchScenario.Kind.SUSPICIOUS,
                "Mixer-funded hub: dispersal to 20 wallets, consolidation by 2 collectors, exit to a mixer and an exchange, return to the hub, structuring, peel chain")
                .name(mix0, "MIX0").name(src, "SRC").name(hub, "HUB").name(c1, "CON1").name(c2, "CON2").name(exch, "EXCH").name(mix1, "MIX1")
                .label(label(mix0, LabelCategory.MIXER, 0.9, EvidenceLevel.RISK_SIGNAL))
                .label(label(mix1, LabelCategory.MIXER, 0.9, EvidenceLevel.RISK_SIGNAL))
                .label(label(exch, LabelCategory.EXCHANGE, 0.95, EvidenceLevel.CONFIRMED_BY_SOURCE));
        List<Address> w = new ArrayList<>();
        for (int i = 0; i < 20; i++) { Address x = a("b1b" + Integer.toHexString(i)); w.add(x); s.name(x, "W" + (i + 1)); }
        s.flow(usdt(mix0, src, "120000", at(0))).flow(usdt(src, hub, "100000", at(120)));
        for (int i = 0; i < 20; i++) s.flow(usdt(hub, w.get(i), "5000", at(300 + i)));
        for (int i = 0; i < 10; i++) s.flow(usdt(w.get(i), c1, "4990", at(600 + 30L * i)));
        s.flow(usdt(c1, exch, "20000", at(1500))).flow(usdt(c1, mix1, "29900", at(1560)));
        for (int i = 10; i < 20; i++) s.flow(usdt(w.get(i), c2, "4980", at(1800 + 30L * (i - 10))));
        s.flow(usdt(c2, hub, "49000", at(2400)));
        Address d1 = a("b1c1"), d2 = a("b1c2"), d3 = a("b1c3");
        s.flow(usdt(hub, d1, "9500", at(2700))).flow(usdt(hub, d2, "9600", at(3000))).flow(usdt(hub, d3, "9700", at(3300)));
        Address p1 = a("b1d1"), p2 = a("b1d2"), p3 = a("b1d3"), p4 = a("b1d4");
        s.name(p1, "P1").name(p2, "P2").name(p3, "P3").name(p4, "P4");
        s.flow(usdt(hub, p1, "20000", at(3600)))
                .flow(usdt(p1, p2, "18000", at(3720))).flow(usdt(p1, exch, "2000", at(3730)))
                .flow(usdt(p2, p3, "16000", at(3840))).flow(usdt(p2, exch, "2000", at(3850)))
                .flow(usdt(p3, p4, "14000", at(3960))).flow(usdt(p3, exch, "2000", at(3970)));
        return s.expect(hub, SignalType.FLOW_CONCENTRATION).expect(hub, SignalType.CIRCULAR_FLOW).expect(hub, SignalType.STRUCTURING)
                .expect(hub, SignalType.INDIRECT_EXPOSURE).expect(hub, SignalType.FAST_FORWARDING)
                .expect(src, SignalType.MIXER_EXPOSURE).expect(c1, SignalType.MIXER_EXPOSURE)
                .tolerate(hub, SignalType.UNUSUAL_AMOUNT, Severity.LOW, RULE_ANOMALY).forbid(c1, SignalType.UNUSUAL_AMOUNT).forbid(c2, SignalType.UNUSUAL_AMOUNT)
                .missing(c1, "CONSOLIDATION").missing(c2, "CONSOLIDATION").missing(p1, "PEEL_CHAIN")
                .missing(hub, "DOWNSTREAM_EXPOSURE").missing(hub, "WALLET_SET_COMMON_FUNDING");
    }

    /** A mule receives 50,000 and forwards 99 % within 10 minutes. */
    static BenchScenario muleFastForward() {
        Address x = a("b2a0"), mule = a("b2a1"), y = a("b2a2");
        return new BenchScenario("S02-mule-fast-forward", BenchScenario.Kind.SUSPICIOUS, "Pass-through: 50,000 received, 49,500 forwarded within 10 minutes")
                .name(mule, "MULE")
                .flow(usdt(x, mule, "50000", at(0))).flow(usdt(mule, y, "49500", at(600)))
                .expect(mule, SignalType.FAST_FORWARDING);
    }

    /** Direct receipt from a sanctioned address, then a 2-hop recipient. */
    static BenchScenario sanctionedDirectAndIndirect() {
        Address sanc = a("b3a0"), v = a("b3a1"), z = a("b3a2");
        return new BenchScenario("S03-sanctioned-exposure", BenchScenario.Kind.SUSPICIOUS, "10,000 from a sanctioned address, 5,000 passed on one hop further")
                .name(sanc, "SANCTIONED").name(v, "V").name(z, "Z")
                .label(label(sanc, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE))
                .flow(usdt(sanc, v, "10000", at(0))).flow(usdt(v, z, "5000", at(3600)))
                .expect(v, SignalType.SANCTIONED_EXPOSURE).expect(z, SignalType.INDIRECT_EXPOSURE);
    }

    /** A wallet sends to a mixer; its funder should be seen as exposed downstream. */
    static BenchScenario outboundToMixer() {
        Address funder = a("b4a0"), w = a("b4a1"), mix = a("b4a2");
        return new BenchScenario("S04-outbound-to-mixer", BenchScenario.Kind.SUSPICIOUS, "Funder pays a wallet which sends the funds to a mixer")
                .name(funder, "FUNDER").name(w, "W").name(mix, "MIX")
                .label(label(mix, LabelCategory.MIXER, 0.9, EvidenceLevel.RISK_SIGNAL))
                .flow(usdt(funder, w, "30000", at(0))).flow(usdt(w, mix, "29000", at(1800)))
                .expect(w, SignalType.MIXER_EXPOSURE).missing(funder, "DOWNSTREAM_EXPOSURE");
    }

    /** A wallet receives 80,000 and empties it to three addresses within 30 minutes: a pass-through, not a drain of savings. */
    static BenchScenario receivedThenDrained() {
        Address src = a("b5a0"), d = a("b5a1");
        var s = new BenchScenario("S05-received-then-forwarded", BenchScenario.Kind.SUSPICIOUS, "80,000 received then 79,000 sent to 3 addresses within 30 minutes")
                .name(d, "D").flow(usdt(src, d, "80000", at(0)));
        for (int i = 0; i < 3; i++) s.flow(usdt(d, a("b5b" + i), i == 0 ? "27000" : "26000", at(600L * (i + 1))));
        return s.expect(d, SignalType.FAST_FORWARDING);
    }

    /** A wallet holding 60,000 for 10 days is emptied within 40 minutes (typical of a compromised wallet). */
    static BenchScenario savingsDrained() {
        Address v = a("b8a0");
        var s = new BenchScenario("S08-savings-drained", BenchScenario.Kind.SUSPICIOUS, "Balance of 60,000 held 10 days, 58,000 sent to 4 new addresses within 40 minutes")
                .name(v, "VICTIM").flow(usdt(a("b8a9"), v, "60000", at(0)));
        long t = 10 * 86400L;
        for (int i = 0; i < 4; i++) s.flow(usdt(v, a("b8b" + i), i < 2 ? "15000" : "14000", at(t + 600L * i)));
        return s.expect(v, SignalType.RAPID_DRAIN);
    }

    /** Three USDT outflows just below 10,000 within 6 hours. */
    static BenchScenario structuringUsdt() {
        Address e = a("b6a0");
        var s = new BenchScenario("S06-structuring-usdt", BenchScenario.Kind.SUSPICIOUS, "Three USDT transfers of 9,600 to 9,800 within 6 hours")
                .name(e, "E").flow(usdt(a("b6a9"), e, "40000", at(0)));
        s.flow(usdt(e, a("b6b1"), "9600", at(3600))).flow(usdt(e, a("b6b2"), "9700", at(10800))).flow(usdt(e, a("b6b3"), "9800", at(21600)));
        return s.expect(e, SignalType.STRUCTURING);
    }

    /** One funder pays 10 fresh wallets in the same minute; all send to one collector. */
    static BenchScenario coordinatedFunding() {
        Address f = a("b7a0"), col = a("b7a1");
        var s = new BenchScenario("S07-coordinated-funding", BenchScenario.Kind.SUSPICIOUS, "10 wallets funded by one address within a minute, all paying one collector")
                .name(f, "FUNDER").name(col, "COLLECTOR");
        for (int i = 0; i < 10; i++) {
            Address w = a("b7b" + i);
            s.flow(usdt(f, w, "2000", at(i * 5L))).flow(usdt(w, col, "1990", at(1800 + i * 60L)));
        }
        return s.missing(f, "WALLET_SET_COMMON_FUNDING").missing(col, "CONSOLIDATION");
    }

    /** BENIGN: a merchant always paid 50 USDT receives one 60 USDT payment. */
    static BenchScenario constantAmountMerchant() {
        Address m = a("c1a0");
        var s = new BenchScenario("B01-constant-amount-merchant", BenchScenario.Kind.BENIGN, "Merchant paid 50 USDT 15 times, then 60 USDT once").name(m, "MERCHANT");
        for (int i = 0; i < 15; i++) s.flow(usdt(a("c1b" + Integer.toHexString(i)), m, "50", at(i * 3600L)));
        s.flow(usdt(a("c1c0"), m, "60", at(16 * 3600L)));
        return s.forbid(m, SignalType.UNUSUAL_AMOUNT);
    }

    /** BENIGN: a funded wallet receives 1 USDT from a stranger, then makes a normal 1,000 payment. */
    static BenchScenario dustThenSpend() {
        Address w = a("c2a0");
        return new BenchScenario("B02-dust-then-spend", BenchScenario.Kind.BENIGN, "Wallet funded with 10,000; 3 h later receives 1 USDT, then pays 1,000 five minutes after")
                .name(w, "W")
                .flow(usdt(a("c2a1"), w, "10000", at(0))).flow(usdt(a("c2a2"), w, "1", at(3 * 3600L))).flow(usdt(w, a("c2a3"), "1000", at(3 * 3600L + 300)))
                .forbid(w, SignalType.FAST_FORWARDING);
    }

    /** BENIGN: three LINK transfers of about 9,500 (the 10,000 threshold is a USD stablecoin rule). */
    static BenchScenario nonStablecoinBelowTenThousand() {
        Address w = a("c3a0");
        return new BenchScenario("B03-non-stablecoin-amounts", BenchScenario.Kind.BENIGN, "Three LINK transfers of 9,500 to 9,700 within 6 hours")
                .name(w, "W")
                .flow(tok(a("c3a9"), w, "40000", LINK, at(0)))
                .flow(tok(w, a("c3b1"), "9500", LINK, at(3600))).flow(tok(w, a("c3b2"), "9600", LINK, at(7200))).flow(tok(w, a("c3b3"), "9700", LINK, at(10800)))
                .forbid(w, SignalType.STRUCTURING);
    }

    /** BENIGN for the victim: dust sent by a sanctioned address must not make the victim exposed. */
    static BenchScenario spoofedDustFromSanctioned() {
        Address sanc = a("c4a0"), victim = a("c4a1");
        return new BenchScenario("B04-dust-from-sanctioned", BenchScenario.Kind.BENIGN, "Sanctioned address sends 0.000001 USDT to a victim")
                .name(sanc, "SANCTIONED").name(victim, "VICTIM")
                .label(label(sanc, LabelCategory.SANCTIONED, 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE))
                .flow(usdt(sanc, victim, "0.000001", at(0)))
                .forbid(victim, SignalType.SANCTIONED_EXPOSURE).forbid(victim, SignalType.INDIRECT_EXPOSURE);
    }
}
