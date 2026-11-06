package io.openchain.alerting.rule;

/** Runtime types of catalog fields. DECIMAL and INTEGER are both evaluated with exact BigDecimal arithmetic. */
public enum FieldType {
    STRING, DECIMAL, INTEGER, BOOLEAN, TIMESTAMP;

    public boolean numeric() { return this == DECIMAL || this == INTEGER; }
}
