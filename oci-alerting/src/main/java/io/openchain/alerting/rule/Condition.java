package io.openchain.alerting.rule;

import java.util.List;

/** Declarative condition tree. Leaves compare one catalog field with a typed literal; groups combine children. */
public sealed interface Condition {

    enum Logic { AND, OR, NOT }

    /** NOT groups have exactly one child. An empty AND group is "always true". */
    record Group(Logic logic, List<Condition> children) implements Condition {
        public Group { children = List.copyOf(children); }
        public static Group matchAll() { return new Group(Logic.AND, List.of()); }
    }

    /**
     * @param value typed literal: String, BigDecimal, Boolean, Instant, or an immutable List of those
     *              (IN / NOT_IN / BETWEEN). For EXISTS it is a Boolean (true = must be present).
     */
    record Leaf(String field, Operator operator, Object value, boolean ignoreCase) implements Condition {}
}
