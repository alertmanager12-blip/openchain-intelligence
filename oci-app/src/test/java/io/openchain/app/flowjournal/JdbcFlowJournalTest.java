package io.openchain.app.flowjournal;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.persistence.PostgresSupport;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** ADR-0026 amendment 1 against PostgreSQL 17: monthly partitions, once per event, read by address, coverage start. */
@PostgresSupport.RequiresDocker
class JdbcFlowJournalTest {
    static final Network NET = Network.TRON;
    static final Token USDT = new Token(NET, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    final Address w = new Address(NET, "TJournalWatched1111111111111111111"), o = new Address(NET, "TJournalOther11111111111111111111");
    NamedParameterJdbcTemplate jdbc;
    JdbcFlowJournal journal;
    int seq;

    @BeforeEach void database() {
        var ds = PostgresSupport.newDatabase().dataSource();
        PostgresSupport.migrate(ds);
        jdbc = new NamedParameterJdbcTemplate(ds);
        journal = new JdbcFlowJournal(jdbc);
    }

    ValueFlow flow(Address from, Address to, String at, long raw) {
        String tx = "%064x".formatted(++seq);
        return new ValueFlow("tron:" + tx + ":0", NET, tx, Instant.parse(at), from, to, USDT, BigInteger.valueOf(raw));
    }

    @Test void partitionsByMonthOncePerEventAndReadByAddress() {
        ValueFlow a = flow(w, o, "2026-10-31T23:59:59Z", 5), b = flow(o, w, "2026-11-01T00:00:00Z", 123_456_789_012_345L), c = flow(o, o, "2026-11-02T00:00:00Z", 1);
        journal.ensurePartition(a.timestamp());
        journal.ensurePartition(b.timestamp());
        assertThat(journal.append(List.of(a, b, c), FlowJournal.Source.LIVE)).isEqualTo(3);
        assertThat(journal.append(List.of(a, b), FlowJournal.Source.LIVE)).as("same events are left as they are").isZero();
        assertThat(journal.rowsInDefault()).isZero();
        assertThat(jdbc.queryForList("select tableoid::regclass::text from flow_journal order by ts", Map.of(), String.class))
                .containsExactly("flow_journal_2026_10", "flow_journal_2026_11", "flow_journal_2026_11");
        assertThat(journal.involving(w, null, null, 10)).containsExactly(a, b);
        assertThat(journal.involving(w, Instant.parse("2026-11-01T00:00:00Z"), null, 10)).containsExactly(b);
        assertThat(journal.involving(o, null, null, 10)).as("a self-transfer appears once").containsExactly(a, b, c);
    }

    @Test void aMonthWithoutPartitionGoesToTheDefaultAndIsNeverLost() {
        ValueFlow a = flow(w, o, "2027-03-05T00:00:00Z", 7);
        assertThat(journal.append(List.of(a), FlowJournal.Source.LIVE)).isEqualTo(1);
        assertThat(journal.rowsInDefault()).isEqualTo(1);
        journal.ensurePartition(a.timestamp()); // cannot take rows held by the default partition: left as it is
        assertThat(journal.involving(w, null, null, 10)).containsExactly(a);
    }

    @Test void coverageKeepsTheEarliestStart() {
        assertThat(journal.coveredSince(w)).isEmpty();
        journal.cover(w, Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-10T00:00:00Z"));
        journal.cover(w, Instant.parse("2026-11-12T00:00:00Z"), Instant.parse("2026-11-12T00:00:00Z"));
        journal.cover(w, Instant.parse("2025-11-01T00:00:00Z"), Instant.parse("2026-11-12T00:00:00Z")); // a loaded history
        assertThat(journal.coveredSince(w)).contains(Instant.parse("2025-11-01T00:00:00Z"));
    }
}
