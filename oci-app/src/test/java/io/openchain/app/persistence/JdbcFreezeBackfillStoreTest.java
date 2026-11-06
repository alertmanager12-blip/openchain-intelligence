package io.openchain.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.app.chain.JdbcFreezeBackfillStore;
import io.openchain.blockchain.backfill.FreezeBackfill;
import io.openchain.blockchain.backfill.FreezeBackfillStore;
import io.openchain.blockchain.backfill.FreezeLogScanner;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenFreeze;
import io.openchain.core.provider.ProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Freeze backfill progress and found events in PostgreSQL 17 (Flyway V2): resume across "restarts", idempotent inserts. */
@PostgresSupport.RequiresDocker
class JdbcFreezeBackfillStoreTest {

    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);

    DataSource ds;
    NamedParameterJdbcTemplate jdbc;
    TransactionRunner tx;

    @BeforeEach
    void setUp() {
        ds = PostgresSupport.newDatabase().dataSource();
        PostgresSupport.migrate(ds);
        jdbc = new NamedParameterJdbcTemplate(ds);
        TransactionTemplate t = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx = new TransactionRunner() {
            @Override public <T> T inTransaction(java.util.function.Supplier<T> work) { return t.execute(s -> work.get()); }
        };
    }

    JdbcFreezeBackfillStore store() { return new JdbcFreezeBackfillStore(jdbc, tx, new DefaultTokenRegistry()); }

    static TokenFreeze freeze(long block, TokenFreeze.Action action) {
        return new TokenFreeze(Network.TRON, "%064x".formatted(block), 0, block, block % 2 == 0 ? null : "%064x".formatted(block + 1),
                Instant.parse("2025-01-01T00:00:00Z").plusSeconds(block * 3), new Address(Network.TRON, "TJDENsfBJs4RFETt1X1W8wMDc8M5XnJhCe"),
                USDT, "tether", action, action == TokenFreeze.Action.FREEZE ? "AddedBlackList" : "RemovedBlackList");
    }

    /** Scanner over freezes at blocks 10 (freeze) and 2500 (unfreeze); fails once at {@code failFrom}. */
    static final class Scanner implements FreezeLogScanner {
        final List<Long> starts = new ArrayList<>();
        long failFrom = -1;
        @Override public Network network() { return Network.TRON; }
        @Override public long backfillSafeHead() { return 3_000; }
        @Override public List<TokenFreeze> scanFreezes(long from, long to) {
            starts.add(from);
            if (from == failFrom) { failFrom = -1; throw new ProviderException("TronGrid HTTP 429", true); }
            List<TokenFreeze> out = new ArrayList<>();
            if (from <= 10 && to >= 10) out.add(freeze(10, TokenFreeze.Action.FREEZE));
            if (from <= 2500 && to >= 2500) out.add(freeze(2500, TokenFreeze.Action.UNFREEZE));
            return out;
        }
    }

    @Test
    void progressSurvivesARestartAndTheScanResumesAfterTheLastScannedBlock() {
        Scanner scanner = new Scanner();
        scanner.failFrom = 2000;
        List<TokenFreeze> applied = new ArrayList<>();
        FreezeBackfill.Config cfg = new FreezeBackfill.Config(0, null, 1000, 10, Duration.ZERO);
        assertThatThrownBy(() -> new FreezeBackfill("freezes:tron", scanner, store(), cfg, applied::add, d -> { }, Clock.systemUTC())
                .run(() -> false)).isInstanceOf(ProviderException.class);
        assertThat(applied).hasSize(1);

        // "restart": new store instance on the same database; stored freezes are re-applied without any scan
        List<TokenFreeze> replayed = new ArrayList<>();
        FreezeBackfill restarted = new FreezeBackfill("freezes:tron", scanner, store(), cfg, replayed::add, d -> { }, Clock.systemUTC());
        int before = scanner.starts.size();
        assertThat(restarted.replayStored()).isEqualTo(1);
        assertThat(scanner.starts).hasSize(before);
        TokenFreeze back = replayed.getFirst();
        assertThat(back.eventId()).isEqualTo(applied.getFirst().eventId());
        assertThat(back.token().symbol()).isEqualTo("USDT"); // token re-resolved from the registry
        assertThat(back.timestamp()).isEqualTo(applied.getFirst().timestamp().truncatedTo(ChronoUnit.MICROS));
        assertThat(back.blockHash()).isNull();

        FreezeBackfill.Result r = restarted.run(() -> false);
        assertThat(scanner.starts.subList(before, scanner.starts.size())).containsExactly(2000L, 3000L);
        assertThat(r.complete()).isTrue();
        assertThat(replayed).extracting(TokenFreeze::blockNumber).containsExactly(10L, 2500L);
        FreezeBackfillStore.Progress p = store().load("freezes:tron").orElseThrow();
        assertThat(p.network()).isEqualTo(Network.TRON);
        assertThat(p.fromBlock()).isZero();
        assertThat(p.lastScannedBlock()).isEqualTo(3000);
        assertThat(p.freezesFound()).isEqualTo(2);
    }

    @Test
    void savingTheSameEventsTwiceStoresThemOnce() {
        JdbcFreezeBackfillStore s = store();
        var p = new FreezeBackfillStore.Progress("freezes:tron", Network.TRON, 0, 100, 1, Instant.now());
        s.saveChunk(p, List.of(freeze(10, TokenFreeze.Action.FREEZE)));
        s.saveChunk(p, List.of(freeze(10, TokenFreeze.Action.FREEZE), freeze(11, TokenFreeze.Action.UNFREEZE)));
        assertThat(s.freezes("freezes:tron")).extracting(TokenFreeze::blockNumber).containsExactly(10L, 11L);
        assertThat(s.freezes("freezes:other")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from backfill_freeze_events", Map.of(), Long.class)).isEqualTo(2);
        assertThat(s.load("missing")).isEmpty();
    }
}
