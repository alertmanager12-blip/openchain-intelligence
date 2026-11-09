package io.openchain.app.journal;

import io.openchain.app.persistence.Sql;
import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Write-behind journal in PostgreSQL ({@code value_flows}, {@code signals}, {@code risk_scores}, V9). The hot path only
 * enqueues; a virtual thread writes batches. When the queue is full the record is dropped and counted
 * ({@link #dropped()}, metric {@code oci_journal_dropped_total}, warning in the log): it is then missing after the next
 * restart, never silently.
 */
public final class JdbcIntelligenceJournal implements IntelligenceJournal, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JdbcIntelligenceJournal.class);
    static final ObjectMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final int BATCH = 1_000;

    private sealed interface Item permits FlowItem, SignalItem, ScoreItem {}
    private record FlowItem(ValueFlow f) implements Item {}
    private record SignalItem(Signal s) implements Item {}
    private record ScoreItem(RiskScore s) implements Item {}

    private final NamedParameterJdbcTemplate jdbc;
    private final BlockingQueue<Item> queue;
    private final AtomicLong enqueued = new AtomicLong(), written = new AtomicLong(), dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean closed;
    private volatile long lastDropWarning;

    public JdbcIntelligenceJournal(NamedParameterJdbcTemplate jdbc, int capacity) {
        this.jdbc = jdbc;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writer = Thread.ofVirtual().name("intelligence-journal").start(this::writeLoop);
    }

    @Override public void flow(ValueFlow f) { offer(new FlowItem(f)); }
    @Override public void signal(Signal s) { offer(new SignalItem(s)); }
    @Override public void score(RiskScore s) { offer(new ScoreItem(s)); }

    public long written() { return written.get(); }
    public long dropped() { return dropped.get(); }
    public int backlog() { return queue.size(); }

    private void offer(Item i) {
        enqueued.incrementAndGet();
        if (queue.offer(i)) return;
        dropped.incrementAndGet();
        long now = System.nanoTime();
        if (now - lastDropWarning > TimeUnit.MINUTES.toNanos(1)) {
            lastDropWarning = now;
            log.warn("intelligence journal full: {} record(s) dropped so far (they will be missing after a restart)", dropped.get());
        }
    }

    private void writeLoop() {
        List<Item> batch = new ArrayList<>(BATCH);
        while (!closed || !queue.isEmpty()) {
            try {
                Item first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, BATCH - 1);
                write(batch);
                written.addAndGet(batch.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                dropped.addAndGet(batch.size());
                log.warn("intelligence journal: batch of {} not written: {}", batch.size(), e.getMessage());
            } finally {
                batch.clear();
            }
        }
    }

    private void write(List<Item> batch) {
        List<MapSqlParameterSource> flows = new ArrayList<>(), signals = new ArrayList<>(), scores = new ArrayList<>();
        for (Item i : batch) {
            switch (i) {
                case FlowItem(ValueFlow f) -> flows.add(new MapSqlParameterSource().addValue("id", f.eventId())
                        .addValue("network", f.network().id()).addValue("tx", f.txHash()).addValue("ts", Sql.ts(f.timestamp()))
                        .addValue("from", f.from().value()).addValue("to", f.to().value()).addValue("contract", f.token().contract())
                        .addValue("symbol", f.token().symbol()).addValue("decimals", f.token().decimals())
                        .addValue("raw", new BigDecimal(f.rawAmount())));
                case SignalItem(Signal s) -> signals.add(new MapSqlParameterSource().addValue("id", s.id())
                        .addValue("network", s.address().network().id()).addValue("address", s.address().value())
                        .addValue("at", Sql.ts(s.detectedAt())).addValue("payload", JSON.writeValueAsString(s)));
                case ScoreItem(RiskScore s) -> scores.add(new MapSqlParameterSource()
                        .addValue("network", s.address().network().id()).addValue("address", s.address().value())
                        .addValue("at", Sql.ts(s.computedAt())).addValue("payload", JSON.writeValueAsString(s)));
            }
        }
        if (!flows.isEmpty()) jdbc.batchUpdate("insert into value_flows (event_id, network, tx_hash, ts, from_address, to_address, "
                + "token_contract, token_symbol, token_decimals, raw_amount) values (:id, :network, :tx, :ts, :from, :to, :contract, "
                + ":symbol, :decimals, :raw) on conflict (event_id) do nothing", flows.toArray(MapSqlParameterSource[]::new));
        if (!signals.isEmpty()) jdbc.batchUpdate("insert into signals (id, network, address, detected_at, payload) values "
                + "(:id, :network, :address, :at, cast(:payload as jsonb)) on conflict (id) do nothing", signals.toArray(MapSqlParameterSource[]::new));
        if (!scores.isEmpty()) jdbc.batchUpdate("insert into risk_scores (network, address, computed_at, payload) values "
                + "(:network, :address, :at, cast(:payload as jsonb))", scores.toArray(MapSqlParameterSource[]::new));
    }

    // ---- reload (start-up) and retention ----------------------------------------------------------------------

    /** Flows since {@code since}, oldest first, streamed to {@code sink} in chunks of {@code chunk}. */
    public long loadFlows(Instant since, int chunk, Consumer<List<ValueFlow>> sink) {
        List<ValueFlow> buf = new ArrayList<>(chunk);
        long[] n = {0};
        jdbc.query("select event_id, network, tx_hash, ts, from_address, to_address, token_contract, token_symbol, token_decimals, "
                + "raw_amount from value_flows where ts >= :since order by ts, event_id", Map.of("since", Sql.ts(since)), rs -> {
            Network net = Network.fromId(rs.getString("network"));
            buf.add(new ValueFlow(rs.getString("event_id"), net, rs.getString("tx_hash"), Sql.instant(rs, "ts"),
                    new Address(net, rs.getString("from_address")), new Address(net, rs.getString("to_address")),
                    new Token(net, rs.getString("token_contract"), rs.getString("token_symbol"), rs.getInt("token_decimals")),
                    rs.getBigDecimal("raw_amount").toBigIntegerExact()));
            n[0]++;
            if (buf.size() >= chunk) {
                sink.accept(List.copyOf(buf));
                buf.clear();
            }
        });
        if (!buf.isEmpty()) sink.accept(List.copyOf(buf));
        return n[0];
    }

    public List<Signal> loadSignals(Instant since) {
        return jdbc.query("select payload from signals where detected_at >= :since order by detected_at, id", Map.of("since", Sql.ts(since)),
                (rs, i) -> JSON.readValue(rs.getString("payload"), Signal.class));
    }

    public List<RiskScore> loadScores(Instant since) {
        return jdbc.query("select payload from risk_scores where computed_at >= :since order by computed_at, id", Map.of("since", Sql.ts(since)),
                (rs, i) -> JSON.readValue(rs.getString("payload"), RiskScore.class));
    }

    /** Deletes journal rows older than {@code before}; returns the number of rows removed. */
    public int purge(Instant before) {
        var p = Map.of("before", Sql.ts(before));
        return jdbc.update("delete from value_flows where ts < :before", p)
                + jdbc.update("delete from signals where detected_at < :before", p)
                + jdbc.update("delete from risk_scores where computed_at < :before", p);
    }

    /** Waits until every record offered so far is written or counted as dropped (tests, shutdown). */
    public void flush(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (written.get() + dropped.get() < enqueued.get() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    @Override public void close() {
        flush(5_000);
        closed = true;
        writer.interrupt();
    }
}
