package io.openchain.app.flowjournal;

import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.ValueFlow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Journal in memory (tests, in-memory mode). */
public final class InMemoryFlowJournal implements FlowJournal {
    private final Map<String, ValueFlow> rows = new LinkedHashMap<>();
    private final Map<Address, Instant> coverage = new HashMap<>();

    @Override public synchronized int append(List<ValueFlow> flows, Source source) {
        int n = 0;
        for (ValueFlow f : flows) if (rows.putIfAbsent(f.network().id() + "|" + f.eventId(), f) == null) n++;
        return n;
    }

    @Override public synchronized List<ValueFlow> involving(Address a, Instant from, Instant to, int limit) {
        List<ValueFlow> out = new ArrayList<>();
        for (ValueFlow f : rows.values()) {
            if (!(f.from().equals(a) || f.to().equals(a))) continue;
            if ((from != null && f.timestamp().isBefore(from)) || (to != null && f.timestamp().isAfter(to))) continue;
            out.add(f);
        }
        out.sort(Comparator.comparing(ValueFlow::timestamp).thenComparing(ValueFlow::eventId));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    @Override public synchronized Optional<Instant> coveredSince(Address a) { return Optional.ofNullable(coverage.get(a)); }

    @Override public synchronized void cover(Address a, Instant since, Instant enrolledAt) {
        coverage.merge(a, since, (old, now) -> now.isBefore(old) ? now : old);
    }
}
