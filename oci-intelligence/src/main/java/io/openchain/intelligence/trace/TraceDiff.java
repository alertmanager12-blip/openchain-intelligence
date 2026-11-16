package io.openchain.intelligence.trace;

import io.openchain.core.model.Address;
import io.openchain.intelligence.trace.Trace.Attribution;
import io.openchain.intelligence.trace.Trace.Edge;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What changed between two runs of the same trace (Fund Flow Trace P0-D, scenarios 14 and 15): after a chain
 * reorganisation a flow can disappear, or come back in another block with another log index, hence another live event id.
 * Evidence is matched on the transfer itself (network, transaction, token, sender, recipient, raw amount, rank of that
 * tuple in the transaction), not on the event id: a re-included transfer is MOVED, not removed and added.
 */
public final class TraceDiff {
    private TraceDiff() {}

    public enum Kind { ADDED, REMOVED, MOVED }

    public record Change(Kind kind, String beforeEventId, String afterEventId, String txHash, Address from, Address to,
                         String tokenKey, BigInteger rawAmount) {}

    public record AttributionChange(Address address, String tokenKey, Fraction beforeLow, Fraction beforeHigh, Fraction afterLow,
                                    Fraction afterHigh) {}

    public record Diff(List<Change> evidence, List<AttributionChange> attributions) {
        public boolean empty() { return evidence.isEmpty() && attributions.isEmpty(); }
    }

    public static Diff compare(Trace.Result before, Trace.Result after) {
        Map<String, Edge> b = keyed(before.edges()), a = keyed(after.edges());
        List<Change> changes = new ArrayList<>();
        for (var e : b.entrySet()) {
            Edge x = e.getValue(), y = a.get(e.getKey());
            if (y == null) changes.add(change(Kind.REMOVED, x, null));
            else if (!x.eventId().equals(y.eventId())) changes.add(change(Kind.MOVED, x, y));
        }
        for (var e : a.entrySet()) if (!b.containsKey(e.getKey())) changes.add(change(Kind.ADDED, null, e.getValue()));
        changes.sort(Comparator.comparing((Change c) -> c.txHash()).thenComparing(c -> c.kind().name())
                .thenComparing(c -> Objects.toString(c.beforeEventId(), "")).thenComparing(c -> Objects.toString(c.afterEventId(), "")));

        Map<String, Attribution> ba = attributions(before), aa = attributions(after);
        List<AttributionChange> attr = new ArrayList<>();
        java.util.Set<String> keys = new java.util.TreeSet<>(ba.keySet());
        keys.addAll(aa.keySet());
        for (String k : keys) {
            Attribution x = ba.get(k), y = aa.get(k);
            Fraction bl = x == null ? Fraction.ZERO : x.low(), bh = x == null ? Fraction.ZERO : x.high();
            Fraction al = y == null ? Fraction.ZERO : y.low(), ah = y == null ? Fraction.ZERO : y.high();
            if (bl.equals(al) && bh.equals(ah)) continue;
            Attribution any = x != null ? x : y;
            attr.add(new AttributionChange(any.address(), any.tokenKey(), bl, bh, al, ah));
        }
        return new Diff(List.copyOf(changes), List.copyOf(attr));
    }

    private static Change change(Kind k, Edge before, Edge after) {
        Edge e = after != null ? after : before;
        return new Change(k, before == null ? null : before.eventId(), after == null ? null : after.eventId(), e.txHash(), e.from(), e.to(),
                e.tokenKey(), e.rawAmount());
    }

    private static Map<String, Attribution> attributions(Trace.Result r) {
        Map<String, Attribution> m = new TreeMap<>();
        for (Attribution x : r.attributions()) m.put(x.address().value() + "|" + x.tokenKey(), x);
        return m;
    }

    /** Edges keyed by transfer identity; the rank orders identical tuples of one transaction by event id. */
    private static Map<String, Edge> keyed(List<Edge> edges) {
        Map<String, List<Edge>> byTuple = new TreeMap<>();
        for (Edge e : edges) {
            String tuple = e.from().network().id() + "|" + e.txHash() + "|" + e.tokenKey() + "|" + e.from().value() + "|" + e.to().value()
                    + "|" + e.rawAmount();
            byTuple.computeIfAbsent(tuple, k -> new ArrayList<>()).add(e);
        }
        Map<String, Edge> out = new LinkedHashMap<>();
        for (var en : byTuple.entrySet()) {
            List<Edge> l = new ArrayList<>(en.getValue());
            l.sort(Comparator.comparing((Edge e) -> logIndex(e.eventId())).thenComparing(Edge::eventId));
            for (int i = 0; i < l.size(); i++) out.put(en.getKey() + "|" + i, l.get(i));
        }
        return out;
    }

    private static long logIndex(String eventId) {
        int i = eventId.lastIndexOf(':');
        try { return Long.parseLong(eventId.substring(i + 1)); } catch (NumberFormatException e) { return Long.MAX_VALUE; }
    }
}
