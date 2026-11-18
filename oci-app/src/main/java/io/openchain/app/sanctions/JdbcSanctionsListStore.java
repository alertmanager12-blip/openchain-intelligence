package io.openchain.app.sanctions;

import io.openchain.app.persistence.Sql;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.label.ofac.SdnAddress;
import io.openchain.intelligence.label.ofac.SdnPublication;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;

/** {@code sanctions_publications} and {@code sanctions_addresses} (V21). Addresses are kept for the last {@code keep} accepted publications. */
public class JdbcSanctionsListStore implements SanctionsListStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final int keep;

    public JdbcSanctionsListStore(NamedParameterJdbcTemplate jdbc, int keep) {
        this.jdbc = jdbc;
        this.keep = Math.max(1, keep);
    }

    @Override public Optional<Stored> latestAccepted() {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, issued, fetched_at, sha256 from sanctions_publications "
                + "where source = :src and status = 'ACCEPTED' order by id desc limit 1", Map.of("src", SdnPublication.SOURCE));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = rows.getFirst();
        long id = ((Number) r.get("id")).longValue();
        List<SdnAddress> addresses = jdbc.query("select network, address, currencies, sdn_uid, name, programs from sanctions_addresses "
                + "where publication_id = :id order by ord", Map.of("id", id), (rs, i) -> new SdnAddress(
                new Address(Network.fromId(rs.getString(1)), rs.getString(2)), split(rs.getString(3)), rs.getString(4), rs.getString(5),
                split(rs.getString(6))));
        LocalDate issued = ((java.sql.Date) r.get("issued")).toLocalDate();
        Instant fetched = ((Timestamp) r.get("fetched_at")).toInstant();
        return Optional.of(new Stored(id, fetched, (String) r.get("sha256"), new SdnPublication(issued, addresses)));
    }

    @Override @Transactional public Stored saveAccepted(SdnPublication p, Instant fetchedAt, String sha256) {
        var keys = new GeneratedKeyHolder();
        jdbc.update("insert into sanctions_publications (source, issued, fetched_at, sha256, address_count, status) "
                + "values (:src, :issued, :at, :sha, :n, 'ACCEPTED')", new MapSqlParameterSource().addValue("src", SdnPublication.SOURCE)
                .addValue("issued", java.sql.Date.valueOf(p.issued())).addValue("at", Sql.ts(fetchedAt)).addValue("sha", sha256)
                .addValue("n", p.addresses().size()), keys, new String[] {"id"});
        long id = keys.getKey().longValue();
        jdbc.batchUpdate("insert into sanctions_addresses (publication_id, ord, network, address, currencies, sdn_uid, name, programs) "
                + "values (:id, :ord, :net, :a, :cur, :uid, :name, :prog)", java.util.stream.IntStream.range(0, p.addresses().size()).mapToObj(i -> { var a = p.addresses().get(i); return new MapSqlParameterSource()
                .addValue("id", id).addValue("ord", i).addValue("net", a.address().network().id()).addValue("a", a.address().value())
                .addValue("cur", String.join(",", a.currencies())).addValue("uid", a.sdnUid()).addValue("name", a.name())
                .addValue("prog", String.join(",", a.programs())); }).toArray(MapSqlParameterSource[]::new));
        // addresses of older accepted publications beyond the last `keep` are dropped; their record stays
        jdbc.update("delete from sanctions_addresses where publication_id in (select id from sanctions_publications where source = :src "
                + "and status = 'ACCEPTED' order by id desc offset :keep)", Map.of("src", SdnPublication.SOURCE, "keep", keep));
        return new Stored(id, fetchedAt, sha256, p);
    }

    @Override public void saveRefused(LocalDate issued, Instant at, String sha256, String reason) {
        jdbc.update("insert into sanctions_publications (source, issued, fetched_at, sha256, status, reason) values (:src, :issued, :at, :sha, 'REFUSED', :reason)",
                new MapSqlParameterSource().addValue("src", SdnPublication.SOURCE).addValue("issued", issued == null ? null : java.sql.Date.valueOf(issued))
                        .addValue("at", Sql.ts(at)).addValue("sha", sha256).addValue("reason", reason));
    }

    private static List<String> split(String s) {
        return s == null || s.isEmpty() ? List.of() : Arrays.asList(s.split(","));
    }
}
