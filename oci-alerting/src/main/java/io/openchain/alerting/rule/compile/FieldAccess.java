package io.openchain.alerting.rule.compile;

import io.openchain.core.event.ChainEvent;

/** Reads a catalog field from an event: envelope virtual fields first, then the flat {@code fields} map. */
public final class FieldAccess {
    private FieldAccess() {}

    public static Object get(ChainEvent e, String field) {
        return switch (field) {
            case "network" -> e.network() == null ? null : e.network().id();
            case "event.type" -> e.type() == null ? null : e.type().name();
            case "event.block_number" -> e.blockNumber();
            case "event.time" -> e.eventTime();
            default -> e.fields().get(field);
        };
    }
}
