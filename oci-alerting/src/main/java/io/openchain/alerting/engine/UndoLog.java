package io.openchain.alerting.engine;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Compensations of the state changes made by one {@link RuleEngine#process} call, applied newest first by
 * {@link RuleEngine#undoLast}: when the transaction that stores the outcome of an event fails, the engine state goes back
 * to exactly what it was before the event (code review I5). Engine-confined, not thread-safe.
 */
final class UndoLog {
    private final Deque<Runnable> ops = new ArrayDeque<>();

    void push(Runnable compensation) { ops.push(compensation); }

    void run() { while (!ops.isEmpty()) ops.pop().run(); }

    int size() { return ops.size(); }
}
