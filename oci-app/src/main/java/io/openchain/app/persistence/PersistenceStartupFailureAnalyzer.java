package io.openchain.app.persistence;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link PersistenceStartupException} into the readable "APPLICATION FAILED TO START" report. */
public class PersistenceStartupFailureAnalyzer extends AbstractFailureAnalyzer<PersistenceStartupException> {

    @Override
    protected FailureAnalysis analyze(Throwable root, PersistenceStartupException cause) {
        return new FailureAnalysis(cause.getMessage(), cause.action(), cause);
    }
}
