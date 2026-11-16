package io.openchain.intelligence.detection;

import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.FlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import java.util.List;

/**
 * Read-only view of a {@link FlowStore} without the flows of unverified tokens, given to detectors that do not opt in
 * to them ({@link Detector#readsUnverifiedTokenFlows()}). Writes are rejected: detectors are side-effect free.
 */
final class VerifiedFlows implements FlowStore {
    private final FlowStore delegate;

    VerifiedFlows(FlowStore delegate) { this.delegate = delegate; }

    private static List<ValueFlow> verified(List<ValueFlow> flows) {
        for (ValueFlow f : flows) {
            if (f.token().isUnverified()) return flows.stream().filter(g -> !g.token().isUnverified()).toList();
        }
        return flows; // common case: no copy
    }

    @Override public List<ValueFlow> outgoing(Address address) { return verified(delegate.outgoing(address)); }
    @Override public List<ValueFlow> incoming(Address address) { return verified(delegate.incoming(address)); }
    @Override public List<ValueFlow> involving(Address address) { return verified(delegate.involving(address)); }
    @Override public boolean contains(String eventId) { return delegate.contains(eventId); }
    @Override public int size() { return delegate.size(); }
    @Override public boolean add(ValueFlow flow) { throw new UnsupportedOperationException("read-only view for detectors"); }
}
