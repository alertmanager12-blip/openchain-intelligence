package io.openchain.intelligence.flow;

import java.util.Comparator;

public final class FlowOrder {
    private FlowOrder() {}
    /** Deterministic chronological order (timestamp, then eventId). */
    public static final Comparator<ValueFlow> CHRONOLOGICAL =
            Comparator.comparing(ValueFlow::timestamp).thenComparing(ValueFlow::eventId);
}
