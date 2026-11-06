package io.openchain.intelligence.label;

import io.openchain.core.detection.SignalType;

/**
 * Category asserted by a label SOURCE. A label reports what a source says about an address; it is never
 * a qualification by this platform.
 */
public enum LabelCategory {
    SANCTIONED(true, SignalType.SANCTIONED_EXPOSURE),
    MIXER(true, SignalType.MIXER_EXPOSURE),
    SCAM(true, SignalType.SCAM_EXPOSURE),
    PHISHING(true, SignalType.SCAM_EXPOSURE),
    RANSOMWARE(true, SignalType.FLAGGED_EXPOSURE),
    STOLEN_FUNDS(true, SignalType.FLAGGED_EXPOSURE),
    DARKNET_MARKET(true, SignalType.FLAGGED_EXPOSURE),
    FLAGGED_COMMUNITY(true, SignalType.FLAGGED_EXPOSURE),
    /**
     * The stablecoin issuer froze (blacklisted) this address in its own token contract, observed on-chain
     * (source e.g. {@code tether-onchain}). States what the issuer did, for reasons it does not publish; not a finding
     * about the owner. Delisted when the issuer unfreezes the address.
     */
    ISSUER_FROZEN(true, SignalType.FLAGGED_EXPOSURE),
    EXCHANGE(false, null),
    BRIDGE(false, null),
    DEFI_PROTOCOL(false, null),
    OTHER(false, null);

    private final boolean risky;
    private final SignalType exposureType;

    LabelCategory(boolean risky, SignalType exposureType) { this.risky = risky; this.exposureType = exposureType; }

    /** True when interacting with such an address is a risk indicator. */
    public boolean risky() { return risky; }

    /** Signal type for DIRECT exposure to an address of this category (null when not risky). */
    public SignalType exposureType() { return exposureType; }
}
