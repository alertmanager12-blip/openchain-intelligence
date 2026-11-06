package io.openchain.alerting.rule;

/**
 * Window aggregation functions.
 * <ul>
 *   <li>COUNT: number of matching events in the window (no field)</li>
 *   <li>SUM / MAX: over a numeric field (exact BigDecimal arithmetic)</li>
 *   <li>DISTINCT_COUNT: number of distinct values of a field (e.g. recipients)</li>
 *   <li>RATIO_OF_BALANCE: SUM(field) divided by the reference balance ({@code wallet.balance}, i.e. the balance
 *       before the transfer) of the earliest event in the window. Undefined (never triggers) for a zero balance.</li>
 * </ul>
 */
public enum AggregationFunction {
    COUNT, SUM, DISTINCT_COUNT, MAX, RATIO_OF_BALANCE;

    public boolean needsField() { return this != COUNT; }
    public boolean needsNumericField() { return this == SUM || this == MAX || this == RATIO_OF_BALANCE; }
}
