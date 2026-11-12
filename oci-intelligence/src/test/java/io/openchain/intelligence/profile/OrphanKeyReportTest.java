package io.openchain.intelligence.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.InMemoryFlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Step 3 diagnostic: dedup keys kept by profiles after their flow left the store are counted, read-only. */
class OrphanKeyReportTest {
    static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);

    static Address a(String c) { return new Address(Network.TRON, ("T" + c.repeat(33)).substring(0, 34)); }
    static ValueFlow flow(int i, Address from, Address to, Instant t) {
        String h = String.format("%064x", i);
        return new ValueFlow("tron:" + h + ":0", Network.TRON, h, t, from, to, USDT, BigInteger.TEN);
    }

    @Test void countsOrphanKeysPerProfileAndIdleTimeWithoutChangingAnything() {
        WalletProfileService profiles = new WalletProfileService(Clock.fixed(NOW, ZoneOffset.UTC));
        InMemoryFlowStore store = new InMemoryFlowStore();
        Address hot = a("h"), quiet = a("q"), peer = a("p");
        for (int i = 0; i < 10; i++) { var f = flow(i, peer, hot, NOW.minusSeconds(60 + i)); store.add(f); profiles.ingest(f); }   // recent
        for (int i = 10; i < 14; i++) { var f = flow(i, peer, quiet, NOW.minusSeconds(2 * 86_400)); store.add(f); profiles.ingest(f); } // 2 days idle
        profiles.ingest(new ChainTransaction(Network.TRON, "f".repeat(64), 1, "0xb", NOW.minusSeconds(30), hot, peer, BigInteger.ZERO,
                BigInteger.ONE, true, null, false)); // transaction-level key, never a flow id
        store.evictBefore(n -> NOW.minusSeconds(3_600), x -> false); // the 4 old flows leave the store
        int before = profiles.size();

        OrphanKeyReport r = profiles.orphanKeys(store::contains, 0); // sample everything
        assertThat(r.profiles()).isEqualTo(3);
        assertThat(r.txLevelKeys()).isEqualTo(2);                    // hot and peer
        assertThat(r.orphanFlowKeys()).isEqualTo(8);                 // 4 flows x (quiet, peer)
        assertThat(r.distinctOrphanFlowIdsEstimate()).isEqualTo(4);  // the same 4 ids, held by two profiles
        assertThat(r.liveFlowKeys()).isEqualTo(20);                  // 10 flows x (hot, peer)
        assertThat(r.orphanTxHashKeys()).isEqualTo(8 + 2);           // tx hashes of the evicted flows + of the transaction
        assertThat(r.profilesWithOrphans()).isEqualTo(2);
        assertThat(r.orphanFlowKeysByProfile()).containsExactly(1, 0, 2, 0, 0, 0, 0); // hot: 0 orphan; quiet and peer: 4 each (bucket 2-4)
        assertThat(r.orphanFlowKeysByIdleSeconds()[0]).isEqualTo(4); // peer: active within the hour
        assertThat(r.orphanFlowKeysByIdleSeconds()[3]).isEqualTo(4); // quiet: idle 2 days (bucket <= 3 d)
        assertThat(r.orphanFlowKeyBytesUpperBound()).isEqualTo(8 * OrphanKeyReport.entryBytes(flow(10, peer, quiet, NOW).eventId()));
        assertThat(profiles.size()).isEqualTo(before);               // read-only
        assertThat(store.size()).isEqualTo(10);
    }
}
