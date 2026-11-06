package io.openchain.blockchain.backfill;

import io.openchain.core.provider.ProviderException;

/** The source refused a log query because the block range (or its result set) is too large: retry with a smaller one. */
public final class RangeTooLargeException extends ProviderException {
    public RangeTooLargeException(String message, Throwable cause) { super(message, false, cause); }
}
