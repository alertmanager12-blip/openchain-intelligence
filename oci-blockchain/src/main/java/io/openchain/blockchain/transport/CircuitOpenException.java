package io.openchain.blockchain.transport;

import io.openchain.core.provider.ProviderException;

/** Fail-fast rejection by an open circuit. Retryable on ANOTHER endpoint, not retried on the same one. */
public final class CircuitOpenException extends ProviderException {
    public CircuitOpenException(String message) { super(message, true); }
}
