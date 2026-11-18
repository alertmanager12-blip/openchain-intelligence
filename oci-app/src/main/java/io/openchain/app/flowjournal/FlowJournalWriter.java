package io.openchain.app.flowjournal;

import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.ValueFlow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Write-behind of the transfers of watched addresses to the {@link FlowJournal} (ADR-0026 amendment 1). The indexing path
 * only offers to a bounded queue; {@link #drain()} writes in batches. A full queue drops and counts: the journal's
 * coverage then cannot be trusted for the period, which the metric says.
 */
public final class FlowJournalWriter {
    private static final Logger log = LoggerFactory.getLogger(FlowJournalWriter.class);
    public static final int BATCH = 1_000;

    private final FlowJournal journal;
    private final Predicate<Address> watched;
    private final Supplier<Set<Address>> watchedSet;
    private final Clock clock;
    private final ArrayBlockingQueue<ValueFlow> queue;
    private final AtomicLong written = new AtomicLong(), dropped = new AtomicLong(), failed = new AtomicLong();
    private volatile Instant droppedSince;

    public FlowJournalWriter(FlowJournal journal, Predicate<Address> watched, Supplier<Set<Address>> watchedSet, Clock clock, int capacity) {
        this.journal = journal;
        this.watched = watched;
        this.watchedSet = watchedSet;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** The indexing path: never blocks. */
    public void observe(ValueFlow f) {
        if (!watched.test(f.from()) && !watched.test(f.to())) return;
        if (!queue.offer(f)) {
            dropped.incrementAndGet();
            if (droppedSince == null) droppedSince = f.timestamp();
        }
    }

    /** Writes queued transfers in batches; a failed batch is counted, and the journal is incomplete from its first transfer. */
    public void drain() {
        List<ValueFlow> batch = new ArrayList<>(BATCH);
        while (queue.drainTo(batch, BATCH) > 0) {
            try {
                journal.ensurePartition(batch.getFirst().timestamp());
                journal.ensurePartition(batch.getLast().timestamp());
                written.addAndGet(journal.append(batch, FlowJournal.Source.LIVE));
            } catch (RuntimeException e) {
                failed.addAndGet(batch.size());
                if (droppedSince == null) droppedSince = batch.getFirst().timestamp();
                log.warn("flow journal: {} transfer(s) not written: {}", batch.size(), e.toString());
            }
            batch.clear();
        }
    }

    /** Starts the coverage of a newly watched address now. */
    public void enrolled(Address a) {
        Instant now = clock.instant();
        journal.cover(a, now, now);
    }

    /** At startup: every watched address without coverage starts now (nothing earlier can be claimed). */
    public void coverWatched() {
        Instant now = clock.instant();
        for (Address a : watchedSet.get()) if (journal.coveredSince(a).isEmpty()) journal.cover(a, now, now);
        journal.ensurePartition(now);
        journal.ensurePartition(now.atZone(java.time.ZoneOffset.UTC).plusMonths(1).toInstant());
    }

    public long written() { return written.get(); }
    public long dropped() { return dropped.get(); }
    public long failed() { return failed.get(); }
    public int backlog() { return queue.size(); }
    /** Event time of the first transfer dropped since start, or null: the journal is incomplete from then on. */
    public Instant droppedSince() { return droppedSince; }
}
