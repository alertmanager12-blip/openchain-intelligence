package io.openchain.app.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.app.persistence.PostgresSupport;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.prediction.Evaluation;
import io.openchain.intelligence.prediction.Prediction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** ADR-0023 amendment 2 against PostgreSQL 17: predictions round-trip, evaluations are written once, never changed. */
@PostgresSupport.RequiresDocker
class JdbcPredictionStoreTest {
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    NamedParameterJdbcTemplate jdbc;
    JdbcPredictionStore store;

    @BeforeEach void database() {
        var ds = PostgresSupport.newDatabase().dataSource();
        PostgresSupport.migrate(ds);
        jdbc = new NamedParameterJdbcTemplate(ds);
        store = new JdbcPredictionStore(jdbc);
    }

    Prediction p(String id, Double conf, int hour) {
        return new Prediction(id, Prediction.Type.WAKE_CONTINUED_OUTFLOW, new Address(Network.TRON, "TPredWallet1111111111111111111111"), conf,
                List.of("sig-1"), List.of("a limit"), T0.plus(Duration.ofHours(hour)), T0.plus(Duration.ofHours(hour + 72)), 1, "tron:x:0", "prediction@1");
    }

    @Test void roundTripOpenListAndCounts() {
        store.insert(p("pr_1", null, 0));
        store.insert(p("pr_1", 0.9, 0)); // same id: left as it is
        store.insert(p("pr_2", 0.6, 1));
        assertThat(store.open()).extracting(Prediction::id).containsExactly("pr_1", "pr_2");
        assertThat(store.open().getFirst().confidence()).as("uncalibrated stays null").isNull();
        store.evaluate(new Evaluation("pr_2", Evaluation.Outcome.REALISED, List.of("tron:y:0"), "condition observed", T0.plus(Duration.ofHours(5))));
        assertThat(store.open()).extracting(Prediction::id).containsExactly("pr_1");
        assertThat(store.list("REALISED", 10)).singleElement().satisfies(e -> {
            assertThat(e.prediction().confidence()).isEqualTo(0.6);
            assertThat(e.evaluation().evidence()).containsExactly("tron:y:0");
        });
        assertThat(store.list("OPEN", 10)).extracting(e -> e.prediction().id()).containsExactly("pr_1");
        assertThat(store.list(null, 10)).extracting(e -> e.prediction().id()).containsExactly("pr_2", "pr_1");
        assertThat(store.counts(Prediction.Type.WAKE_CONTINUED_OUTFLOW)).containsExactly(Map.entry(Evaluation.Outcome.REALISED, 1L));
    }

    @Test void neitherAPredictionNorItsEvaluationCanBeChanged() {
        store.insert(p("pr_1", null, 0));
        store.evaluate(new Evaluation("pr_1", Evaluation.Outcome.NOT_REALISED, List.of(), "expiry passed", T0.plus(Duration.ofDays(4))));
        assertThatThrownBy(() -> store.evaluate(new Evaluation("pr_1", Evaluation.Outcome.REALISED, List.of(), "x", T0))).isNotNull();
        assertThatThrownBy(() -> jdbc.update("update predictions set confidence = 1", Map.of())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update prediction_evaluations set outcome = 'REALISED'", Map.of())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from prediction_evaluations", Map.of())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("insert into predictions (id, type, network, subject, signal_ids, limits, issued_at, expires_at, "
                + "min_outgoing, model) values ('bad', 'X', 'tron', 'T', '[]', '[]', now(), now() - interval '1 hour', 1, 'm')", Map.of()))
                .as("an expiry before the issue is refused").isNotNull();
    }
}
