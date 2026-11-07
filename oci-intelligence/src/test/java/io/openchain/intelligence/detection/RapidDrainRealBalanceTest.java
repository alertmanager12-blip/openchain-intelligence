package io.openchain.intelligence.detection;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.intelligence.Fx;
import io.openchain.intelligence.detection.detectors.RapidDrainDetector;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Real chains: the platform only sees flows since its start, so a wallet funded before looks empty in the flow-based
 * estimate and any spending looked like a 100% drain (false positives on Ethereum mainnet). On an authoritative network
 * the detector uses the provider-reported balance and stays silent without one.
 */
class RapidDrainRealBalanceTest {
    final Fx fx = new Fx();
    final Address me = a("me"), src = a("5ac");
    final BalanceBook book = new BalanceBook();

    DetectionContext ctx(ValueFlow trigger) {
        return new DetectionContext(me, trigger, null, null, fx.flows, fx.labels, fx.config, T0.plus(Duration.ofDays(400)), book);
    }

    @Test void walletFundedBeforeTheIndexerStartSpendingPartOfItsRealBalanceIsNotADrain() {
        book.markAuthoritative(NET);
        fx.flow(src, me, "10", days(0));                                   // the only inflow the platform saw
        ValueFlow spend = fx.flow(me, a("x1"), "5000", days(10));
        book.recordSenderBalanceBefore(spend.eventId(), new BigDecimal("1000010")); // real balance from the provider
        assertThat(new RapidDrainDetector().detect(ctx(spend))).isEmpty();
        // without the real balance, the flow-based estimate (10 USDT seen) makes the same spend a "drain": the false positive
        assertThat(new RapidDrainDetector().detect(fx.ctx(me, spend))).hasSize(1);
    }

    @Test void spendingMoreThan80PercentOfTheRealBalanceIsADrain() {
        book.markAuthoritative(NET);
        ValueFlow first = fx.flow(me, a("x1"), "6000", days(10));
        ValueFlow second = fx.flow(me, a("x2"), "3000", days(10).plusSeconds(60));
        book.recordSenderBalanceBefore(first.eventId(), new BigDecimal("10000"));
        book.recordSenderBalanceBefore(second.eventId(), new BigDecimal("4000"));
        assertThat(new RapidDrainDetector().detect(ctx(second))).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo(SignalType.RAPID_DRAIN);
            assertThat(s.summary()).isEqualTo("Sent 90% of its USDT balance (9,000 USDT) to 2 addresses within 1 hour.");
            assertThat(s.evidence()).anyMatch(e -> e.description().contains("provider-reported available balance 10000 USDT"));
        });
    }

    @Test void unknownRealBalanceGivesNoSignal() {
        book.markAuthoritative(NET);
        fx.flow(src, me, "1000", days(0));
        ValueFlow drain = fx.flow(me, a("x1"), "990", days(10)); // would be 99% by estimate
        assertThat(new RapidDrainDetector().detect(ctx(drain))).isEmpty();
        // not authoritative (simulated chain): the exact flow-based estimate is used
        assertThat(new RapidDrainDetector().detect(new DetectionContext(me, drain, null, null, fx.flows, fx.labels, fx.config,
                T0.plus(Duration.ofDays(400)), new BalanceBook()))).hasSize(1);
    }
}
