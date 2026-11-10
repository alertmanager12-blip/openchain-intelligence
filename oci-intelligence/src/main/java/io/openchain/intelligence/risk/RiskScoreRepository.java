package io.openchain.intelligence.risk;

import io.openchain.core.model.Address;
import io.openchain.core.risk.RiskScore;
import java.util.List;
import java.util.Optional;

public interface RiskScoreRepository {
    void append(RiskScore score);

    /** Oldest first. */
    List<RiskScore> history(Address address);

    Optional<RiskScore> latest(Address address);

    /** Forgets every score of the given addresses (evicted from the working set); returns how many addresses were removed. */
    default int forget(java.util.Collection<Address> addresses) { return 0; }

    /** Addresses with at least one score held, or -1 when unknown. */
    default int addresses() { return -1; }
}
