package io.openchain.alerting.engine;

import static io.openchain.alerting.Fx.T0;
import static io.openchain.alerting.Fx.header;
import static io.openchain.alerting.Fx.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.Fx;
import io.openchain.alerting.engine.EngineResult.Disposition;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WindowEngineTest {

    private static final String ANY_TRANSFER = "conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 0 } ] }\n";

    static CompiledRule countRule(String window, int threshold) {
        return Fx.rule(header("count") + ANY_TRANSFER + """
                aggregation: { window: %s, groupBy: [transaction.from], function: COUNT, operator: GREATER_THAN, threshold: %d }
                """.formatted(window, threshold));
    }

    static RuleEngine engine(CompiledRule... rules) {
        RuleEngine e = new RuleEngine(EngineConfig.defaults());
        for (CompiledRule r : rules) e.register(r);
        return e;
    }

    static int triggers(RuleEngine e, List<ChainEvent> events) {
        int n = 0;
        for (ChainEvent ev : events) n += e.process(ev).matches().size();
        return n;
    }

    // ---------------------------------------------------------------- required examples

    @Test void moreThan20TransactionsIn5MinutesFromSameAddress() {
        RuleEngine e = engine(countRule("5m", 20));
        for (int i = 0; i < 20; i++)
            assertThat(e.process(transfer("tx" + i, T0.plusSeconds(i * 10L), "TA", "TB" + i, "1")).matches()).isEmpty();
        // another sender does not contribute to TA's window
        assertThat(e.process(transfer("other", T0.plusSeconds(205), "TZ", "TB", "1")).matches()).isEmpty();
        List<RuleMatch> m = e.process(transfer("tx20", T0.plusSeconds(210), "TA", "TB", "1")).matches();
        assertThat(m).singleElement().satisfies(x -> {
            assertThat(x.groupKey()).isEqualTo("tron|TA");
            assertThat(x.evidence()).containsEntry("value", "21").containsEntry("function", "COUNT").containsEntry("threshold", "20");
            assertThat(x.matchedEventIds()).hasSize(21).contains("tx0", "tx20");
            assertThat(x.dedupKey()).isEqualTo("count:tron|TA");
        });
    }

    @Test void twentyOneTransactionsSpreadOverMoreThan5MinutesDoNotTrigger() {
        RuleEngine e = engine(countRule("5m", 20));
        int n = 0;
        for (int i = 0; i < 21; i++) n += e.process(transfer("tx" + i, T0.plusSeconds(i * 20L), "TA", "TB", "1")).matches().size();
        assertThat(n).isZero(); // span 400s: any 5m window holds at most 15 events
    }

    @Test void moreThan100kUsdtVolumeIn1Hour() {
        CompiledRule r = Fx.rule(header("volume") + """
                conditions: { logic: AND, rules: [ { field: transaction.token.symbol, operator: EQUALS, value: USDT } ] }
                aggregation: { window: 1h, groupBy: transaction.from, function: SUM, field: transaction.amount, operator: GREATER_THAN, threshold: "100000" }
                """);
        RuleEngine e = engine(r);
        assertThat(e.process(transfer("a", T0, "TA", "TB", "60000.000001")).matches()).isEmpty();
        assertThat(e.process(transfer("b", T0.plus(Duration.ofMinutes(30)), "TA", "TB", "39999.999999")).matches()).isEmpty(); // exactly 100000: not >
        RuleMatch m = e.process(transfer("c", T0.plus(Duration.ofMinutes(59)), "TA", "TB", "0.000001")).matches().getFirst();
        assertThat(new BigDecimal((String) m.evidence().get("value"))).isEqualByComparingTo("100000.000001");
        // one hour after the first event it has slid out of the window
        assertThat(e.process(transfer("d", T0.plus(Duration.ofMinutes(61)), "TA", "TB", "1")).matches()).isEmpty();
    }

    @Test void moreThan10DistinctRecipientsIn3Minutes() {
        CompiledRule r = Fx.rule(header("fanout") + ANY_TRANSFER + """
                aggregation: { window: 3m, groupBy: [transaction.from], function: DISTINCT_COUNT, field: transaction.to, operator: GREATER_THAN, threshold: 10 }
                """);
        RuleEngine e = engine(r);
        int n = 0;
        for (int i = 0; i < 30; i++) n += e.process(transfer("same" + i, T0.plusSeconds(i), "TA", "TB", "1")).matches().size();
        assertThat(n).isZero(); // 30 payments, 1 recipient
        for (int i = 0; i < 9; i++) n += e.process(transfer("d" + i, T0.plusSeconds(40 + i), "TA", "TR" + i, "1")).matches().size();
        assertThat(n).isZero(); // TB + 9 = 10 distinct, not > 10
        RuleMatch m = e.process(transfer("d9", T0.plusSeconds(60), "TA", "TR9", "1")).matches().getFirst();
        assertThat(m.evidence()).containsEntry("value", "11");
    }

    @Test void moreThan80PercentOfBalanceOutIn2Minutes() {
        CompiledRule r = Fx.rule(header("drain") + ANY_TRANSFER + """
                aggregation: { window: 2m, groupBy: [transaction.from], function: RATIO_OF_BALANCE, field: transaction.amount, operator: GREATER_THAN, threshold: "0.8" }
                """);
        RuleEngine e = engine(r);
        assertThat(e.process(Fx.with(transfer("a", T0, "TA", "TX", "500"), "wallet.balance", new BigDecimal("1000"))).matches()).isEmpty();
        assertThat(e.process(Fx.with(transfer("b", T0.plusSeconds(30), "TA", "TX", "300"), "wallet.balance", new BigDecimal("500"))).matches()).isEmpty();
        RuleMatch m = e.process(Fx.with(transfer("c", T0.plusSeconds(60), "TA", "TX", "1"), "wallet.balance", new BigDecimal("200"))).matches().getFirst();
        assertThat(new BigDecimal((String) m.evidence().get("value"))).isEqualByComparingTo("0.801");
        assertThat(m.evidence()).containsEntry("function", "RATIO_OF_BALANCE");

        // zero balance: ratio undefined, never triggers
        RuleEngine e2 = engine(r);
        assertThat(e2.process(Fx.with(transfer("z", T0, "TB", "TX", "500"), "wallet.balance", BigDecimal.ZERO)).matches()).isEmpty();
    }

    @Test void maxFunctionAndGreaterOrEqual() {
        CompiledRule r = Fx.rule(header("max") + ANY_TRANSFER + """
                aggregation: { window: 15m, function: MAX, field: transaction.amount, operator: GREATER_THAN_OR_EQUAL, threshold: 1000 }
                """);
        RuleEngine e = engine(r);
        assertThat(e.process(transfer("a", T0, "TA", "TB", "999.99")).matches()).isEmpty();
        assertThat(e.process(transfer("b", T0.plusSeconds(1), "TA", "TB", "1000")).matches()).hasSize(1);
        assertThat(e.process(transfer("c", T0.plusSeconds(2), "TQ", "TB", "1")).matches()).hasSize(1); // no groupBy: global per network
    }

    // ---------------------------------------------------------------- every window size

    @ParameterizedTest(name = "window {0}")
    @CsvSource({"1m,60", "5m,300", "15m,900", "1h,3600", "24h,86400", "90s,90"})
    void eachWindowSizeIsHalfOpen(String window, long seconds) {
        // threshold: more than 2 events. Events at t, t+W-1s, t+W: the window ending at t+W is (t, t+W] and holds 2
        RuleEngine e = engine(countRule(window, 2));
        assertThat(e.process(transfer("a", T0, "TA", "TB", "1")).matches()).isEmpty();
        assertThat(e.process(transfer("b", T0.plusSeconds(seconds - 1), "TA", "TB", "1")).matches()).isEmpty();
        assertThat(e.process(transfer("c", T0.plusSeconds(seconds), "TA", "TB", "1")).matches()).isEmpty();
        // a 4th event within W of b: window (b, ...] includes b, c, d = 3 > 2
        assertThat(e.process(transfer("d", T0.plusSeconds(seconds + 1), "TA", "TB", "1")).matches()).hasSize(1);
    }

    // ---------------------------------------------------------------- lateness, duplicates, reorg

    @Test void lateEventsWithinLatenessAreAccountedBeyondAreDroppedAndCounted() {
        RuleEngine e = new RuleEngine(EngineConfig.defaults().withAllowedLateness(Duration.ofMinutes(2)));
        e.register(countRule("5m", 2));
        e.process(transfer("a", T0.plusSeconds(200), "TA", "TB", "1"));
        e.process(transfer("b", T0.plusSeconds(260), "TA", "TB", "1"));
        // 90s late (watermark = 260 - 120 = 140): accepted and counted in its window -> 3 events in 5m
        EngineResult late = e.process(transfer("c", T0.plusSeconds(170), "TA", "TB", "1"));
        assertThat(late.disposition()).isEqualTo(Disposition.PROCESSED);
        assertThat(late.matches()).hasSize(1);
        assertThat(e.metrics().lateAccepted()).isEqualTo(1);
        // 230s late: beyond lateness -> dropped
        EngineResult tooLate = e.process(transfer("d", T0.plusSeconds(30), "TA", "TB", "1"));
        assertThat(tooLate.disposition()).isEqualTo(Disposition.LATE_DROPPED);
        assertThat(e.metrics().lateDropped()).isEqualTo(1);
        assertThat(e.watermark(Network.TRON)).contains(T0.plusSeconds(140));
    }

    @Test void lateEventCanCompleteALaterWindow() {
        // events at 0 and 290 (5m window): a late event at 100 makes the window ending at 290 hold 3
        RuleEngine e = new RuleEngine(EngineConfig.defaults().withAllowedLateness(Duration.ofMinutes(5)));
        e.register(countRule("5m", 2));
        e.process(transfer("a", T0, "TA", "TB", "1"));
        e.process(transfer("b", T0.plusSeconds(290), "TA", "TB", "1"));
        RuleMatch m = e.process(transfer("late", T0.plusSeconds(100), "TA", "TB", "1")).matches().getFirst();
        assertThat(m.eventTime()).isEqualTo(T0.plusSeconds(290));
        assertThat(m.matchedEventIds()).containsExactly("a", "late", "b");
    }

    @Test void duplicateEventIdsAreIgnored() {
        RuleEngine e = engine(countRule("5m", 2));
        ChainEvent a = transfer("same-id", T0, "TA", "TB", "1");
        e.process(a);
        assertThat(e.process(a).disposition()).isEqualTo(Disposition.DUPLICATE);
        assertThat(e.process(transfer("same-id", T0.plusSeconds(5), "TA", "TB", "1")).disposition()).isEqualTo(Disposition.DUPLICATE);
        assertThat(e.process(transfer("b", T0.plusSeconds(10), "TA", "TB", "1")).matches()).isEmpty(); // only 2 counted
        assertThat(e.metrics().duplicatesIgnored()).isEqualTo(2);
    }

    @Test void dedupCacheIsBoundedAndExpires() {
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now = new java.util.concurrent.atomic.AtomicReference<>(T0);
        java.time.Clock clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId z) { return this; }
            public java.time.Instant instant() { return now.get(); }
        };
        RuleEngine e = new RuleEngine(EngineConfig.defaults().withClock(clock).withDedup(2, Duration.ofMinutes(10)).withAllowedLateness(Duration.ofDays(1)));
        e.process(transfer("x1", T0, "TA", "TB", "1"));
        e.process(transfer("x2", T0, "TA", "TB", "1"));
        e.process(transfer("x3", T0, "TA", "TB", "1")); // evicts x1 (capacity 2)
        assertThat(e.process(transfer("x3", T0, "TA", "TB", "1")).disposition()).isEqualTo(Disposition.DUPLICATE);
        assertThat(e.process(transfer("x1", T0, "TA", "TB", "1")).disposition()).isEqualTo(Disposition.PROCESSED);
        now.set(T0.plus(Duration.ofMinutes(11)));
        assertThat(e.process(transfer("x1", T0, "TA", "TB", "1")).disposition()).isEqualTo(Disposition.PROCESSED); // TTL expired
    }

    @Test void reorgRetractionRemovesContribution() {
        RuleEngine e = engine(countRule("5m", 2));
        ChainEvent a = transfer("a", T0, "TA", "TB", "1");
        e.process(a);
        e.process(transfer("b", T0.plusSeconds(10), "TA", "TB", "1"));
        EngineResult r = e.process(Fx.removed(a));
        assertThat(r.disposition()).isEqualTo(Disposition.RETRACTED);
        assertThat(r.retractedEventId()).isEqualTo("a");
        assertThat(e.process(transfer("c", T0.plusSeconds(20), "TA", "TB", "1")).matches()).isEmpty(); // b, c = 2
        // duplicate / unknown retractions are ignored
        assertThat(e.process(Fx.removed(a)).disposition()).isEqualTo(Disposition.RETRACTION_IGNORED);
        assertThat(e.process(Fx.removed(transfer("never-seen", T0, "TA", "TB", "1"))).disposition()).isEqualTo(Disposition.RETRACTION_IGNORED);
        // the same event re-included in the canonical chain counts again
        assertThat(e.process(a).matches()).hasSize(1);
        assertThat(e.metrics().retractions()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- bounded memory

    @Test void groupByCardinalityIsBoundedWithLruEviction() {
        RuleEngine e = new RuleEngine(EngineConfig.defaults().withMaxGroupKeysPerRule(100));
        CompiledRule r = countRule("1h", 1);
        e.register(r);
        for (int i = 0; i < 1000; i++) e.process(transfer("k" + i, T0.plusMillis(i), "TS" + i, "TB", "1"));
        assertThat(e.groupKeyCount("count")).isEqualTo(100);
        assertThat(e.metrics().groupKeysEvicted()).isEqualTo(900);
        assertThat(e.metrics().snapshot()).containsEntry("oci.alerting.engine.groupkey.evicted", 900L);
        // recently used keys survive: TS999 still has its first event
        assertThat(e.process(transfer("k999b", T0.plusSeconds(5), "TS999", "TB", "1")).matches()).hasSize(1);
    }

    @Test void entriesPerKeyAreBounded() {
        RuleEngine e = new RuleEngine(EngineConfig.defaults().withMaxEntriesPerKey(50));
        e.register(countRule("24h", 1_000_000));
        for (int i = 0; i < 200; i++) e.process(transfer("e" + i, T0.plusSeconds(i), "TA", "TB", "1"));
        assertThat(e.metrics().windowEntriesDropped()).isEqualTo(150);
    }

    @Test void expiredStateIsSwept() {
        RuleEngine e = engine(countRule("1m", 5));
        e.process(transfer("a", T0, "TA", "TB", "1"));
        e.process(transfer("b", T0, "TC", "TB", "1"));
        assertThat(e.groupKeyCount("count")).isEqualTo(2);
        e.process(transfer("c", T0.plus(Duration.ofHours(1)), "TD", "TB", "1"));
        e.evictExpired();
        assertThat(e.groupKeyCount("count")).isEqualTo(1);
    }

    @Test void missingGroupKeyFieldSkipsAndIsCounted() {
        RuleEngine e = engine(countRule("5m", 0));
        Map<String, Object> f = Map.of("transaction.amount", BigDecimal.ONE);
        assertThat(e.process(Fx.event("nf", Network.TRON, EventType.TOKEN_TRANSFER, T0, f)).matches()).isEmpty();
        assertThat(e.metrics().snapshot()).containsEntry("oci.alerting.engine.groupkey.missing", 1L);
    }

    @Test void disabledAndOutOfScopeRulesAreSkipped() {
        CompiledRule disabled = Fx.rule(header("off") + "enabled: false\n" + ANY_TRANSFER);
        CompiledRule ethOnly = Fx.rule(header("eth") + "scope: { networks: [ethereum] }\n" + ANY_TRANSFER);
        RuleEngine e = engine(disabled, ethOnly);
        assertThat(e.process(transfer("a", T0, "TA", "TB", "1")).matches()).isEmpty();
    }
}
