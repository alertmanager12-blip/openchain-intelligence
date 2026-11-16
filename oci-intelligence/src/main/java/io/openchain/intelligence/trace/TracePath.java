package io.openchain.intelligence.trace;

import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.ValueFlow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A path value could have taken from one address to another (Fund Flow Trace P0-C, G8 and T9): directed (each hop sent by
 * the address the previous hop reached), chronological (each hop not earlier than the previous one) and in one token.
 * Earliest-arrival search, ties broken by hop count then event id, so the same flows give the same path. "No path" means
 * none in the flows read, within the limits; the result says which limit was reached, if any.
 */
public final class TracePath {
    private TracePath() {}

    public enum Outcome { FOUND, NO_PATH, LIMIT_REACHED }

    public record Result(Outcome outcome, List<ValueFlow> hops, List<Trace.Stop> stops, int flowsRead) {}

    /**
     * @param tokenKey only this token ({@link Trace#tokenKey}); null = any, but one token along the whole path
     */
    public static Result find(Trace.Source source, Address from, Address to, Trace.Window window, String tokenKey, int maxHops,
                              int maxFlowsRead, int maxFlowsPerAddress) {
        if (maxHops < 1 || maxHops > Trace.Limits.DEPTH_CAP) throw new IllegalArgumentException("maxHops must be 1.." + Trace.Limits.DEPTH_CAP);
        record State(Address address, String token, Instant arrival, int hops, ValueFlow via, State prev) {}
        Comparator<State> order = Comparator.comparing((State s) -> s.arrival() == null ? Instant.MIN : s.arrival())
                .thenComparingInt(State::hops).thenComparing(s -> s.via() == null ? "" : s.via().eventId());
        PriorityQueue<State> queue = new PriorityQueue<>(order);
        queue.add(new State(from, tokenKey, null, 0, null, null));
        Map<String, Instant> best = new HashMap<>(); // address|token -> earliest arrival settled
        Map<String, Trace.Stop> stops = new java.util.TreeMap<>();
        int read = 0;
        boolean limited = false;
        while (!queue.isEmpty()) {
            State s = queue.poll();
            if (s.address().equals(to) && s.via() != null) {
                List<ValueFlow> hops = new ArrayList<>();
                for (State c = s; c.via() != null; c = c.prev()) hops.addFirst(c.via());
                return new Result(Outcome.FOUND, List.copyOf(hops), List.copyOf(stops.values()), read);
            }
            String key = s.address().value() + "|" + s.token();
            if (best.containsKey(key)) continue;
            best.put(key, s.arrival());
            if (s.hops() >= maxHops) {
                limited = true;
                stops.putIfAbsent("DEPTH|" + s.address().value(), new Trace.Stop(s.address(), s.token(), Trace.StopReason.DEPTH,
                        "maximum " + maxHops + " hops"));
                continue;
            }
            List<ValueFlow> out = source.outgoing(s.address());
            if (!s.address().equals(from) && out.size() + source.incoming(s.address()).size() > maxFlowsPerAddress) {
                limited = true;
                stops.putIfAbsent("SERVICE_LIKE|" + s.address().value(), new Trace.Stop(s.address(), null, Trace.StopReason.SERVICE_LIKE,
                        "more than " + maxFlowsPerAddress + " flows: probably a service; not expanded"));
                continue;
            }
            if (read + out.size() > maxFlowsRead) {
                limited = true;
                stops.putIfAbsent("FLOWS_READ|" + s.address().value(), new Trace.Stop(s.address(), null, Trace.StopReason.FLOWS_READ,
                        "flows-read budget " + maxFlowsRead + " reached"));
                continue;
            }
            read += out.size();
            Map<String, ValueFlow> unique = new java.util.TreeMap<>();
            for (ValueFlow f : out) unique.putIfAbsent(f.eventId(), f);
            for (ValueFlow f : unique.values()) {
                if (f.from().equals(f.to()) || !window.contains(f.timestamp())) continue;
                String tk = Trace.tokenKey(f.token());
                if (s.token() != null && !s.token().equals(tk)) continue;
                if (f.token().isUnverified()) continue;
                if (s.arrival() != null && f.timestamp().isBefore(s.arrival())) continue; // value cannot leave before it arrived
                if (best.containsKey(f.to().value() + "|" + tk)) continue;
                queue.add(new State(f.to(), tk, f.timestamp(), s.hops() + 1, f, s));
            }
        }
        return new Result(limited ? Outcome.LIMIT_REACHED : Outcome.NO_PATH, List.of(), List.copyOf(stops.values()), read);
    }
}
