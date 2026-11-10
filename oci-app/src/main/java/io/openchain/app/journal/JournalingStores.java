package io.openchain.app.journal;

import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.detection.SignalRepository;
import io.openchain.intelligence.flow.FlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.risk.RiskScoreRepository;
import java.util.List;
import java.util.Optional;

/** Decorators that copy every new flow, signal and score to the {@link IntelligenceJournal}; reads go to the delegate. */
public final class JournalingStores {
    private JournalingStores() {}

    public record Flows(FlowStore delegate, IntelligenceJournal journal) implements FlowStore {
        @Override public boolean add(ValueFlow f) {
            boolean added = delegate.add(f);
            if (added && !IntelligenceJournal.restoring()) journal.flow(f);
            return added;
        }
        @Override public boolean contains(String eventId) { return delegate.contains(eventId); }
        @Override public List<ValueFlow> outgoing(Address a) { return delegate.outgoing(a); }
        @Override public List<ValueFlow> incoming(Address a) { return delegate.incoming(a); }
        @Override public List<ValueFlow> involving(Address a) { return delegate.involving(a); }
        @Override public int evictBefore(java.util.function.Function<io.openchain.core.model.Network, java.time.Instant> cutoff,
                                         java.util.function.Predicate<Address> keep) { return delegate.evictBefore(cutoff, keep); }
        @Override public int size() { return delegate.size(); }
    }

    public record Signals(SignalRepository delegate, IntelligenceJournal journal) implements SignalRepository {
        @Override public boolean saveIfAbsent(Signal s) {
            boolean saved = delegate.saveIfAbsent(s);
            if (saved && !IntelligenceJournal.restoring()) journal.signal(s);
            return saved;
        }
        @Override public Optional<Signal> findById(String id) { return delegate.findById(id); }
        @Override public List<Signal> findByAddress(Address a) { return delegate.findByAddress(a); }
        @Override public int size() { return delegate.size(); }
        @Override public int evictBefore(java.time.Instant cutoff, java.util.function.Predicate<Signal> keep) { return delegate.evictBefore(cutoff, keep); }
    }

    public record Scores(RiskScoreRepository delegate, IntelligenceJournal journal) implements RiskScoreRepository {
        @Override public void append(RiskScore s) {
            delegate.append(s);
            if (!IntelligenceJournal.restoring()) journal.score(s);
        }
        @Override public List<RiskScore> history(Address a) { return delegate.history(a); }
        @Override public Optional<RiskScore> latest(Address a) { return delegate.latest(a); }
        @Override public int forget(java.util.Collection<Address> addresses) { return delegate.forget(addresses); }
        @Override public int addresses() { return delegate.addresses(); }
    }
}
