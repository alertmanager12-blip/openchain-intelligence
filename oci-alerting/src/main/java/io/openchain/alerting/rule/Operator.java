package io.openchain.alerting.rule;

import java.util.EnumSet;
import java.util.Set;

/** Closed set of comparison operators. There is deliberately no "expression" or "script" operator. */
public enum Operator {
    EQUALS, NOT_EQUALS,
    GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL,
    IN, NOT_IN,
    CONTAINS, STARTS_WITH,
    EXISTS,
    BETWEEN,
    MATCHES;

    private static final Set<Operator> ORDERED = EnumSet.of(GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN,
            LESS_THAN_OR_EQUAL, BETWEEN);

    /** Operators accepted for a field of the given type. */
    public static Set<Operator> allowedFor(FieldType type) {
        return switch (type) {
            case STRING -> EnumSet.of(EQUALS, NOT_EQUALS, IN, NOT_IN, CONTAINS, STARTS_WITH, MATCHES, EXISTS);
            case DECIMAL, INTEGER -> EnumSet.of(EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN,
                    LESS_THAN_OR_EQUAL, IN, NOT_IN, BETWEEN, EXISTS);
            case TIMESTAMP -> EnumSet.of(EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN,
                    LESS_THAN_OR_EQUAL, BETWEEN, EXISTS);
            case BOOLEAN -> EnumSet.of(EQUALS, NOT_EQUALS, EXISTS);
        };
    }

    public boolean ordered() { return ORDERED.contains(this); }
}
