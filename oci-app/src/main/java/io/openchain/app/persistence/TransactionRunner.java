package io.openchain.app.persistence;

import java.util.function.Supplier;

/** Runs a unit of work atomically: a database transaction in JDBC mode, a plain call in memory mode. */
@FunctionalInterface
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }

    TransactionRunner DIRECT = new TransactionRunner() {
        @Override public <T> T inTransaction(Supplier<T> work) { return work.get(); }
    };
}
