package io.openchain.app.chain;

import io.openchain.app.persistence.Sql;
import io.openchain.app.persistence.TransactionRunner;
import io.openchain.blockchain.backfill.FreezeBackfillStore;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.TokenFreeze;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code backfill_progress} + {@code backfill_freeze_events} (Flyway V2). Tokens are re-resolved from the registry on read. */
public final class JdbcFreezeBackfillStore implements FreezeBackfillStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner tx;
    private final TokenRegistry tokens;

    public JdbcFreezeBackfillStore(NamedParameterJdbcTemplate jdbc, TransactionRunner tx, TokenRegistry tokens) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tokens = tokens;
    }

    @Override public Optional<Progress> load(String id) {
        return jdbc.query("select id, network, from_block, last_scanned_block, freezes_found, updated_at from backfill_progress where id = :id",
                Map.of("id", id), (rs, n) -> new Progress(rs.getString("id"), Network.fromId(rs.getString("network")), rs.getLong("from_block"),
                        rs.getLong("last_scanned_block"), rs.getLong("freezes_found"), Sql.instant(rs, "updated_at"))).stream().findFirst();
    }

    @Override public void saveChunk(Progress p, List<TokenFreeze> freezes) {
        tx.run(() -> {
            jdbc.update("insert into backfill_progress (id, network, from_block, last_scanned_block, freezes_found, updated_at) "
                    + "values (:id, :network, :from, :last, :found, :at) on conflict (id) do update set network = excluded.network, "
                    + "from_block = excluded.from_block, last_scanned_block = excluded.last_scanned_block, "
                    + "freezes_found = excluded.freezes_found, updated_at = excluded.updated_at",
                    new MapSqlParameterSource().addValue("id", p.id()).addValue("network", p.network().id()).addValue("from", p.fromBlock())
                            .addValue("last", p.lastScannedBlock()).addValue("found", p.freezesFound()).addValue("at", Sql.ts(p.updatedAt())));
            if (freezes.isEmpty()) return;
            MapSqlParameterSource[] rows = freezes.stream().map(z -> new MapSqlParameterSource().addValue("bid", p.id())
                    .addValue("eid", z.eventId()).addValue("network", z.network().id()).addValue("tx", z.txHash())
                    .addValue("li", z.logIndex()).addValue("bn", z.blockNumber()).addValue("bh", z.blockHash())
                    .addValue("bt", Sql.ts(z.timestamp())).addValue("target", z.target().value()).addValue("contract", z.token().contract())
                    .addValue("issuer", z.issuer()).addValue("action", z.action().name()).addValue("event", z.eventName()))
                    .toArray(MapSqlParameterSource[]::new);
            jdbc.batchUpdate("insert into backfill_freeze_events (backfill_id, event_id, network, tx_hash, log_index, block_number, "
                    + "block_hash, block_time, target, token_contract, issuer, action, event_name) values (:bid, :eid, :network, :tx, :li, "
                    + ":bn, :bh, :bt, :target, :contract, :issuer, :action, :event) on conflict (backfill_id, event_id) do nothing", rows);
        });
    }

    @Override public List<TokenFreeze> freezes(String id) {
        return jdbc.query("select network, tx_hash, log_index, block_number, block_hash, block_time, target, token_contract, issuer, "
                + "action, event_name from backfill_freeze_events where backfill_id = :id order by block_number, log_index, tx_hash",
                Map.of("id", id), (rs, n) -> {
                    Network net = Network.fromId(rs.getString("network"));
                    return new TokenFreeze(net, rs.getString("tx_hash"), rs.getInt("log_index"), rs.getLong("block_number"),
                            rs.getString("block_hash"), Sql.instant(rs, "block_time"), new Address(net, rs.getString("target")),
                            tokens.resolve(net, rs.getString("token_contract")), rs.getString("issuer"),
                            TokenFreeze.Action.valueOf(rs.getString("action")), rs.getString("event_name"));
                });
    }
}
