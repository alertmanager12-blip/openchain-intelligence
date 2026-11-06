package io.openchain.alerting.rule.compile;

import io.openchain.core.event.ChainEvent;
import java.util.List;

/** Immutable, pre-compiled predicate tree node. Evaluation never allocates on the hot path except for regex. */
public interface CompiledCondition {

    boolean test(ChainEvent event, EvalStats stats);

    /** Appends the outcome of every leaf (for explainable evidence); evaluates all leaves without short-circuit. */
    void explain(ChainEvent event, List<LeafOutcome> out);

    /** Counters for evaluation anomalies (never thrown to the caller). */
    interface EvalStats {
        void regexBudgetExceeded();
        void typeMismatch();
        EvalStats NOOP = new EvalStats() {
            @Override public void regexBudgetExceeded() {}
            @Override public void typeMismatch() {}
        };
    }

    /** Human readable result of one leaf, e.g. {@code transaction.amount GREATER_THAN 100000 (actual 250000) -> true}. */
    record LeafOutcome(String field, String operator, String expected, String actual, boolean matched) {
        @Override public String toString() {
            return field + " " + operator + " " + expected + " (actual " + actual + ") -> " + matched;
        }
    }
}
