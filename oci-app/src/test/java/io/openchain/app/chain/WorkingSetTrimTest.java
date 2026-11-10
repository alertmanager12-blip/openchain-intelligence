package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.ApiTestSupport;
import io.openchain.app.OciApplication;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** ADR-0024 step 3a: the trim bounds the working set; kept addresses, active wallets' signals and catch-ups survive. */
class WorkingSetTrimTest {
    static ConfigurableApplicationContext ctx;

    @BeforeAll static void start() {
        ctx = new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class).profiles("test")
                .run("--server.port=0", "--oci.demo.networks=tron");
    }

    @AfterAll static void stop() { if (ctx != null) ctx.close(); }

    static Address poly(char c) { return new Address(Network.POLYGON, "0x" + String.valueOf(c).repeat(40)); }
    static Address bsc(char c) { return new Address(Network.BSC, "0x" + String.valueOf(c).repeat(40)); }
    static final Token USDT = new Token(Network.POLYGON, "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", "USDT", 6);
    static final Token BUSDT = new Token(Network.BSC, "0x55d398326f99059ff775485246999027b3197955", "USDT", 18);

    static ValueFlow flow(String id, Address from, Address to, Token t, Instant at) {
        return new ValueFlow(from.network().id() + ":" + id + ":0", from.network(), id, at, from, to, t, BigInteger.TEN);
    }

    static Signal signal(String id, Address a, Severity s, Instant at) {
        return new Signal(id, SignalType.UNUSUAL_AMOUNT, a, null, s, 0.5, Signal.EvidenceLevel.ANOMALY, List.of(), "test@1", at);
    }

    @Test void trimsPerNetworkAgainstEventTimeAndKeepsWhatScoresAndOperatorsNeed() {
        ChainIngestionService chains = ctx.getBean(ChainIngestionService.class);
        IntelligencePipeline intel = ctx.getBean(IntelligencePipeline.class);
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(10));
        Address idleA = poly('b'), idleB = poly('c'), kept = poly('d'), active = poly('e'), activePeer = poly('f');
        // BSC: a catch-up after downtime, every flow 3 days old (its own watermark is 3 days old)
        Address lateA = bsc('1'), lateB = bsc('2');
        Instant late = now.minus(Duration.ofDays(3));
        chains.restoreIntelligence(List.of(
                flow("0xold1", idleA, idleB, USDT, old),
                flow("0xold2", idleA, kept, USDT, old),
                flow("0xold3", active, activePeer, USDT, old),
                flow("0xnew", active, activePeer, USDT, now.minus(Duration.ofHours(1))),
                flow("0xlate1", lateA, lateB, BUSDT, late.minus(Duration.ofHours(2))),
                flow("0xlate2", lateA, lateB, BUSDT, late)),
                List.of(signal("sig-idle", idleB, Severity.LOW, old),
                        signal("sig-idle-high", idleB, Severity.HIGH, old),
                        signal("sig-active", active, Severity.LOW, old),
                        signal("sig-kept", kept, Severity.LOW, old)),
                List.of());

        var r = chains.trimWorkingSet(Duration.ofHours(24), Duration.ofHours(48), Duration.ofDays(7), kept::equals);

        // flows: the old ones go, unless they touch a kept address; the catch-up network keeps its recent history
        assertThat(intel.flows().contains("polygon:0xold1:0")).isFalse();
        assertThat(intel.flows().contains("polygon:0xold3:0")).isFalse();
        assertThat(intel.flows().contains("polygon:0xold2:0")).isTrue();
        assertThat(intel.flows().contains("polygon:0xnew:0")).isTrue();
        assertThat(intel.flows().contains("bsc:0xlate1:0")).isTrue();
        assertThat(intel.flows().contains("bsc:0xlate2:0")).isTrue();
        // profiles: idle ones go (not the kept one), active and catching-up ones stay
        assertThat(intel.profiles().profile(idleB)).isEmpty();
        assertThat(intel.profiles().profile(kept)).isPresent();
        assertThat(intel.profiles().profile(active)).isPresent();
        assertThat(intel.profiles().profile(lateA)).isPresent();
        // signals: an active wallet keeps them (its risk score is computed from them), HIGH and kept stay
        assertThat(intel.signals().findById("sig-idle")).isEmpty();
        assertThat(intel.signals().findById("sig-idle-high")).isPresent();
        assertThat(intel.signals().findById("sig-active")).isPresent();
        assertThat(intel.signals().findById("sig-kept")).isPresent();
        assertThat(chains.trackedWallets()).doesNotContain(idleB).contains(active, kept);
        assertThat(r.flows()).isGreaterThanOrEqualTo(2);
        assertThat(chains.lastTrim()).isEqualTo(r);
    }

    /** ADR-0024 step 3b: whatever the time windows, the caps hold. */
    @Test void hardCapsHoldWhateverTheTimeWindows() {
        ChainIngestionService chains = ctx.getBean(ChainIngestionService.class);
        IntelligencePipeline intel = ctx.getBean(IntelligencePipeline.class);
        Instant now = Instant.now();
        List<ValueFlow> flows = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Address from = new Address(Network.POLYGON, "0x" + String.format("%040x", 0x1000 + i));
            Address to = new Address(Network.POLYGON, "0x" + String.format("%040x", 0x2000 + i));
            flows.add(flow("0xcap" + i, from, to, USDT, now.minus(Duration.ofMinutes(40 - i))));
        }
        chains.restoreIntelligence(flows, List.of(), List.of());

        var r = chains.trimWorkingSet(Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(30), 10, 10, 12, a -> false);

        assertThat(intel.flows().size()).isLessThanOrEqualTo(10);
        assertThat(intel.profiles().size()).isLessThanOrEqualTo(12);
        assertThat(intel.flows().contains("polygon:0xcap39:0")).isTrue(); // the newest stay
        assertThat(intel.flows().contains("polygon:0xcap0:0")).isFalse();
        assertThat(r.remainingFlows()).isEqualTo(intel.flows().size());
    }
}
