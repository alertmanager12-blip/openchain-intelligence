package io.openchain.app.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.persistence.PostgresSupport;
import io.openchain.app.persistence.TransactionRunner;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.trace.Trace;
import io.openchain.intelligence.trace.TraceDiff;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** ADR-0022 amendment 1, step 3-1, against PostgreSQL 17: stored runs, replay, versions, STALE, client scope and RLS. */
@PostgresSupport.RequiresDocker
class TraceArtifactStoreTest {
    static final Network NET = Network.ETHEREUM;
    static final Token USD = new Token(NET, "0xusd0000000000000000000000000000000000001", "USDT", 0);
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    DataSource ds;
    NamedParameterJdbcTemplate jdbc;
    JdbcTraceArtifactStore store;
    final List<ValueFlow> flows = new ArrayList<>();
    final Clock clock = Clock.fixed(T0.plusSeconds(86_400), ZoneOffset.UTC);

    static Address a(String s) { return new Address(NET, "0x" + s); }

    ValueFlow flow(Address from, Address to, long amount, int minute) {
        String tx = "0x%064x".formatted(flows.size() + 1);
        ValueFlow f = new ValueFlow(NET.id() + ":" + tx + ":0", NET, tx, T0.plusSeconds(60L * minute), from, to, USD, BigInteger.valueOf(amount));
        flows.add(f);
        return f;
    }

    final Trace.Source live = new Trace.Source() {
        @Override public List<ValueFlow> outgoing(Address x) { return flows.stream().filter(f -> f.from().equals(x)).toList(); }
        @Override public List<ValueFlow> incoming(Address x) { return flows.stream().filter(f -> f.to().equals(x)).toList(); }
        @Override public boolean complete(Address x, Trace.Window w) { return true; }
        @Override public Optional<BigInteger> openingBalance(Address x, String k, Trace.Window w) { return Optional.of(BigInteger.ZERO); }
        @Override public List<AddressLabel> labels(Address x) { return List.of(); }
    };

    @BeforeEach void database() {
        ds = PostgresSupport.newDatabase().dataSource();
        PostgresSupport.migrate(ds);
        jdbc = new NamedParameterJdbcTemplate(ds);
        TransactionRunner tx = new TransactionRunner() {
            final org.springframework.transaction.support.TransactionTemplate t =
                    new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            @Override public <T> T inTransaction(java.util.function.Supplier<T> work) { return t.execute(s -> work.get()); }
        };
        store = new JdbcTraceArtifactStore(jdbc, tx);
        jdbc.update("insert into client_scopes (id, name) values ('client-b', 'B')", Map.of());
    }

    TraceArtifactService service() { return new TraceArtifactService(store, () -> live, clock); }

    @Test void aStoredRunReplaysToTheSameResultByteForByte() {
        flow(a("a"), a("b"), 100, 1); flow(a("b"), a("c"), 60, 2); flow(a("x"), a("b"), 20, 0);
        TraceArtifact t = service().run("default", Trace.Request.of(a("a"), Trace.Direction.FORWARD), "analyst");
        TraceArtifact stored = store.find("default", t.id()).orElseThrow();
        assertThat(stored.eventIds()).hasSize(3);
        Trace.Result replay = service().replay("default", t.id()).orElseThrow();
        // jsonb normalises key order and spacing: compare the objects, and their canonical serialisation
        assertThat(TraceJson.result(stored.resultJson())).isEqualTo(replay);
        assertThat(TraceJson.write(TraceJson.result(stored.resultJson()))).isEqualTo(TraceJson.write(replay));
    }

    @Test void aRerunIsANewVersionWithItsDiffAndTheOldOneIsUnchanged() {
        flow(a("a"), a("b"), 100, 1);
        ValueFlow bc = flow(a("b"), a("c"), 100, 2);
        TraceArtifact v1 = service().run("default", Trace.Request.of(a("a"), Trace.Direction.FORWARD), "analyst");
        flows.remove(bc); // reorganisation
        TraceArtifact v2 = service().rerun("default", v1.id(), "analyst").orElseThrow();
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.lineageId()).isEqualTo(v1.lineageId());
        assertThat(v2.previousId()).isEqualTo(v1.id());
        TraceDiff.Diff d = TraceJson.diff(v2.diffJson());
        assertThat(d.evidence()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(TraceDiff.Kind.REMOVED);
            assertThat(c.beforeEventId()).isEqualTo(bc.eventId());
        });
        Trace.Result v1Result = TraceJson.result(v1.resultJson());
        assertThat(TraceJson.result(store.find("default", v1.id()).orElseThrow().resultJson())).isEqualTo(v1Result);
        assertThat(service().replay("default", v1.id())).hasValue(v1Result); // still explainable from its frozen evidence
    }

    @Test void aRetractedFlowMarksTheArtifactsHoldingItStaleInEveryScope() {
        flow(a("a"), a("b"), 100, 1);
        ValueFlow bc = flow(a("b"), a("c"), 100, 2);
        TraceArtifact mine = service().run("default", Trace.Request.of(a("a"), Trace.Direction.FORWARD), "analyst");
        TraceArtifact theirs = service().run("client-b", Trace.Request.of(a("b"), Trace.Direction.FORWARD), "analyst-b");
        TraceArtifact unrelated = service().run("default", Trace.Request.of(a("c"), Trace.Direction.FORWARD), "analyst");
        assertThat(store.markStale(Set.of(bc.eventId()))).containsExactlyInAnyOrder(mine.id(), theirs.id(), unrelated.id())
                .as("c's trace read b->c as an incoming flow");
        assertThat(store.find("default", mine.id()).orElseThrow()).satisfies(x -> {
            assertThat(x.stale()).isTrue();
            assertThat(x.staleEventIds()).containsExactly(bc.eventId());
        });
    }

    /** N1, N4, N7: another scope's artifact is invisible, by id or in a list. */
    @Test void anArtifactOfAnotherScopeIsInvisible() {
        flow(a("a"), a("b"), 100, 1);
        TraceArtifact mine = service().run("default", Trace.Request.of(a("a"), Trace.Direction.FORWARD), "analyst");
        assertThat(store.find("client-b", mine.id())).isEmpty();
        assertThat(store.list("client-b", 50)).isEmpty();
        assertThat(service().replay("client-b", mine.id())).isEmpty();
        assertThat(service().rerun("client-b", mine.id(), "x")).isEmpty();
        assertThat(store.list("default", 50)).extracting(TraceArtifact::id).containsExactly(mine.id());
    }

    /** N10: with row-level security, a role that is not superuser sees no row unless oci.scope is set, then only its scope. */
    @Test void rowLevelSecurityHidesRowsFromANonSuperuserRole() throws Exception {
        flow(a("a"), a("b"), 100, 1);
        service().run("default", Trace.Request.of(a("a"), Trace.Direction.FORWARD), "analyst");
        service().run("client-b", Trace.Request.of(a("a"), Trace.Direction.FORWARD), "analyst-b");
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("create role oci_app_test nosuperuser nobypassrls");
            st.execute("grant select on trace_artifacts, trace_artifact_flows to oci_app_test");
            c.setAutoCommit(false);
            st.execute("set local role oci_app_test");
            try (var rs = st.executeQuery("select count(*) from trace_artifacts")) { rs.next(); assertThat(rs.getInt(1)).isZero(); }
            st.execute("select set_config('oci.scope', 'client-b', true)");
            try (var rs = st.executeQuery("select distinct client_scope_id from trace_artifacts")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("client-b");
                assertThat(rs.next()).isFalse();
            }
            c.rollback();
        }
    }
}
