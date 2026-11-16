package io.openchain.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.blockchain.indexer.Checkpoint;
import io.openchain.blockchain.indexer.IndexedBlock;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.BlockRef;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.risk.ScoringConfig;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * ADR-0024 step 3d against PostgreSQL 17: the working set written at an orderly shutdown comes back identical at the next
 * start, and only when the indexers have not moved since; any other case leaves the working set empty (never partial).
 */
@PostgresSupport.RequiresDocker
class WorkingSetSnapshotServiceTest {
    static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    static final Token USDT = new Token(Network.ETHEREUM, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6);
    static final Address LISTED = new Address(Network.ETHEREUM, "0x5a5a000000000000000000000000000000000001");

    final Clock clock = Clock.fixed(T0.plusSeconds(86_400), ZoneOffset.UTC);
    NamedParameterJdbcTemplate jdbc;
    JdbcWorkingSetSnapshotStore store;
    JdbcCheckpointStore checkpoints;

    @BeforeEach void database() {
        var ds = PostgresSupport.newDatabase().dataSource();
        PostgresSupport.migrate(ds);
        jdbc = new NamedParameterJdbcTemplate(ds);
        TransactionRunner tx = new TransactionRunner() {
            final org.springframework.transaction.support.TransactionTemplate t =
                    new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            @Override public <T> T inTransaction(java.util.function.Supplier<T> work) { return t.execute(s -> work.get()); }
        };
        store = new JdbcWorkingSetSnapshotStore(jdbc, tx, clock);
        checkpoints = new JdbcCheckpointStore(jdbc, clock);
        checkpoints.save("ethereum-indexer", checkpoint(1_000));
    }

    static Checkpoint checkpoint(long last) {
        return new Checkpoint(Network.ETHEREUM, last, List.of(new IndexedBlock(new BlockRef(Network.ETHEREUM, last, "0xh" + last, "0xh" + (last - 1), T0), List.of())));
    }

    IntelligencePipeline pipeline() {
        IntelligencePipeline p = IntelligencePipeline.inMemory(DetectionConfig.defaults(), ScoringConfig.defaults(), clock);
        p.labels().save(new AddressLabel(LISTED, LabelCategory.SANCTIONED, "listed", "test-list", 1.0, T0, EvidenceLevel.CONFIRMED_BY_SOURCE, "ref"));
        return p;
    }

    static Address w(int i) { return new Address(Network.ETHEREUM, String.format("0x%040d", i)); }

    static void traffic(IntelligencePipeline p) {
        for (int i = 0; i < 60; i++) {
            Address from = i % 6 == 0 ? LISTED : w(i % 8);
            p.onFlow(new ValueFlow("ethereum:0xt" + i + ":0", Network.ETHEREUM, "0xt" + i, T0.plusSeconds(i * 60L), from, w(50 + i % 4), USDT,
                    BigInteger.valueOf(1_500_000_000L + i)));
        }
        p.balances().markAuthoritative(Network.ETHEREUM);
        p.balances().recordSenderBalanceBefore("ethereum:0xt1:0", new java.math.BigDecimal("9000.5"));
    }

    WorkingSetSnapshotService service(IntelligencePipeline p) { return new WorkingSetSnapshotService(store, p, Runnable::run, "test-1", clock); }

    static void assertSame(IntelligencePipeline a, IntelligencePipeline b) {
        assertThat(Set.copyOf(b.flows().all())).isEqualTo(Set.copyOf(a.flows().all()));
        for (ValueFlow f : a.flows().all()) {
            assertThat(b.profiles().profile(f.from())).isEqualTo(a.profiles().profile(f.from()));
            assertThat(b.profiles().profile(f.to())).isEqualTo(a.profiles().profile(f.to()));
        }
        assertThat(b.signals().findAll()).isEqualTo(a.signals().findAll());
        assertThat(b.scoring().repository().all()).isEqualTo(a.scoring().repository().all());
        assertThat(b.balances().entries()).isEqualTo(a.balances().entries());
        assertThat(b.balances().authoritativeNetworks()).isEqualTo(a.balances().authoritativeNetworks());
    }

    static boolean empty(IntelligencePipeline p) { return p.flows().size() == 0 && p.profiles().size() == 0 && p.signals().size() == 0; }

    @Test void anOrderlyShutdownThenRestartRestoresTheSameWorkingSet() {
        IntelligencePipeline before = pipeline();
        traffic(before);
        assertThat(before.signals().size()).as("the scenario produces signals").isPositive();
        assertThat(service(before).writeAtShutdown()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from working_set_snapshots where completed_at is not null", Map.of(), Integer.class)).isEqualTo(1);

        IntelligencePipeline after = pipeline();
        WorkingSetSnapshotService s = service(after);
        assertThat(s.restoreAtStartup()).isEqualTo(WorkingSetSnapshotService.Outcome.RESTORED);
        assertSame(before, after);
        assertThat(s.lastRestored().flows()).isEqualTo(before.flows().size());

        // a second orderly shutdown replaces the generation (only one is kept)
        assertThat(service(after).writeAtShutdown()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from working_set_snapshots", Map.of(), Integer.class)).isEqualTo(1);
    }

    long lastBlock(String indexer) {
        return jdbc.queryForObject("select last_block from indexer_checkpoints where indexer_id = :id", Map.of("id", indexer), Long.class);
    }

    @Test void afterACrashTheSnapshotIsRestoredAndTheCheckpointsGoBackToItsWatermark() {
        IntelligencePipeline before = pipeline();
        traffic(before);
        checkpoints.save("tron-indexer", new Checkpoint(Network.TRON, 70_000_000L, List.of()));
        assertThat(service(before).writePeriodic()).isTrue();
        // indexing went on after the periodic snapshot, then the application crashed (no snapshot at shutdown)
        checkpoints.save("ethereum-indexer", checkpoint(1_050));
        checkpoints.save("tron-indexer", new Checkpoint(Network.TRON, 70_000_200L, List.of()));
        checkpoints.save("bsc-indexer", new Checkpoint(Network.BSC, 5L, List.of())); // added after the snapshot

        IntelligencePipeline after = pipeline();
        WorkingSetSnapshotService s = service(after);
        assertThat(s.restoreAtStartup()).isEqualTo(WorkingSetSnapshotService.Outcome.REWOUND);
        assertSame(before, after);
        assertThat(lastBlock("ethereum-indexer")).as("blocks 1001..1050 are indexed again").isEqualTo(1_000);
        assertThat(lastBlock("tron-indexer")).isEqualTo(70_000_000L);
        assertThat(checkpoints.load("ethereum-indexer").orElseThrow().window()).isEqualTo(checkpoint(1_000).window());
        assertThat(jdbc.queryForObject("select count(*) from indexer_checkpoints where indexer_id = 'bsc-indexer'", Map.of(), Integer.class))
                .as("an indexer unknown to the snapshot starts as a new one").isZero();
        // a second crash before the next snapshot rewinds to the same watermark again
        checkpoints.save("ethereum-indexer", checkpoint(1_010));
        assertThat(service(pipeline()).restoreAtStartup()).isEqualTo(WorkingSetSnapshotService.Outcome.REWOUND);
        assertThat(lastBlock("ethereum-indexer")).isEqualTo(1_000);
    }

    @Test void aPeriodicSnapshotKeepsItsWatermarkWhileIndexingGoesOnAndReplacesThePreviousOne() {
        IntelligencePipeline p = pipeline();
        traffic(p);
        WorkingSetSnapshotService s = service(p);
        assertThat(s.writePeriodic()).isTrue();
        checkpoints.save("ethereum-indexer", checkpoint(1_001));
        assertThat(s.writePeriodic()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from working_set_snapshots", Map.of(), Integer.class)).as("one generation").isEqualTo(1);
        assertThat(jdbc.queryForObject("select (checkpoints -> 'ethereum-indexer' ->> 'last_block')::bigint from working_set_snapshots",
                Map.of(), Long.class)).isEqualTo(1_001);
        assertThat(s.lastWriteBytes()).isPositive();
        assertThat(s.lastPauseMillis()).isGreaterThanOrEqualTo(0);
        // the indexers move on after the copy: the generation stays valid (no "moved" refusal as at shutdown)
        checkpoints.save("ethereum-indexer", checkpoint(1_002));
        assertThat(store.latestComplete()).isPresent();
    }

    @Test void aGenerationIsDiscardedWhenAnIndexerMovesWhileItIsWritten() {
        long g = store.create(1, "test-1");
        checkpoints.save("ethereum-indexer", checkpoint(1_001));
        assertThat(store.complete(g, 0, "{}")).isFalse();
        assertThat(store.latestComplete()).isEmpty();
    }

    @Test void anUnknownFormatOrCorruptedDataIsNeverRestoredPartially() {
        IntelligencePipeline before = pipeline();
        traffic(before);
        service(before).writeAtShutdown();
        // corrupt the middle of the stream
        jdbc.update("update working_set_snapshot_chunks set data = overlay(data placing '\\x00000000ffffffff'::bytea from 200 for 8)", Map.of());
        IntelligencePipeline after = pipeline();
        assertThat(service(after).restoreAtStartup()).isEqualTo(WorkingSetSnapshotService.Outcome.FAILED);
        assertThat(empty(after)).isTrue();

        service(before).writeAtShutdown();
        jdbc.update("update working_set_snapshots set format = 99", Map.of());
        IntelligencePipeline other = pipeline();
        assertThat(service(other).restoreAtStartup()).isEqualTo(WorkingSetSnapshotService.Outcome.SKIPPED_FORMAT);
        assertThat(empty(other)).isTrue();
    }

    @Test void noSnapshotMeansAnEmptyStart() {
        assertThat(service(pipeline()).restoreAtStartup()).isEqualTo(WorkingSetSnapshotService.Outcome.NONE);
    }
}
