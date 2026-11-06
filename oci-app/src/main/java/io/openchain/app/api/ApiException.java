package io.openchain.app.api;

import java.util.List;
import java.util.Map;

/** An error with an RFC 9457 problem type from the API contract table (docs/api/README.md). */
public class ApiException extends RuntimeException {

    /** One field-level problem: JSON pointer, machine code, human message. */
    public record FieldError(String pointer, String code, String message) {
        public Map<String, Object> toMap() {
            // "path" duplicates the pointer for the SPA's error parser, which reads pointer|field|path.
            return Map.of("pointer", pointer, "path", pointer, "code", code, "message", message);
        }
    }

    private final int status;
    private final String type;
    private final String title;
    private final List<FieldError> errors;

    public ApiException(int status, String type, String title, String detail, List<FieldError> errors) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public int status() { return status; }
    public String type() { return type; }
    public String title() { return title; }
    public List<FieldError> errors() { return errors; }

    public static ApiException badRequest(String detail) { return new ApiException(400, "bad-request", "Bad request", detail, null); }
    public static ApiException notFound(String detail) { return new ApiException(404, "not-found", "Resource not found", detail, null); }
    public static ApiException conflict(String detail) { return new ApiException(409, "conflict", "Conflict", detail, null); }
    public static ApiException validation(String detail, List<FieldError> errors) {
        return new ApiException(422, "validation-error", "Request validation failed", detail, errors);
    }
    /** 429; the handler sets {@code Retry-After}. No limiter exists yet (TODO rate limiting), the type is reserved. */
    public static final class RateLimited extends ApiException {
        private final long retryAfterSeconds;
        public RateLimited(String detail, long retryAfterSeconds) {
            super(429, "rate-limited", "Too many requests", detail, null);
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    public static ApiException upstream(String detail) {
        return new ApiException(503, "upstream-unavailable", "Upstream unavailable", detail, null);
    }
}
