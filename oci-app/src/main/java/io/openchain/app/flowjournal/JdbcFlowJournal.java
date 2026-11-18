package io.openchain.app.flowjournal;

import io.openchain.app.persistence.Sql;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code flow_journal} and {@code journal_coverage} (V20). */
public final class JdbcFlowJournal implements FlowJournal {
    private static final String COLS = "network, event_id, tx_hash, ts, from_address, to_address, token_contract, token_symbol, token_decimals, raw_amount";
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcFlowJournal(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public int append(List<ValueFlow> flows, Source source) {
        if (flows.isEmpty()) return 0;
        MapSqlParameterSource[] batch = flows.stream().map(f -> new MapSqlParameterSource().addValue("net", f.network().id())
                .addValue("id", f.eventId()).addValue("tx", f.txHash()).addValue("ts", Sql.ts(f.timestamp())).addValue("from", f.from().value())
                .addValue("to", f.to().value()).addValue("contract", f.token().contract()).addValue("symbol", f.token().symbol())
                .addValue("dec", f.token().decimals()).addValue("raw", new BigDecimal(f.rawAmount())).addValue("src", source.name()))
                .toArray(MapSqlParameterSource[]::new);
        int[] r = jdbc.batchUpdate("insert into flow_journal (" + COLS + ", source) values (:net, :id, :tx, :ts, :from, :to, :contract, :symbol, "
                + ":dec, :raw, :src) on conflict do nothing", batch);
        int n = 0;
        for (int x : r) if (x > 0) n += x;
        return n;
    }

    @Override public List<ValueFlow> involving(Address a, Instant from, Instant to, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("net", a.network().id()).addValue("a", a.value())
                .addValue("from", Sql.ts(from == null ? Instant.EPOCH : from)).addValue("to", Sql.ts(to == null ? Instant.parse("9999-01-01T00:00:00Z") : to))
                .addValue("l", limit);
        // two index range scans (sender, receiver) merged: a self-transfer appears once
        return jdbc.query("select " + COLS + " from (select " + COLS + " from flow_journal where network = :net and from_address = :a and ts between :from and :to "
                + "union select " + COLS + " from flow_journal where network = :net and to_address = :a and ts between :from and :to) j "
                + "order by ts, event_id limit :l", p, (rs, n) -> {
                    Network net = Network.fromId(rs.getString(1));
                    String contract = rs.getString(7);
                    Token t = contract == null ? Token.nativeOf(net, rs.getString(8), rs.getInt(9)) : new Token(net, contract, rs.getString(8), rs.getInt(9));
                    return new ValueFlow(rs.getString(2), net, rs.getString(3), rs.getTimestamp(4).toInstant(), new Address(net, rs.getString(5)),
                            new Address(net, rs.getString(6)), t, rs.getBigDecimal(10).toBigIntegerExact());
                });
    }

    @Override public Optional<Instant> coveredSince(Address a) {
        List<Instant> r = jdbc.query("select since from journal_coverage where network = :net and address = :a",
                Map.of("net", a.network().id(), "a", a.value()), (rs, n) -> rs.getTimestamp(1).toInstant());
        return r.stream().findFirst();
    }

    @Override public void cover(Address a, Instant since, Instant enrolledAt) {
        jdbc.update("insert into journal_coverage (network, address, since, enrolled_at) values (:net, :a, :since, :at) "
                + "on conflict (network, address) do update set since = least(journal_coverage.since, excluded.since)",
                new MapSqlParameterSource().addValue("net", a.network().id()).addValue("a", a.value()).addValue("since", Sql.ts(since))
                        .addValue("at", Sql.ts(enrolledAt)));
    }

    private static final Pattern NAME = Pattern.compile("flow_journal_\\d{4}_\\d{2}");

    @Override public void ensurePartition(Instant at) {
        YearMonth m = YearMonth.from(at.atZone(ZoneOffset.UTC)), next = m.plusMonths(1);
        int y = m.getYear(), mo = m.getMonthValue(), ny = next.getYear(), nmo = next.getMonthValue();
        // DDL takes no bind parameters: the statement is built from integers and constants only (CodeQL java/concatenated-sql-query)
        String name = "flow_journal_" + y + "_" + (mo < 10 ? "0" : "") + mo;
        if (!NAME.matcher(name).matches()) throw new IllegalStateException(name);
        String from = y + "-" + mo + "-01 00:00:00+00", to = ny + "-" + nmo + "-01 00:00:00+00";
        // a month partition cannot be attached while the default partition holds rows of that month: they stay there
        Integer inDefault = jdbc.queryForObject("select count(*) from flow_journal_default where ts >= cast(:f as timestamptz) and ts < cast(:t as timestamptz)",
                Map.of("f", from, "t", to), Integer.class);
        if (inDefault != null && inDefault > 0) return;
        jdbc.getJdbcTemplate().execute("create table if not exists flow_journal_" + y + "_" + (mo < 10 ? "0" : "") + mo
                + " partition of flow_journal for values from ('" + y + "-" + mo + "-01 00:00:00+00') to ('" + ny + "-" + nmo + "-01 00:00:00+00')");
    }

    /** Rows outside any month partition (a safety net that should stay empty). */
    public long rowsInDefault() {
        Long n = jdbc.queryForObject("select count(*) from flow_journal_default", Map.of(), Long.class);
        return n == null ? 0 : n;
    }
}
