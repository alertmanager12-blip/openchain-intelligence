package io.openchain.alerting.rule;

/**
 * One structured validation problem.
 *
 * @param path JSON path of the offending node, e.g. {@code $.conditions.rules[2].field}
 */
public record ValidationError(String path, Code code, String message) {

    public enum Code {
        SYNTAX, UNSAFE_DOCUMENT, UNKNOWN_PROPERTY, REQUIRED, TYPE, PATTERN, UNSUPPORTED_API_VERSION, UNKNOWN_FIELD,
        INVALID_OPERATOR, INVALID_VALUE, LIMIT_EXCEEDED, INVALID_REGEX, UNSAFE_REGEX, INVALID_TEMPLATE, CONFLICT,
        INVALID_TEST
    }

    @Override public String toString() { return path + " [" + code + "] " + message; }
}
