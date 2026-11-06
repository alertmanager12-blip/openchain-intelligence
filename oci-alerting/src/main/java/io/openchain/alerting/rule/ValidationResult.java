package io.openchain.alerting.rule;

import java.util.List;

/** Outcome of {@link RuleValidator#validate}: either a rule (no errors) or the list of errors. */
public record ValidationResult(List<ValidationError> errors, Rule rule) {
    public ValidationResult { errors = List.copyOf(errors); if (!errors.isEmpty()) rule = null; }

    public boolean valid() { return errors.isEmpty(); }

    public static ValidationResult failure(List<ValidationError> errors) { return new ValidationResult(errors, null); }
}
