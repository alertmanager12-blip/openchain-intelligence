package io.openchain.app.api;

import java.util.List;
import java.util.Map;

/** An error with an RFC 9457 problem type from the API contract table (docs/api/README.md) and a stable {@link ApiCode}. */
public class ApiException extends RuntimeException {

    /**
     * One field-level problem: JSON pointer, machine code, the code's params, human (English) message. {@code code} is
     * an {@link ApiCode#id()} (or, for rule validation, the validator's own key).
     */
    public record FieldError(String pointer, String code, Map<String, Object> params, String message) {
        public FieldError {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        /** The message is the code's template filled with its params. */
        public static FieldError of(String pointer, ApiCode code, Map<String, Object> params) {
            return new FieldError(pointer, code.id(), params, code.fill(params));
        }

        /** A message worded for this particular case, kept word for word. */
        public static FieldError of(String pointer, ApiCode code, Map<String, Object> params, String message) {
            return new FieldError(pointer, code.id(), params, message);
        }

        public Map<String, Object> toMap() {
            // "path" duplicates the pointer for the SPA's error parser, which reads pointer|field|path.
            return Map.of("pointer", pointer, "path", pointer, "code", code, "params", params, "message", message);
        }
    }

    private final int status;
    private final String type;
    private final String title;
    private final ApiCode code;
    private final Map<String, Object> params;
    private final List<FieldError> errors;

    public ApiException(int status, String type, String title, ApiCode code, Map<String, Object> params, List<FieldError> errors) {
        super(code.fill(params));
        this.status = status;
        this.type = type;
        this.title = title;
        this.code = code;
        this.params = params == null ? Map.of() : Map.copyOf(params);
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public int status() { return status; }
    public String type() { return type; }
    public String title() { return title; }
    public ApiCode code() { return code; }
    public Map<String, Object> params() { return params; }
    public List<FieldError> errors() { return errors; }

    public static ApiException badRequest(ApiCode code, Map<String, Object> params) {
        return new ApiException(400, "bad-request", "Bad request", code, params, null);
    }
    public static ApiException badRequest(ApiCode code) { return badRequest(code, Map.of()); }
    public static ApiException notFound(ApiCode code, Map<String, Object> params) {
        return new ApiException(404, "not-found", "Resource not found", code, params, null);
    }
    public static ApiException notFound(ApiCode code) { return notFound(code, Map.of()); }
    public static ApiException conflict(ApiCode code, Map<String, Object> params) {
        return new ApiException(409, "conflict", "Conflict", code, params, null);
    }
    public static ApiException conflict(ApiCode code) { return conflict(code, Map.of()); }
    public static ApiException forbidden(ApiCode code, Map<String, Object> params) {
        return new ApiException(403, "forbidden", "Forbidden", code, params, null);
    }
    public static ApiException tooManyRequests(ApiCode code, Map<String, Object> params) {
        return new ApiException(429, "too-many-requests", "Too many requests", code, params, null);
    }
    public static ApiException validation(ApiCode code, Map<String, Object> params, List<FieldError> errors) {
        return new ApiException(422, "validation-error", "Request validation failed", code, params, errors);
    }
    public static ApiException validation(ApiCode code, List<FieldError> errors) { return validation(code, Map.of(), errors); }
    public static ApiException upstream(ApiCode code, Map<String, Object> params) {
        return new ApiException(503, "upstream-unavailable", "Upstream unavailable", code, params, null);
    }

    /** 429; the handler sets {@code Retry-After}. No limiter exists yet (TODO rate limiting), the type is reserved. */
    public static final class RateLimited extends ApiException {
        private final long retryAfterSeconds;
        public RateLimited(ApiCode code, Map<String, Object> params, long retryAfterSeconds) {
            super(429, "rate-limited", "Too many requests", code, params, null);
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }
}
