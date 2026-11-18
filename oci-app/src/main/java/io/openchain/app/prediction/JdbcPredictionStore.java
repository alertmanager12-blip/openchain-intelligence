package io.openchain.app.prediction;

import io.openchain.app.persistence.Sql;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.prediction.Evaluation;
import io.openchain.intelligence.prediction.Prediction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code predictions} and {@code prediction_evaluations} (V18). */
public final class JdbcPredictionStore implements PredictionStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final String P = "p.id, p.type, p.network, p.subject, p.confidence, p.signal_ids::text, p.limits::text, p.issued_at, p.expires_at, "
            + "p.min_outgoing, p.excluded_event, p.model";
    private static final String E = "e.outcome, e.evidence::text, e.reason, e.evaluated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPredictionStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(Prediction p) {
        jdbc.update("insert into predictions (id, type, network, subject, confidence, signal_ids, limits, issued_at, expires_at, min_outgoing, "
                + "excluded_event, model) values (:id, :type, :net, :subject, :conf, cast(:signals as jsonb), cast(:limits as jsonb), :issued, "
                + ":expires, :min, :excluded, :model) on conflict (id) do nothing", new MapSqlParameterSource().addValue("id", p.id())
                .addValue("type", p.type().name()).addValue("net", p.subject().network().id()).addValue("subject", p.subject().value())
                .addValue("conf", p.confidence()).addValue("signals", JSON.writeValueAsString(p.signalIds()))
                .addValue("limits", JSON.writeValueAsString(p.limits())).addValue("issued", Sql.ts(p.issuedAt()))
                .addValue("expires", Sql.ts(p.expiresAt())).addValue("min", p.minOutgoing()).addValue("excluded", p.excludedEvent())
                .addValue("model", p.model()));
    }

    @Override public void evaluate(Evaluation e) {
        jdbc.update("insert into prediction_evaluations (prediction_id, outcome, evidence, reason, evaluated_at) values (:id, :o, "
                + "cast(:ev as jsonb), :r, :at)", new MapSqlParameterSource().addValue("id", e.predictionId()).addValue("o", e.outcome().name())
                .addValue("ev", JSON.writeValueAsString(e.evidence())).addValue("r", e.reason()).addValue("at", Sql.ts(e.evaluatedAt())));
    }

    @Override public List<Prediction> open() {
        return jdbc.query("select " + P + " from predictions p where not exists (select 1 from prediction_evaluations e "
                + "where e.prediction_id = p.id) order by p.issued_at, p.id", Map.of(), (rs, n) -> prediction(rs));
    }

    @Override public List<Entry> list(String outcome, int limit) {
        String where = outcome == null ? "" : "OPEN".equals(outcome) ? " where e.prediction_id is null" : " where e.outcome = :o";
        return jdbc.query("select " + P + ", " + E + " from predictions p left join prediction_evaluations e on e.prediction_id = p.id" + where
                + " order by p.issued_at desc, p.id limit :l", new MapSqlParameterSource().addValue("o", outcome).addValue("l", limit),
                (rs, n) -> new Entry(prediction(rs), rs.getString(13) == null ? null : new Evaluation(rs.getString(1),
                        Evaluation.Outcome.valueOf(rs.getString(13)), JSON.readValue(rs.getString(14), STRINGS), rs.getString(15),
                        rs.getTimestamp(16).toInstant())));
    }

    @Override public Map<Evaluation.Outcome, Long> counts(Prediction.Type type) {
        Map<Evaluation.Outcome, Long> m = new EnumMap<>(Evaluation.Outcome.class);
        jdbc.query("select e.outcome, count(*) from prediction_evaluations e join predictions p on p.id = e.prediction_id where p.type = :t "
                + "group by e.outcome", Map.of("t", type.name()), rs -> { m.put(Evaluation.Outcome.valueOf(rs.getString(1)), rs.getLong(2)); });
        return m;
    }

    private static Prediction prediction(ResultSet rs) throws SQLException {
        double c = rs.getDouble(5);
        Double conf = rs.wasNull() ? null : c;
        return new Prediction(rs.getString(1), Prediction.Type.valueOf(rs.getString(2)), new Address(Network.fromId(rs.getString(3)), rs.getString(4)),
                conf, JSON.readValue(rs.getString(6), STRINGS), JSON.readValue(rs.getString(7), STRINGS), rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9).toInstant(), rs.getInt(10), rs.getString(11), rs.getString(12));
    }
}
