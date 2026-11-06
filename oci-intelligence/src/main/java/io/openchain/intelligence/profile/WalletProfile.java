package io.openchain.intelligence.profile;

import io.openchain.core.model.Address;
import io.openchain.core.risk.RiskScore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a wallet's observed behaviour. Built from indexed activity only: it describes,
 * it does not judge.
 *
 * @param transactionCount  distinct transactions (hashes) the wallet took part in
 * @param txPerDay          transactionCount / max(1 day, active span)
 * @param maxDormancyGap    longest gap between two consecutive activities (dormancy)
 * @param contractInteractions transactions sent by the wallet that called a contract
 */
public record WalletProfile(
        Address address,
        Map<String, TokenFlowStats> tokens,
        long transactionCount,
        long inboundTransfers,
        long outboundTransfers,
        Instant firstActivity,
        Instant lastActivity,
        double txPerDay,
        Duration maxDormancyGap,
        List<CounterpartyStats> topCounterparties,
        long contractInteractions,
        int distinctContracts,
        List<RiskScore> riskScoreHistory,
        Instant computedAt) {

    public WalletProfile {
        tokens = Map.copyOf(tokens);
        topCounterparties = List.copyOf(topCounterparties);
        riskScoreHistory = List.copyOf(riskScoreHistory);
    }

    public boolean hasActivity() { return transactionCount > 0 || inboundTransfers > 0 || outboundTransfers > 0; }

    public static WalletProfile empty(Address a, Instant now) {
        return new WalletProfile(a, Map.of(), 0, 0, 0, null, null, 0, Duration.ZERO, List.of(), 0, 0, List.of(), now);
    }
}
