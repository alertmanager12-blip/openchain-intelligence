package io.openchain.core.detection;

/** Only types emitted by a detector are declared: a type is added together with the detector that produces it. */
public enum SignalType {
    // A. transaction patterns
    UNUSUAL_AMOUNT, STRUCTURING, FAST_FORWARDING,
    // B. wallet patterns
    DORMANT_REACTIVATION, CIRCULAR_FLOW, FLOW_CONCENTRATION, RAPID_DRAIN,
    // C. risk exposure
    SANCTIONED_EXPOSURE, FLAGGED_EXPOSURE, INDIRECT_EXPOSURE, MIXER_EXPOSURE, SCAM_EXPOSURE,
    /** Only unsolicited dust or unverified-token transfers link the wallet to a listed address (an anomaly, not exposure). */
    DUSTING,
    // D. smart contract
    OWNERSHIP_CHANGE, UNUSUAL_APPROVAL
}
