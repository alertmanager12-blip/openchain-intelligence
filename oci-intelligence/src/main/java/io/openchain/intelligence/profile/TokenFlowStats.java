package io.openchain.intelligence.profile;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact per-token flow aggregates for one wallet (raw integer units). */
public record TokenFlowStats(String tokenKey, String symbol, int decimals,
                             BigInteger inflowRaw, BigInteger outflowRaw, long inCount, long outCount) {
    public BigDecimal inflow() { return new BigDecimal(inflowRaw, decimals); }
    public BigDecimal outflow() { return new BigDecimal(outflowRaw, decimals); }
    /**
     * Balance estimate from observed flows only (inflow - outflow). It is NOT the on-chain balance:
     * history before indexing start, fees and mint/burn are not visible. May be negative when history is partial.
     */
    public BigDecimal balanceEstimate() { return new BigDecimal(inflowRaw.subtract(outflowRaw), decimals); }
}
