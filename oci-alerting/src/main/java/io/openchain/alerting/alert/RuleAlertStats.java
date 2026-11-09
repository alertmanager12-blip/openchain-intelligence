package io.openchain.alerting.alert;

/**
 * Alert volume of one rule: how noisy it is in practice. {@code open} = not RESOLVED nor FALSE_POSITIVE;
 * {@code recent} = created since the requested instant.
 */
public record RuleAlertStats(String ruleId, long total, long open, long falsePositive, long resolved, long recent) {

    /** Share of closed alerts that an analyst classified as false positives; null when none is closed yet. */
    public Double falsePositiveRate() {
        long closed = falsePositive + resolved;
        return closed == 0 ? null : (double) falsePositive / closed;
    }
}
