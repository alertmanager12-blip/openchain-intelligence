package io.openchain.app.sanctions;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.persistence.PostgresSupport;
import io.openchain.intelligence.label.ofac.OfacSdnParser;
import io.openchain.intelligence.label.ofac.SdnPublication;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** ADR-0027 against PostgreSQL 17: an accepted publication comes back whole; refused ones are recorded, never returned. */
@PostgresSupport.RequiresDocker
class JdbcSanctionsListStoreTest {
    NamedParameterJdbcTemplate jdbc;
    JdbcSanctionsListStore store;
    SdnPublication pub;

    @BeforeEach void database() throws Exception {
        var ds = PostgresSupport.newDatabase().dataSource();
        PostgresSupport.migrate(ds);
        jdbc = new NamedParameterJdbcTemplate(ds);
        store = new JdbcSanctionsListStore(jdbc, 3);
        try (InputStream in = getClass().getResourceAsStream("/ofac/sdn-advanced-extract.xml")) { pub = OfacSdnParser.parse(in); }
    }

    @Test void theLatestAcceptedPublicationComesBackWhole() {
        assertThat(store.latestAccepted()).isEmpty();
        Instant at = Instant.parse("2026-11-18T22:00:00Z");
        var saved = store.saveAccepted(pub, at, "abc");
        store.saveRefused(null, at.plusSeconds(60), null, "unreadable document");
        var back = store.latestAccepted().orElseThrow();
        assertThat(back.id()).isEqualTo(saved.id());
        assertThat(back.fetchedAt()).isEqualTo(at);
        assertThat(back.sha256()).isEqualTo("abc");
        assertThat(back.publication()).isEqualTo(pub);
        assertThat(jdbc.queryForObject("select count(*) from sanctions_publications where status = 'REFUSED'", Map.of(), Integer.class)).isEqualTo(1);
    }

    @Test void onlyTheLastAcceptedPublicationsKeepTheirAddresses() {
        for (int d = 18; d <= 22; d++) {
            store.saveAccepted(new SdnPublication(LocalDate.of(2026, 11, d), pub.addresses()), Instant.parse("2026-11-" + d + "T00:00:00Z"), "s" + d);
        }
        assertThat(store.latestAccepted().orElseThrow().publication().issued()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(jdbc.queryForObject("select count(distinct publication_id) from sanctions_addresses", Map.of(), Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from sanctions_publications", Map.of(), Integer.class))
                .as("the record of every download is kept").isEqualTo(5);
    }
}
