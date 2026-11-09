package io.openchain.app.persistence;

import io.openchain.blockchain.indexer.Checkpoint;
import io.openchain.blockchain.indexer.CheckpointStore;
import io.openchain.blockchain.indexer.IndexedBlock;
import io.openchain.core.model.BlockRef;
import io.openchain.core.model.Network;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code indexer_checkpoints} (V8). Saves the last processed block and the block references of the reorg window (not
 * their events: one small upsert per block). After a restart the window's blocks come back without events, so a reorg of
 * a block indexed before the restart is detected but its events cannot be retracted.
 */
public final class JdbcCheckpointStore implements CheckpointStore {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public JdbcCheckpointStore(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override public Optional<Checkpoint> load(String indexerId) {
        return jdbc.query("select network, last_block, window_refs from indexer_checkpoints where indexer_id = :id", Map.of("id", indexerId),
                (rs, n) -> {
                    Network net = Network.fromId(rs.getString("network"));
                    List<IndexedBlock> window = new ArrayList<>();
                    for (JsonNode b : JSON.readTree(rs.getString("window_refs"))) {
                        window.add(new IndexedBlock(new BlockRef(net, b.path("number").asLong(), b.path("hash").asString(),
                                b.path("parentHash").isNull() ? null : b.path("parentHash").asString(),
                                b.path("timestamp").isNull() ? null : Instant.parse(b.path("timestamp").asString())), List.of()));
                    }
                    return new Checkpoint(net, rs.getLong("last_block"), window);
                }).stream().findFirst();
    }

    @Override public void save(String indexerId, Checkpoint c) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (IndexedBlock ib : c.window()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", ib.block().number());
            m.put("hash", ib.block().hash());
            m.put("parentHash", ib.block().parentHash());
            m.put("timestamp", ib.block().timestamp() == null ? null : ib.block().timestamp().toString());
            refs.add(m);
        }
        jdbc.update("insert into indexer_checkpoints (indexer_id, network, last_block, window_refs, updated_at) "
                + "values (:id, :network, :last, cast(:refs as jsonb), :at) on conflict (indexer_id) do update set "
                + "network = excluded.network, last_block = excluded.last_block, window_refs = excluded.window_refs, updated_at = excluded.updated_at",
                new MapSqlParameterSource().addValue("id", indexerId).addValue("network", c.network().id()).addValue("last", c.lastBlock())
                        .addValue("refs", JSON.writeValueAsString(refs)).addValue("at", Sql.ts(clock.instant())));
    }
}
