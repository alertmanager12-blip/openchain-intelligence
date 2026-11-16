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
