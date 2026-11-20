package io.openchain.app.api;

import io.openchain.alerting.alert.ConcurrentAlertModificationException;
import io.openchain.alerting.alert.IllegalAlertTransitionException;
import io.openchain.app.persistence.OptimisticLockException;
import io.openchain.app.rules.RuleRegistry;
import io.openchain.core.provider.ProviderException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error leaves the API as {@code application/problem+json} (RFC 9457) with the contract's type URIs. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<ProblemDetail> of(HttpServletRequest req, int status, String type, String title, ApiCode code,
                                                    Map<String, Object> params, List<ApiException.FieldError> errors) {
        return Problems.entity(Problems.problem(status, type, title, code, params, req.getRequestURI(), errors));
    }

    private static Map<String, Object> reason(Throwable e) { return Map.of("reason", String.valueOf(e.getMessage())); }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest req) {
        ResponseEntity<ProblemDetail> r = of(req, e.status(), e.type(), e.title(), e.code(), e.params(), e.errors());
        if (e instanceof ApiException.RateLimited rl) {
            return ResponseEntity.status(429).headers(r.getHeaders()).header("Retry-After", Long.toString(rl.retryAfterSeconds())).body(r.getBody());
        }
        return r;
    }

    @ExceptionHandler(RuleRegistry.InvalidRuleException.class)
    ResponseEntity<ProblemDetail> invalidRule(RuleRegistry.InvalidRuleException e, HttpServletRequest req) {
        return of(req, 422, "validation-error", "Request validation failed", ApiCode.RULE_INVALID, reason(e), RuleErrors.of(e.errors(), ""));
    }

    @ExceptionHandler(RuleRegistry.UnknownRuleException.class)
    ResponseEntity<ProblemDetail> unknownRule(RuleRegistry.UnknownRuleException e, HttpServletRequest req) {
        return of(req, 404, "not-found", "Resource not found", ApiCode.RULE_NOT_FOUND, Map.of("id", e.id()), null);
    }

    @ExceptionHandler(RuleRegistry.RuleConflictException.class)
    ResponseEntity<ProblemDetail> ruleConflict(RuleRegistry.RuleConflictException e, HttpServletRequest req) {
        return of(req, 409, "conflict", "Conflict", ApiCode.GENERIC_CONFLICT, reason(e), null);
    }

    @ExceptionHandler({OptimisticLockException.class, ConcurrentAlertModificationException.class})
    ResponseEntity<ProblemDetail> concurrentModification(RuntimeException e, HttpServletRequest req) {
        return of(req, 409, "conflict", "Conflict", ApiCode.GENERIC_CONFLICT, reason(e), null);
    }

    @ExceptionHandler(IllegalAlertTransitionException.class)
    ResponseEntity<ProblemDetail> transition(IllegalAlertTransitionException e, HttpServletRequest req) {
        return of(req, 409, "conflict", "Illegal alert transition", ApiCode.GENERIC_CONFLICT, reason(e), null);
    }

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<ProblemDetail> provider(ProviderException e, HttpServletRequest req) {
        return of(req, 503, "upstream-unavailable", "Upstream unavailable", ApiCode.UPSTREAM_PROVIDER_UNAVAILABLE, reason(e), null);
    }

    /**
     * Every standard Spring MVC exception (unknown route, bad method, unreadable body, missing/mistyped parameter,
     * media type...) is rewritten into the contract's problem format (type URI, title, traceId, instance).
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                             WebRequest request) {
        int status = statusCode.value();
        String uri = request instanceof ServletWebRequest sw ? sw.getRequest().getRequestURI() : null;
        String method = request instanceof ServletWebRequest sw ? sw.getRequest().getMethod() : "";
        ApiCode code;
        Map<String, Object> params;
        if (status == 404) { code = ApiCode.HTTP_NO_ENDPOINT; params = Map.of("method", method, "uri", String.valueOf(uri)); }
        else if (ex instanceof HttpMessageNotReadableException) { code = ApiCode.HTTP_MALFORMED_BODY; params = Map.of(); }
        else if (status >= 500) { code = ApiCode.ERROR_UNEXPECTED; params = Map.of(); }
        else {
            code = ApiCode.HTTP_ERROR;
            params = Map.of("reason", String.valueOf(body instanceof ProblemDetail pd && pd.getDetail() != null ? pd.getDetail() : ex.getMessage()));
        }
        if (status >= 500) log.error("MVC error on {} {}", method, uri, ex);
        ProblemDetail p = Problems.problem(status, typeFor(status), titleFor(status), code, params, uri, null);
        HttpHeaders h = new HttpHeaders();
        if (headers != null) h.putAll(headers);
        h.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(p, h, statusCode);
    }

    static String typeFor(int status) {
        return switch (status) {
            case 400 -> "bad-request";
            case 401 -> "unauthenticated";
            case 403 -> "forbidden";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 406 -> "not-acceptable";
            case 409 -> "conflict";
            case 412 -> "precondition-failed";
            case 413 -> "payload-too-large";
            case 415 -> "unsupported-media-type";
            case 422 -> "validation-error";
            case 429 -> "rate-limited";
            case 502, 503, 504 -> "upstream-unavailable";
            default -> status >= 500 ? "internal-error" : "bad-request";
        };
    }

    static String titleFor(int status) {
        return switch (status) {
            case 400 -> "Bad request";
            case 401 -> "Authentication required";
            case 403 -> "Forbidden";
            case 404 -> "Resource not found";
            case 405 -> "Method not allowed";
            case 406 -> "Not acceptable";
            case 409 -> "Conflict";
            case 412 -> "Precondition failed";
            case 413 -> "Payload too large";
            case 415 -> "Unsupported media type";
            case 422 -> "Request validation failed";
            case 429 -> "Too many requests";
            default -> status >= 500 ? "Internal server error" : "Bad request";
        };
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e, HttpServletRequest req) {
        return of(req, 403, "forbidden", "Forbidden", ApiCode.AUTH_MISSING_SCOPE, Map.of(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
        // full details in the server log only; the body is generic (no exception message, no stack trace)
        ProblemDetail p = Problems.problem(500, "internal-error", "Internal server error", ApiCode.ERROR_UNEXPECTED, Map.of(),
                req.getRequestURI(), null);
        log.error("unhandled error on {} {} (traceId {})", req.getMethod(), req.getRequestURI(), p.getProperties().get("traceId"), e);
        return Problems.entity(p);
    }
}
