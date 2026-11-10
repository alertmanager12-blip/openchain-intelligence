package io.openchain.intelligence.profile;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.core.model.Address;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** ADR-0024 step 3b: a very active wallet keeps a bounded footprint and exact totals. */
class WalletProfileCompactionTest {
    static final int N = 20_000;
    final Address hot = a("h07"), whale = a("3a1e");
    final WalletProfileService svc = new WalletProfileService(Clock.fixed(days(400), ZoneOffset.UTC));

    /** N deposits from N distinct senders, one per minute after a 30-day silence, plus 50 from one large counterparty. */
    private void feedHotWallet() {
        svc.ingest(transfer("0xfirst", 0, whale, hot, "1", USDT, days(0)));
        for (int i = 0; i < N; i++) {
            svc.ingest(transfer("0x" + Integer.toHexString(0x100000 + i), 0, a("5e" + Integer.toHexString(0x100000 + i)), hot, "0.5", USDT,
                    days(30).plus(Duration.ofMinutes(i))));
        }
        for (int i = 0; i < 50; i++) svc.ingest(transfer("0xw" + i, 0, whale, hot, "2", USDT, days(30).plus(Duration.ofMinutes(N + i))));
    }

    @Test void totalsStayExactBeyondTheCaps() {
        feedHotWallet();
        WalletProfile p = svc.profile(hot).orElseThrow();
        assertThat(p.inboundTransfers()).isEqualTo(N + 51);
        assertThat(p.transactionCount()).isEqualTo(N + 51);
        assertThat(p.tokens().values().iterator().next().inflow()).isEqualByComparingTo(String.valueOf(1 + N * 0.5 + 100));
        assertThat(p.firstActivity()).isEqualTo(days(0));
        assertThat(p.lastActivity()).isEqualTo(days(30).plus(Duration.ofMinutes(N + 49)));
        // the 30-day silence was folded long ago and is still the longest gap
        assertThat(p.maxDormancyGap()).isEqualTo(Duration.ofDays(30));
        assertThat(p.topCounterparties().getFirst().counterparty()).isEqualTo(whale);
        assertThat(p.topCounterparties().getFirst().received()).isEqualTo(51);
    }

    @Test void footprintIsBounded() {
        feedHotWallet();
        WalletProfileAccumulator acc = svc.accumulator(hot);
        synchronized (acc) {
            assertThat(acc.retainedEntries()).isLessThanOrEqualTo(2 * WalletProfileAccumulator.RECENT_KEYS
                    + WalletProfileAccumulator.ACTIVITY_CAP + 2 * WalletProfileAccumulator.COUNTERPARTY_CAP + WalletProfileAccumulator.CONTRACT_CAP + 1);
        }
    }

    @Test void recentDuplicatesAreStillIgnored() {
        feedHotWallet();
        var replay = transfer("0xw49", 0, whale, hot, "2", USDT, days(30).plus(Duration.ofMinutes(N + 49)));
        assertThat(svc.ingest(replay)).isFalse();
        assertThat(svc.profile(hot).orElseThrow().inboundTransfers()).isEqualTo(N + 51);
    }

    @Test void lateEventOlderThanEverythingMovesFirstActivityAndItsGap() {
        feedHotWallet();
        svc.ingest(transfer("0xlate", 0, whale, hot, "1", USDT, days(0).minus(Duration.ofDays(45))));
        WalletProfile p = svc.profile(hot).orElseThrow();
        assertThat(p.firstActivity()).isEqualTo(days(0).minus(Duration.ofDays(45)));
        assertThat(p.maxDormancyGap()).isEqualTo(Duration.ofDays(45));
    }
}
