package io.openchain.intelligence.flow;

import io.openchain.core.model.Address;
import java.util.List;

/** Storage of value flows indexed by address. Implementations must be idempotent on {@link ValueFlow#eventId()}. */
public interface FlowStore {

    /** @return true if stored, false if a flow with the same eventId already exists (duplicate ignored). */
    boolean add(ValueFlow flow);

    boolean contains(String eventId);

    /** Flows sent by the address, ordered by timestamp ascending. */
    List<ValueFlow> outgoing(Address address);

    /** Flows received by the address, ordered by timestamp ascending. */
    List<ValueFlow> incoming(Address address);

    /**
     * Removes the flows older than the cutoff of their network ({@code null}: none removed on that network) that involve no
     * kept address; returns how many were removed. Bounds the in-memory working set (default: nothing removed).
     */
    default int evictBefore(java.util.function.Function<io.openchain.core.model.Network, java.time.Instant> cutoff,
                            java.util.function.Predicate<Address> keep) { return 0; }

    /** Flows held, or -1 when unknown. */
    default int size() { return -1; }

    /** All flows involving the address (both directions), ordered by timestamp ascending. */
    default List<ValueFlow> involving(Address address) {
        var all = new java.util.ArrayList<ValueFlow>(outgoing(address));
        for (ValueFlow f : incoming(address)) if (!f.from().equals(f.to())) all.add(f);
        all.sort(FlowOrder.CHRONOLOGICAL);
        return all;
    }
}
