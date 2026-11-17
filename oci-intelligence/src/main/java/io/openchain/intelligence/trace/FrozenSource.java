package io.openchain.intelligence.trace;

import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The evidence a trace read, frozen (ADR-0022 amendment 1: "replayable means explainable from the frozen evidence").
 * {@link #recording} wraps a live source and remembers every answer it gave; {@link #snapshot} gives the frozen
 * evidence, and a {@code FrozenSource} built from it answers exactly the same: the engine run again on it gives the same
 * result. Addresses the trace did not read answer "nothing, coverage unknown".
 */
public final class FrozenSource implements Trace.Source {

    /** What the engine read about one address. */
    public record AddressEvidence(Address address, List<ValueFlow> incoming, List<ValueFlow> outgoing, boolean complete,
                                  BigInteger openingBalance, List<AddressLabel> labels) {}

    /** Everything one run read, per address, sorted: storable and comparable. */
    public record Snapshot(List<AddressEvidence> addresses) {
        /** Every distinct flow, sorted by event id. */
        public List<ValueFlow> flows() {
            Map<String, ValueFlow> m = new TreeMap<>();
            for (AddressEvidence e : addresses) {
                e.incoming().forEach(f -> m.putIfAbsent(f.eventId(), f));
                e.outgoing().forEach(f -> m.putIfAbsent(f.eventId(), f));
            }
            return List.copyOf(m.values());
        }
    }

    private final Map<String, AddressEvidence> byAddress = new TreeMap<>();

    public FrozenSource(Snapshot s) {
        for (AddressEvidence e : s.addresses()) byAddress.put(e.address().value(), e);
    }

    private AddressEvidence of(Address a) { return byAddress.get(a.value()); }

    @Override public List<ValueFlow> outgoing(Address a) { var e = of(a); return e == null ? List.of() : e.outgoing(); }
    @Override public List<ValueFlow> incoming(Address a) { var e = of(a); return e == null ? List.of() : e.incoming(); }
    @Override public boolean complete(Address a, Trace.Window w) { var e = of(a); return e != null && e.complete(); }
    @Override public Optional<BigInteger> openingBalance(Address a, String tokenKey, Trace.Window w) {
        var e = of(a);
        return e == null ? Optional.empty() : Optional.ofNullable(e.openingBalance());
    }
    @Override public List<AddressLabel> labels(Address a) { var e = of(a); return e == null ? List.of() : e.labels(); }

    /** Wraps {@code live} and records every answer; call {@link Recorder#snapshot()} after the run. */
    public static Recorder recording(Trace.Source live) { return new Recorder(live); }

    /**
     * Records what the engine reads. Opening balances are recorded per address only when known and the same for every
     * token (the engine asks per token; an address with balances per token is recorded as unknown, which only widens the
     * replayed interval, and is said by {@link #openingBalancesSimplified()}).
     */
    public static final class Recorder implements Trace.Source {
        private final Trace.Source live;
        private final Map<String, Address> seen = new TreeMap<>();
        private final Map<String, List<ValueFlow>> in = new TreeMap<>(), out = new TreeMap<>();
        private final Map<String, Boolean> complete = new TreeMap<>();
        private final Map<String, List<AddressLabel>> labels = new TreeMap<>();
        private final Map<String, Optional<BigInteger>> opening = new TreeMap<>();
        private boolean simplified;

        Recorder(Trace.Source live) { this.live = live; }

        private void see(Address a) { seen.putIfAbsent(a.value(), a); }

        @Override public List<ValueFlow> outgoing(Address a) { see(a); return out.computeIfAbsent(a.value(), k -> List.copyOf(live.outgoing(a))); }
        @Override public List<ValueFlow> incoming(Address a) { see(a); return in.computeIfAbsent(a.value(), k -> List.copyOf(live.incoming(a))); }
        @Override public boolean complete(Address a, Trace.Window w) { see(a); return complete.computeIfAbsent(a.value(), k -> live.complete(a, w)); }
        @Override public List<AddressLabel> labels(Address a) { see(a); return labels.computeIfAbsent(a.value(), k -> List.copyOf(live.labels(a))); }
        @Override public Optional<BigInteger> openingBalance(Address a, String tokenKey, Trace.Window w) {
            see(a);
            Optional<BigInteger> v = live.openingBalance(a, tokenKey, w);
            Optional<BigInteger> before = opening.putIfAbsent(a.value(), v);
            if (before != null && !before.equals(v)) { opening.put(a.value(), Optional.empty()); simplified = true; return Optional.empty(); }
            return before != null ? before : v;
        }

        public boolean openingBalancesSimplified() { return simplified; }

        public Snapshot snapshot() {
            List<AddressEvidence> l = new ArrayList<>();
            for (var e : seen.entrySet()) {
                String k = e.getKey();
                l.add(new AddressEvidence(e.getValue(), sorted(in.get(k)), sorted(out.get(k)), complete.getOrDefault(k, false),
                        opening.getOrDefault(k, Optional.empty()).orElse(null), labels.getOrDefault(k, List.of())));
            }
            return new Snapshot(List.copyOf(l));
        }

        private static List<ValueFlow> sorted(List<ValueFlow> l) {
            if (l == null) return List.of();
            List<ValueFlow> s = new ArrayList<>(l);
            s.sort(Comparator.comparing(ValueFlow::eventId));
            return List.copyOf(s);
        }
    }
}
