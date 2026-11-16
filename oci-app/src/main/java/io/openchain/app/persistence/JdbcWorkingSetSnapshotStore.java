package io.openchain.app.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * PostgreSQL storage of working-set snapshots (ADR-0024 step 3d, V11). A snapshot is a stream of bytes cut into chunks;
 * a generation becomes visible only when {@link #complete} sets its completion time, in the same transaction that removes
 * every other generation. The indexer checkpoints are copied when a generation is created; {@link #complete} refuses to
 * complete it if they changed meanwhile (an indexer moved while the state was being written), and {@link #latestRestorable}
 * returns a generation only while they are still equal (the indexers have not moved since the snapshot).
 */
public final class JdbcWorkingSetSnapshotStore {

    /** Chunk size of a snapshot stream (bytes). */
    public static final int CHUNK = 1 << 20;

    /** Current indexer checkpoints, as one comparable JSON value (null when there is none). */
    static final String CHECKPOINTS = "(select coalesce(jsonb_object_agg(indexer_id, jsonb_build_object('network', network, "
            + "'last_block', last_block, 'window_refs', window_refs)), '{}'::jsonb) from indexer_checkpoints)";

    public record Generation(long generation, int format, String appVersion, Instant createdAt) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner tx;
    private final Clock clock;

    public JdbcWorkingSetSnapshotStore(NamedParameterJdbcTemplate jdbc, TransactionRunner tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** The indexer checkpoints as they are now, as JSON (the watermark of a periodic snapshot taken at a block boundary). */
    public String currentCheckpoints() { return jdbc.queryForObject("select " + CHECKPOINTS + "::text", Map.of(), String.class); }

    /**
     * Starts a new, not yet visible generation whose watermark is {@code checkpointsJson} (checkpoints read while no block
     * was being indexed); the indexers may move on while it is written: complete it with {@link #completePeriodic}.
     */
    public long create(int format, String appVersion, String checkpointsJson) {
        Long g = jdbc.queryForObject("select coalesce(max(generation), 0) + 1 from working_set_snapshots", Map.of(), Long.class);
        jdbc.update("insert into working_set_snapshots (generation, format, app_version, created_at, checkpoints) "
                + "values (:g, :f, :v, :at, cast(:cp as jsonb))",
                new MapSqlParameterSource().addValue("g", g).addValue("f", format).addValue("v", appVersion)
                        .addValue("at", Sql.ts(clock.instant())).addValue("cp", checkpointsJson));
        return g;
    }

    /** Makes a periodic generation the only visible one (its watermark was fixed at {@link #create(int, String, String)}). */
    public void completePeriodic(long generation, long sizeBytes, String countsJson) {
        tx.inTransaction(() -> {
            int ok = jdbc.update("update working_set_snapshots set completed_at = :at, size_bytes = :size, counts = cast(:counts as jsonb) "
                    + "where generation = :g", new MapSqlParameterSource().addValue("at", Sql.ts(clock.instant())).addValue("size", sizeBytes)
                    .addValue("counts", countsJson).addValue("g", generation));
            if (ok != 1) throw new IllegalStateException("snapshot generation " + generation + " vanished while it was written");
            jdbc.update("delete from working_set_snapshots where generation <> :g", Map.of("g", generation));
            return null;
        });
    }

    /** Last block of one indexer: now, and in the watermark of a generation (null = no checkpoint there). */
    public record Rewind(String indexerId, Long current, Long watermark) {}

    /** Indexers whose checkpoint differs from a generation's watermark (what {@link #rewindCheckpoints} changes). */
    public java.util.List<Rewind> rewindPlan(long generation) {
        return jdbc.query("select coalesce(c.indexer_id, w.key), c.last_block, (w.value ->> 'last_block')::bigint "
                + "from indexer_checkpoints c full join (select key, value from jsonb_each("
                + "(select checkpoints from working_set_snapshots where generation = :g))) w on w.key = c.indexer_id "
                + "where c.last_block is distinct from (w.value ->> 'last_block')::bigint "
                + "or c.window_refs is distinct from w.value -> 'window_refs' order by 1", Map.of("g", generation),
                (rs, i) -> new Rewind(rs.getString(1), (Long) rs.getObject(2), (Long) rs.getObject(3)));
    }

    /**
     * Puts the indexer checkpoints back to a generation's watermark, in one transaction: the indexers then index again what
     * was indexed after the snapshot (a crash). An indexer without checkpoint in the watermark (added since) loses its
     * checkpoint and starts as a new one.
     */
    public void rewindCheckpoints(long generation) {
        tx.inTransaction(() -> {
            Map<String, Object> g = Map.of("g", generation, "at", Sql.ts(clock.instant()));
            jdbc.update("delete from indexer_checkpoints where indexer_id not in (select key from jsonb_each("
                    + "(select checkpoints from working_set_snapshots where generation = :g)))", g);
            jdbc.update("insert into indexer_checkpoints (indexer_id, network, last_block, window_refs, updated_at) "
                    + "select key, value ->> 'network', (value ->> 'last_block')::bigint, value -> 'window_refs', :at "
                    + "from jsonb_each((select checkpoints from working_set_snapshots where generation = :g)) "
                    + "on conflict (indexer_id) do update set network = excluded.network, last_block = excluded.last_block, "
                    + "window_refs = excluded.window_refs, updated_at = excluded.updated_at", g);
            return null;
        });
    }

    /** Starts a new, not yet visible generation, copying the indexer checkpoints as they are now. */
    public long create(int format, String appVersion) {
        Long g = jdbc.queryForObject("select coalesce(max(generation), 0) + 1 from working_set_snapshots", Map.of(), Long.class);
        jdbc.update("insert into working_set_snapshots (generation, format, app_version, created_at, checkpoints) "
                + "values (:g, :f, :v, :at, " + CHECKPOINTS + ")",
                new MapSqlParameterSource().addValue("g", g).addValue("f", format).addValue("v", appVersion).addValue("at", Sql.ts(clock.instant())));
        return g;
    }

    /** Stream writing the chunks of a generation; close it before {@link #complete}. */
    public OutputStream chunks(long generation) {
        return new OutputStream() {
            private final byte[] buf = new byte[CHUNK];
            private int n, seq;
            private long total;

            @Override public void write(int b) throws IOException {
                if (n == buf.length) flushChunk();
                buf[n++] = (byte) b;
            }

            @Override public void write(byte[] b, int off, int len) throws IOException {
                while (len > 0) {
                    if (n == buf.length) flushChunk();
                    int k = Math.min(len, buf.length - n);
                    System.arraycopy(b, off, buf, n, k);
                    n += k; off += k; len -= k;
                }
            }

            private void flushChunk() {
                if (n == 0) return;
                byte[] data = java.util.Arrays.copyOf(buf, n);
                jdbc.update("insert into working_set_snapshot_chunks (generation, seq, data) values (:g, :s, :d)",
                        new MapSqlParameterSource().addValue("g", generation).addValue("s", seq++).addValue("d", data));
                total += n;
                n = 0;
            }

            @Override public void close() { flushChunk(); }

            @Override public String toString() { return total + " bytes"; }
        };
    }

    /**
     * Makes a generation the only visible one, with its size and counts, and removes every other generation. Returns false
     * (and removes the generation) when the indexer checkpoints changed since {@link #create}: the state written may then
     * lag behind them.
     */
    public boolean complete(long generation, long sizeBytes, String countsJson) {
        return tx.inTransaction(() -> {
            int ok = jdbc.update("update working_set_snapshots set completed_at = :at, size_bytes = :size, counts = cast(:counts as jsonb) "
                    + "where generation = :g and checkpoints = " + CHECKPOINTS,
                    new MapSqlParameterSource().addValue("at", Sql.ts(clock.instant())).addValue("size", sizeBytes)
                            .addValue("counts", countsJson).addValue("g", generation));
            if (ok == 0) {
                jdbc.update("delete from working_set_snapshots where generation = :g", Map.of("g", generation));
                return false;
            }
            jdbc.update("delete from working_set_snapshots where generation <> :g", Map.of("g", generation));
            return true;
        });
    }

    /** Removes generations never completed (a shutdown cut short). */
    public int deleteIncomplete() { return jdbc.update("delete from working_set_snapshots where completed_at is null", Map.of()); }

    public void delete(long generation) { jdbc.update("delete from working_set_snapshots where generation = :g", Map.of("g", generation)); }

    /** The completed generation, if any, whatever the checkpoints. */
    public Optional<Generation> latestComplete() {
        return jdbc.query("select generation, format, app_version, created_at from working_set_snapshots where completed_at is not null "
                + "order by generation desc limit 1", Map.of(), (rs, i) -> new Generation(rs.getLong(1), rs.getInt(2), rs.getString(3),
                rs.getTimestamp(4).toInstant())).stream().findFirst();
    }

    /** True when the indexer checkpoints are still those copied with the generation (nothing indexed since). */
    public boolean checkpointsUnchanged(long generation) {
        Boolean same = jdbc.queryForObject("select checkpoints = " + CHECKPOINTS + " from working_set_snapshots where generation = :g",
                Map.of("g", generation), Boolean.class);
        return Boolean.TRUE.equals(same);
    }

    /** Stream reading the chunks of a generation in order, one chunk in memory at a time. */
    public InputStream open(long generation) {
        Integer count = jdbc.queryForObject("select count(*) from working_set_snapshot_chunks where generation = :g", Map.of("g", generation), Integer.class);
        int chunks = count == null ? 0 : count;
        return new InputStream() {
            private byte[] cur = new byte[0];
            private int pos, next;

            private boolean fill() {
                while (pos >= cur.length) {
                    if (next >= chunks) return false;
                    cur = jdbc.queryForObject("select data from working_set_snapshot_chunks where generation = :g and seq = :s",
                            Map.of("g", generation, "s", next++), byte[].class);
                    pos = 0;
                }
                return true;
            }

            @Override public int read() { return fill() ? cur[pos++] & 0xff : -1; }

            @Override public int read(byte[] b, int off, int len) {
                if (len == 0) return 0;
                if (!fill()) return -1;
                int k = Math.min(len, cur.length - pos);
                System.arraycopy(cur, pos, b, off, k);
                pos += k;
                return k;
            }
        };
    }
}
