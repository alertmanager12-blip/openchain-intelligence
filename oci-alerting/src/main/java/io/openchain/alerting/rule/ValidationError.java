package io.openchain.alerting.rule;

import java.util.Map;

/**
 * One structured validation problem.
 *
 * @param path JSON path of the offending node, e.g. {@code $.conditions.rules[2].field}
 * @param key stable message key ({@code rule.<name>}) that clients translate
 * @param params values of the key's placeholders
 * @param message the English sentence: the key's template filled with the params
 */
public record ValidationError(String path, Code code, String key, Map<String, Object> params, String message) {

    public enum Code {
        SYNTAX, UNSAFE_DOCUMENT, UNKNOWN_PROPERTY, REQUIRED, TYPE, PATTERN, UNSUPPORTED_API_VERSION, UNKNOWN_FIELD,
        INVALID_OPERATOR, INVALID_VALUE, LIMIT_EXCEEDED, INVALID_REGEX, UNSAFE_REGEX, INVALID_TEMPLATE, CONFLICT,
        INVALID_TEST
    }

    public ValidationError {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** For callers outside the validator that only have free text: key {@code rule.generic}. */
    public ValidationError(String path, Code code, String message) {
        this(path, code, RuleMessage.GENERIC.key(), Map.of("reason", message), message);
    }

    /** A keyed error whose message is the key's template filled with the params (name/value pairs). */
    public static ValidationError of(String path, Code code, RuleMessage m, Object... kv) {
        Map<String, Object> p = RuleMessage.params(kv);
        return new ValidationError(path, code, m.key(), p, m.fill(p));
    }

    @Override public String toString() { return path + " [" + code + "] " + message; }
}
