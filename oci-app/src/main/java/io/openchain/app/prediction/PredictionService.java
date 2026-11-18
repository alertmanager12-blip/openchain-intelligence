package io.openchain.app.prediction;

import io.openchain.app.chain.IndexingCoverage;
import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.prediction.Evaluation;
import io.openchain.intelligence.prediction.Prediction;
import io.openchain.intelligence.prediction.Predictions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Predictions (ADR-0023 amendment 2). Precursor signals raise predictions as flows are indexed; an outgoing transfer of the
 * subject realises an open prediction as soon as it is indexed (no dependence on the flows kept in memory); at expiry, a
 * prediction is NOT_REALISED only when the network's indexer has passed the expiry with no gap since the issue, else
 * UNDETERMINED. Writes go through a queue drained by {@link #tick()}: the indexing path never waits on the database.
 */
public final class PredictionService {
    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);

    /** At most this many open predictions are tracked; beyond, new ones are dropped and counted. */
    public static final int MAX_OPEN = 5_000;

    private final PredictionStore store;
    private final Function<Network, List<IndexingCoverage.Gap>> gaps;
    private final Clock clock;
    private final Map<Address, List<Prediction>> open = new HashMap<>();
    private final Map<Network, Instant> indexedUntil = new EnumMap<>(Network.class);
    private final ConcurrentLinkedQueue<Prediction> toInsert = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Evaluation> toEvaluate = new ConcurrentLinkedQueue<>();
    private final Map<Prediction.Type, Double> established = new EnumMap<>(Prediction.Type.class);
    private final AtomicLong dropped = new AtomicLong();
    private int openCount;

    public PredictionService(PredictionStore store, Function<Network, List<IndexingCoverage.Gap>> gaps, Clock clock) {
        this.store = store;
        this.gaps = gaps;
        this.clock = clock;
    }

    /** Loads the open predictions (after a restart) and the established rates. */
    public synchronized void start() {
        for (Prediction p : store.open()) track(p);
        refreshRates();
        if (openCount > 0) log.info("{} open prediction(s) resumed", openCount);
    }

    /** The indexing path: new precursor signals raise predictions; an outgoing transfer may realise open ones. */
    public synchronized void observe(ValueFlow f, List<Signal> newSignals) {
        indexedUntil.merge(f.network(), f.timestamp(), (a, b) -> a.isAfter(b) ? a : b);
        List<Prediction> mine = open.get(f.from());
        if (mine != null) {
            for (Prediction p : List.copyOf(mine)) {
                if (Predictions.realisedBy(p, f)) settle(p, new Evaluation(p.id(), Evaluation.Outcome.REALISED, List.of(f.eventId()),
                        "condition observed", clock.instant()));
            }
        }
        for (Signal s : newSignals) {
            Optional<Prediction> p = Predictions.fromSignal(s, f, null);
            if (p.isEmpty()) continue;
            Prediction q = p.get();
            Double rate = established.get(q.type());
            if (rate != null) q = new Prediction(q.id(), q.type(), q.subject(), rate, q.signalIds(), Predictions.fromSignal(s, f, rate).orElseThrow().limits(),
                    q.issuedAt(), q.expiresAt(), q.minOutgoing(), q.excludedEvent(), q.model());
            if (openCount >= MAX_OPEN) { dropped.incrementAndGet(); continue; }
            if (isTracked(q.id())) continue;
            track(q);
            toInsert.add(q);
        }
    }

    /** Settles expired predictions and writes queued predictions and evaluations; run every minute. */
    public void tick() {
        List<Prediction> due = new ArrayList<>();
        Map<Network, Instant> until;
        synchronized (this) {
            until = new EnumMap<>(indexedUntil);
            Instant now = clock.instant();
            for (List<Prediction> l : open.values()) for (Prediction p : l) if (!now.isBefore(p.expiresAt())) due.add(p);
        }
        for (Prediction p : due) {
            Network n = p.subject().network();
            boolean gap = gaps.apply(n).stream().anyMatch(g -> !g.detectedAt().isBefore(p.issuedAt()));
            Predictions.evaluate(p, List.of(), until.get(n), gap, clock.instant()).ifPresent(e -> {
                synchronized (this) { settle(p, e); }
            });
        }
        Prediction p;
        while ((p = toInsert.poll()) != null) store.insert(p);
        Evaluation e;
        while ((e = toEvaluate.poll()) != null) {
            try {
                store.evaluate(e);
            } catch (RuntimeException ex) {
                log.warn("evaluation of {} not stored: {}", e.predictionId(), ex.toString());
            }
        }
        synchronized (this) { refreshRates(); }
    }

    public synchronized int openCount() { return openCount; }

    public long dropped() { return dropped.get(); }

    public List<Predictions.Rate> rates() {
        List<Predictions.Rate> out = new ArrayList<>();
        for (Prediction.Type t : Prediction.Type.values()) out.add(Predictions.rate(t, store.counts(t)));
        return out;
    }

    public List<PredictionStore.Entry> list(String outcome, int limit) { return store.list(outcome, limit); }

    private void refreshRates() {
        for (Prediction.Type t : Prediction.Type.values()) {
            Predictions.Rate r = Predictions.rate(t, store.counts(t));
            if (r.established()) established.put(t, r.rate()); else established.remove(t);
        }
    }

    private boolean isTracked(String id) {
        return open.values().stream().anyMatch(l -> l.stream().anyMatch(p -> p.id().equals(id)));
    }

    private void track(Prediction p) {
        open.computeIfAbsent(p.subject(), k -> new ArrayList<>()).add(p);
        openCount++;
    }

    private void settle(Prediction p, Evaluation e) {
        List<Prediction> l = open.get(p.subject());
        if (l == null || !l.remove(p)) return;
        if (l.isEmpty()) open.remove(p.subject());
        openCount--;
        toEvaluate.add(e);
    }
}
