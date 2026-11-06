package io.openchain.app.persistence;

/** A row was changed concurrently since it was read (row_version mismatch). Mapped to HTTP 409. */
public final class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String message) { super(message); }
}
