package io.openchain.alerting.rule;

import java.util.List;

/** Thrown by {@link RuleLoader#load} when a rule document is invalid. Carries every structured error found. */
public class RuleValidationException extends RuntimeException {
    private final List<ValidationError> errors;

    public RuleValidationException(List<ValidationError> errors) {
        super("invalid rule: " + errors.size() + " error(s): " + errors);
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() { return errors; }
}
