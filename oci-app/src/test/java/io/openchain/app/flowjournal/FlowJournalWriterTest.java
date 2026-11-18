package io.openchain.app.flowjournal;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** ADR-0026 amendment 1: only transfers of watched addresses, never blocking, a full queue counted with its start. */
class FlowJournalWriterTest {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-18T00:00:00Z");
    final Address w = new Address(Network.TRON, "TJournalWatched1111111111111111111"), o = new Address(Network.TRON, "TJournalOther11111111111111111111"),
            x = new Address(Network.TRON, "TJournalX111111111111111111111111");
    final InMemoryFlowJournal journal = new InMemoryFlowJournal();
    int seq;

    ValueFlow flow(Address from, Address to, int s) {
        String tx = "%064x".formatted(++seq);
        return new ValueFlow("tron:" + tx + ":0", Network.TRON, tx, T0.plusSeconds(s), from, to, USDT, BigInteger.ONE);
    }

    @Test void onlyTransfersOfWatchedAddressesAreJournalled() {
        var writer = new FlowJournalWriter(journal, Set.of(w)::contains, () -> Set.of(w), Clock.fixed(T0, ZoneOffset.UTC), 10);
        writer.observe(flow(w, o, 1));
        writer.observe(flow(o, w, 2));
        writer.observe(flow(o, x, 3)); // neither watched
        writer.drain();
        assertThat(writer.written()).isEqualTo(2);
        assertThat(journal.involving(w, null, null, 10)).hasSize(2);
        assertThat(journal.involving(x, null, null, 10)).isEmpty();
        writer.coverWatched();
        assertThat(journal.coveredSince(w)).contains(T0);
    }

    @Test void aFullQueueDropsCountsAndSaysSinceWhen() {
        var writer = new FlowJournalWriter(journal, Set.of(w)::contains, () -> Set.of(w), Clock.fixed(T0, ZoneOffset.UTC), 2);
        for (int i = 0; i < 5; i++) writer.observe(flow(w, o, i));
        assertThat(writer.backlog()).isEqualTo(2);
        assertThat(writer.dropped()).isEqualTo(3);
        assertThat(writer.droppedSince()).isEqualTo(T0.plusSeconds(2));
    }
}
