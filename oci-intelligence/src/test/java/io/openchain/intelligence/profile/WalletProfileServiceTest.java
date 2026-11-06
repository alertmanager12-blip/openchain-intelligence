package io.openchain.intelligence.profile;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WalletProfileServiceTest {
    final Address me = a("me"), bob = a("b0b"), carol = a("ca401");
    final WalletProfileService svc = new WalletProfileService(Clock.fixed(days(100), ZoneOffset.UTC));

    @Test void aggregatesFlowsExactly() {
        svc.ingest(transfer("0x1", 0, bob, me, "100.000001", USDT, days(0)));
        svc.ingest(transfer("0x2", 0, me, carol, "40.5", USDT, days(1)));
        svc.ingest(transfer("0x3", 0, bob, me, "0.1", USDT, days(10)));
        WalletProfile p = svc.profile(me).orElseThrow();
        TokenFlowStats s = p.tokens().values().iterator().next();
        assertThat(s.inflow()).isEqualByComparingTo("100.100001");
        assertThat(s.outflow()).isEqualByComparingTo("40.5");
        assertThat(s.balanceEstimate()).isEqualByComparingTo("59.600001");
        assertThat(p.transactionCount()).isEqualTo(3);
        assertThat(p.inboundTransfers()).isEqualTo(2);
        assertThat(p.outboundTransfers()).isEqualTo(1);
        assertThat(p.firstActivity()).isEqualTo(days(0));
        assertThat(p.lastActivity()).isEqualTo(days(10));
        assertThat(p.maxDormancyGap()).isEqualTo(Duration.ofDays(9));
        assertThat(p.txPerDay()).isEqualTo(0.3);
        assertThat(p.topCounterparties().getFirst().counterparty()).isEqualTo(bob);
        assertThat(p.topCounterparties().getFirst().received()).isEqualTo(2);
        // the counterparty side is profiled too
        assertThat(svc.profile(carol).orElseThrow().tokens().values().iterator().next().inflow()).isEqualByComparingTo("40.5");
    }

    @Test void duplicateEventsAreIgnored() {
        var t = transfer("0x1", 0, bob, me, "100", USDT, days(0));
        assertThat(svc.ingest(t)).isTrue();
        assertThat(svc.ingest(t)).isFalse();
        assertThat(svc.ingest(transfer("0x1", 0, bob, me, "100", USDT, days(0)))).isFalse();
        WalletProfile p = svc.profile(me).orElseThrow();
        assertThat(p.inboundTransfers()).isEqualTo(1);
        assertThat(p.tokens().values().iterator().next().inflow()).isEqualByComparingTo("100");
    }

    @Test void outOfOrderEventsGiveSameProfile() {
        var other = new WalletProfileService(Clock.fixed(days(100), ZoneOffset.UTC));
        var e1 = transfer("0x1", 0, bob, me, "1", USDT, days(0));
        var e2 = transfer("0x2", 0, me, bob, "1", USDT, days(5));
        var e3 = transfer("0x3", 0, bob, me, "1", USDT, days(7));
        svc.ingest(e1); svc.ingest(e2); svc.ingest(e3);
        other.ingest(e3); other.ingest(e1); other.ingest(e2);
        assertThat(other.profile(me).orElseThrow()).isEqualTo(svc.profile(me).orElseThrow());
    }

    @Test void transactionsCountContractInteractionsAndNativeValue() {
        Address contract = a("c0ffee");
        var tx = new ChainTransaction(NET, "0xabc", 1, "0xb", days(1), me, contract, BigInteger.ZERO, BigInteger.ONE, true, "0xa9059cbb", true);
        var pay = new ChainTransaction(NET, "0xdef", 2, "0xb", days(2), bob, me, new BigInteger("1500000000000000000"), BigInteger.ONE, true, null, false);
        svc.ingest(tx); svc.ingest(tx); svc.ingest(pay);
        WalletProfile p = svc.profile(me).orElseThrow();
        assertThat(p.contractInteractions()).isEqualTo(1);
        assertThat(p.distinctContracts()).isEqualTo(1);
        assertThat(p.transactionCount()).isEqualTo(2);
        assertThat(p.tokens().get("ethereum:native:ETH").inflow()).isEqualByComparingTo("1.5");
    }

    @Test void unknownAddressHasNoProfile() {
        assertThat(svc.profile(a("nobody"))).isEmpty();
        assertThat(svc.profileOrEmpty(a("nobody")).hasActivity()).isFalse();
    }
}
