package io.openchain.alerting.engine;

import io.openchain.alerting.rule.AggregationFunction;
import io.openchain.alerting.rule.Operator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Event-time sliding window state for one (rule, group key). Entries are kept sorted by event time, so late
 * (out-of-order) events are inserted at their place and retractions remove exactly their contribution.
 * A window ending at {@code t} covers {@code (t - W, t]}.
 */
final class SlidingWindow {

    record Entry(String eventId, Instant time, long seq, BigDecimal value, String distinct, BigDecimal balance) {}

    /** Window that satisfied the threshold. */
    record Hit(Instant start, Instant end, BigDecimal value, int count, List<String> eventIds) {}

    private final List<Entry> entries = new ArrayList<>();
    /** Ids of the entries: an event is counted at most once per window, even when processed again (code review I8). */
    private final java.util.Set<String> ids = new java.util.HashSet<>();

    boolean contains(String eventId) { return ids.contains(eventId); }

    int size() { return entries.size(); }

    boolean isEmpty() { return entries.isEmpty(); }

    /** @return true if the oldest entry had to be dropped to respect {@code maxEntries} */
    boolean add(Entry e, int maxEntries) { return add(e, maxEntries, null); }

    /** Same; {@code undo} (nullable) receives the compensation. */
    boolean add(Entry e, int maxEntries, UndoLog undo) {
        int idx = upperBound(e.time());
        entries.add(idx, e);
        ids.add(e.eventId());
        Instant priorLost = lostUpTo;
        Entry dropped = null;
        if (entries.size() > maxEntries) { dropped = entries.removeFirst(); ids.remove(dropped.eventId()); lost(dropped.time()); }
        if (undo != null) {
            Entry d = dropped;
            undo.push(() -> {
                entries.remove(e);
                ids.remove(e.eventId());
                if (d != null) { entries.addFirst(d); ids.add(d.eventId()); }
                lostUpTo = priorLost;
            });
        }
        return dropped != null;
    }

    /**
     * Event time up to which entries were discarded (expiry or size cap), null when none was: a window that starts at or
     * after it is still held in full. Used to tell "the threshold is no longer met" from "cannot be re-verified".
     */
    private Instant lostUpTo;

    Instant lostUpTo() { return lostUpTo; }

    private void lost(Instant t) { if (lostUpTo == null || t.isAfter(lostUpTo)) lostUpTo = t; }

    /** Result of {@link #recheck}: the passing window if any, else the window ending at the end of the range. */
    record Recheck(boolean passes, BigDecimal value, int count) {}

    /**
     * Is there a window ending at an entry time within {@code [from, to]} that passes the threshold? Same windows and
     * arithmetic as {@link #evaluate}; nothing is modified.
     */
    Recheck recheck(Instant from, Instant to, Duration w, AggregationFunction fn, Operator op, BigDecimal threshold) {
        Accumulator acc = Accumulator.of(fn);
        int n = entries.size();
        int i = lowerBound(from);
        int l = upperBound(from.minus(w)), r = l;
        while (i < n) {
            Instant end = entries.get(i).time();
            if (end.isAfter(to)) break;
            while (r < n && !entries.get(r).time().isAfter(end)) acc.add(entries.get(r++));
            Instant startExclusive = end.minus(w);
            while (l < r && !entries.get(l).time().isAfter(startExclusive)) acc.remove(entries.get(l++));
            BigDecimal value = acc.value(entries.get(l));
            if (value != null && passes(value, op, threshold)) return new Recheck(true, value, r - l);
            i = r;
        }
        // nothing passes: report the window ending at the end of the range, (to - W, to]
        Accumulator last = Accumulator.of(fn);
        int a = upperBound(to.minus(w)), b = upperBound(to);
        for (int k = a; k < b; k++) last.add(entries.get(k));
        return new Recheck(false, b > a ? last.value(entries.get(a)) : null, b - a);
    }

    boolean remove(String eventId) { ids.remove(eventId); return entries.removeIf(e -> e.eventId().equals(eventId)); }

    /** Drops entries with time {@code <= limit}: they can no longer belong to a window touched by an accepted event. */
    void evictUpTo(Instant limit) { evictUpTo(limit, null); }

    void evictUpTo(Instant limit, UndoLog undo) {
        int n = 0;
        while (n < entries.size() && !entries.get(n).time().isAfter(limit)) n++;
        if (n == 0) return;
        Instant priorLost = lostUpTo;
        List<Entry> removed = new ArrayList<>(entries.subList(0, n));
        lost(entries.get(n - 1).time());
        entries.subList(0, n).clear();
        for (Entry x : removed) ids.remove(x.eventId());
        if (undo != null) undo.push(() -> { entries.addAll(0, removed); for (Entry x : removed) ids.add(x.eventId()); lostUpTo = priorLost; });
    }

    Instant newest() { return entries.isEmpty() ? null : entries.getLast().time(); }

    /**
     * Evaluates every window that contains an event at {@code t0}: windows ending at each distinct entry time in
     * {@code [t0, t0 + W)}. In-order streams evaluate a single window; a late event may complete a later window.
     * Two-pointer sweep with add/remove accumulators: O(n) for the key.
     */
    Hit evaluate(Instant t0, Duration w, AggregationFunction fn, Operator op, BigDecimal threshold, int maxIds) {
        Accumulator acc = Accumulator.of(fn);
        int n = entries.size();
        Instant limit = t0.plus(w);
        int i = lowerBound(t0);
        // entries at or before t0 - W cannot belong to any window ending in [t0, t0 + W): skip them
        int l = upperBound(t0.minus(w)), r = l;
        while (i < n) {
            Instant end = entries.get(i).time();
            if (!end.isBefore(limit)) break;
            while (r < n && !entries.get(r).time().isAfter(end)) acc.add(entries.get(r++));
            Instant startExclusive = end.minus(w);
            while (l < r && !entries.get(l).time().isAfter(startExclusive)) acc.remove(entries.get(l++));
            BigDecimal value = acc.value(entries.get(l));
            if (value != null && passes(value, op, threshold)) {
                List<String> ids = new ArrayList<>();
                for (int k = Math.max(l, r - maxIds); k < r; k++) ids.add(entries.get(k).eventId());
                return new Hit(startExclusive, end, value, r - l, ids);
            }
            i = r;
        }
        return null;
    }

    private static boolean passes(BigDecimal v, Operator op, BigDecimal threshold) {
        int c = v.compareTo(threshold);
        return op == Operator.GREATER_THAN_OR_EQUAL ? c >= 0 : c > 0;
    }

    private int lowerBound(Instant t) { // first index with time >= t
        int lo = 0, hi = entries.size();
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (entries.get(mid).time().isBefore(t)) lo = mid + 1; else hi = mid; }
        return lo;
    }

    private int upperBound(Instant t) { // first index with time > t (stable insertion for equal times)
        int lo = 0, hi = entries.size();
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (!entries.get(mid).time().isAfter(t)) lo = mid + 1; else hi = mid; }
        return lo;
    }

    // ------------------------------------------------------------ accumulators (exact arithmetic)

    private interface Accumulator {
        void add(Entry e);
        void remove(Entry e);
        /** @param first earliest entry of the current window */
        BigDecimal value(Entry first);

        static Accumulator of(AggregationFunction fn) {
            return switch (fn) {
                case COUNT -> new Accumulator() {
                    long n;
                    public void add(Entry e) { n++; }
                    public void remove(Entry e) { n--; }
                    public BigDecimal value(Entry first) { return BigDecimal.valueOf(n); }
                };
                case SUM -> new Sum();
                case RATIO_OF_BALANCE -> new Sum() {
                    @Override public BigDecimal value(Entry first) {
                        BigDecimal b = first.balance();
                        if (b == null || b.signum() <= 0) return null; // undefined ratio never triggers
                        return sum.divide(b, MathContext.DECIMAL128);
                    }
                };
                case MAX -> new Accumulator() {
                    final TreeMap<BigDecimal, Integer> values = new TreeMap<>();
                    public void add(Entry e) { values.merge(e.value(), 1, Integer::sum); }
                    public void remove(Entry e) { values.computeIfPresent(e.value(), (k, c) -> c == 1 ? null : c - 1); }
                    public BigDecimal value(Entry first) { return values.isEmpty() ? null : values.lastKey(); }
                };
                case DISTINCT_COUNT -> new Accumulator() {
                    final Map<String, Integer> values = new HashMap<>();
                    public void add(Entry e) { values.merge(e.distinct(), 1, Integer::sum); }
                    public void remove(Entry e) { values.computeIfPresent(e.distinct(), (k, c) -> c == 1 ? null : c - 1); }
                    public BigDecimal value(Entry first) { return BigDecimal.valueOf(values.size()); }
                };
            };
        }
    }

    private static class Sum implements Accumulator {
        BigDecimal sum = BigDecimal.ZERO;
        public void add(Entry e) { sum = sum.add(e.value()); }
        public void remove(Entry e) { sum = sum.subtract(e.value()); }
        public BigDecimal value(Entry first) { return sum; }
    }
}
