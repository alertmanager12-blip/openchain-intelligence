package io.openchain.app.journal;

import io.openchain.core.detection.Signal;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.flow.ValueFlow;

/**
 * Durable copy of the chain-derived intelligence (ADR-0024 step 2). Calls come from the processing hot path and must
 * not block: implementations queue and write behind. Nothing is journaled while {@link #restoring()} is set on the
 * current thread (start-up reload writes into the in-memory stores without re-writing what it just read).
 */
public interface IntelligenceJournal {
    void flow(ValueFlow f);
    void signal(Signal s);
    void score(RiskScore s);

    ThreadLocal<Boolean> RESTORING = ThreadLocal.withInitial(() -> false);

    static boolean restoring() { return RESTORING.get(); }
}
