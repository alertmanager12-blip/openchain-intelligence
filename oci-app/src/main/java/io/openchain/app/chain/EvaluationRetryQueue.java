package io.openchain.app.chain;

import io.openchain.alerting.engine.EngineResult;
import io.openchain.core.event.ChainEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rule evaluation that survives a failed transaction (code review I5). The rule engine runs inside the transaction that
 * stores alerts and deliveries; when that transaction fails (database restarted, disk full...), the database is rolled
 * back but the engine state is not. Without this, the event would then be taken for a duplicate on any later evaluation,
 * and its alert lost; so would the alerts of the signals derived from it, which the intelligence pipeline does not derive
 * twice.
 * <ul>
 *   <li>on failure the engine state is undone ({@code undo}) and the event is queued (bounded, in memory);</li>
 *   <li>queued events are retried first, in order, before any new event, and periodically; while some remain, new events
 *       are queued behind them, so evaluation order is kept;</li>
 *   <li>an event the engine finds too late on retry (beyond its allowed lateness) is dropped and counted: retries do
 *       not bypass that rule (decision of 2026-11-13, option A);</li>
 *   <li>a full queue drops the new event and counts it.</li>
 * </ul>
 * Re-evaluating an event that was also replayed by its indexer is harmless: the engine deduplicates by event id and the
 * alert service recognises a replayed trigger. Not thread-safe: callers serialise (ChainIngestionService).
 */
final class EvaluationRetryQueue {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRetryQueue.class);

    public static final int DEFAULT_CAPACITY = 10_000;

    private final Function<ChainEvent, EngineResult.Disposition> attempt;
    private final Predicate<String> undo;
    private final int capacity;
    private final Deque<ChainEvent> pending = new ArrayDeque<>();
    private final AtomicLong failed = new AtomicLong(), retried = new AtomicLong(), droppedLate = new AtomicLong(),
            droppedFull = new AtomicLong();
    private boolean failing;
    private volatile int pendingCount; // read by metrics without the ingestion lock

    /**
     * @param attempt evaluates one event in its own transaction and returns the engine disposition; throws when the
     *                transaction failed
     * @param undo    undoes the engine state of the last evaluated event (RuleEngine.undoLast)
     */
    EvaluationRetryQueue(Function<ChainEvent, EngineResult.Disposition> attempt, Predicate<String> undo, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.attempt = attempt;
        this.undo = undo;
        this.capacity = capacity;
    }

    void evaluate(ChainEvent e) {
        retryPending();
        if (!pending.isEmpty() || !tryOnce(e, false)) enqueue(e);
    }

    /** Retries queued events in order until one fails again; returns how many succeeded. */
    int retryPending() {
        int n = 0;
        while (!pending.isEmpty()) {
            if (!tryOnce(pending.peekFirst(), true)) break;
            pending.pollFirst();
            pendingCount = pending.size();
            retried.incrementAndGet();
            n++;
        }
        if (n > 0 && pending.isEmpty()) log.info("rule evaluation recovered: {} queued event(s) evaluated", n);
        return n;
    }

    private boolean tryOnce(ChainEvent e, boolean retry) {
        try {
            EngineResult.Disposition d = attempt.apply(e);
            if (retry && d == EngineResult.Disposition.LATE_DROPPED) {
                droppedLate.incrementAndGet();
                log.warn("rule evaluation of {} dropped on retry: older than the allowed lateness of the rule engine", e.eventId());
            }
            failing = false;
            return true;
        } catch (RuntimeException ex) {
            boolean undone = undo.test(e.eventId());
            failed.incrementAndGet();
            if (!failing) log.error("rule evaluation of {} failed ({}), engine state {}; queued for retry: {}", e.eventId(),
                    ex.getClass().getSimpleName(), undone ? "undone" : "unchanged", ex.getMessage());
            failing = true;
            return false;
        }
    }

    private void enqueue(ChainEvent e) {
        if (pending.size() >= capacity) {
            droppedFull.incrementAndGet();
            log.error("rule evaluation of {} dropped: retry queue full ({} event(s))", e.eventId(), capacity);
            return;
        }
        pending.addLast(e);
        pendingCount = pending.size();
    }

    int pending() { return pendingCount; }
    long failed() { return failed.get(); }
    long retried() { return retried.get(); }
    long droppedLate() { return droppedLate.get(); }
    long droppedFull() { return droppedFull.get(); }
}
