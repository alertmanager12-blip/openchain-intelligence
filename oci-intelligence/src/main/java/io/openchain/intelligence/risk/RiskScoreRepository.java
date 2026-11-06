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
}
