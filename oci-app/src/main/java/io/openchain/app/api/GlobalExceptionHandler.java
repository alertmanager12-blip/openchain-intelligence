package io.openchain.app.api;

import io.openchain.alerting.alert.IllegalAlertTransitionException;
import io.openchain.app.rules.RuleRegistry;
import io.openchain.core.provider.ProviderException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Every error leaves the API as {@code application/problem+json} (RFC 9457) with the contract's type URIs. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<ProblemDetail> of(HttpServletRequest req, int status, String type, String title, String detail,
                                                    List<ApiException.FieldError> errors) {
        return Problems.entity(Problems.problem(status, type, title, detail, req.getRequestURI(), errors));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest req) {
        ResponseEntity<ProblemDetail> r = of(req, e.status(), e.type(), e.title(), e.getMessage(), e.errors());
        if (e instanceof ApiException.RateLimited rl) {
            return ResponseEntity.status(429).headers(r.getHeaders()).header("Retry-After", Long.toString(rl.retryAfterSeconds())).body(r.getBody());
        }
        return r;
    }

    @ExceptionHandler(RuleRegistry.InvalidRuleException.class)
    ResponseEntity<ProblemDetail> invalidRule(RuleRegistry.InvalidRuleException e, HttpServletRequest req) {
        return of(req, 422, "validation-error", "Request validation failed", e.getMessage(), RuleErrors.of(e.errors(), ""));
    }

    @ExceptionHandler(RuleRegistry.UnknownRuleException.class)
    ResponseEntity<ProblemDetail> unknownRule(RuleRegistry.UnknownRuleException e, HttpServletRequest req) {
        return of(req, 404, "not-found", "Resource not found", e.getMessage(), null);
    }

    @ExceptionHandler(RuleRegistry.RuleConflictException.class)
    ResponseEntity<ProblemDetail> ruleConflict(RuleRegistry.RuleConflictException e, HttpServletRequest req) {
        return of(req, 409, "conflict", "Conflict", e.getMessage(), null);
    }

    @ExceptionHandler(IllegalAlertTransitionException.class)
    ResponseEntity<ProblemDetail> transition(IllegalAlertTransitionException e, HttpServletRequest req) {
        return of(req, 409, "conflict", "Illegal alert transition", e.getMessage(), null);
    }

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<ProblemDetail> provider(ProviderException e, HttpServletRequest req) {
        return of(req, 503, "upstream-unavailable", "Upstream unavailable", "chain provider unavailable: " + e.getMessage(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noResource(NoResourceFoundException e, HttpServletRequest req) {
        return of(req, 404, "not-found", "Resource not found", "no endpoint " + req.getMethod() + " " + req.getRequestURI(), null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception e, HttpServletRequest req) {
        String detail = e instanceof HttpMessageNotReadableException ? "malformed request body" : e.getMessage();
        return of(req, 400, "bad-request", "Bad request", detail, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return of(req, 405, "method-not-allowed", "Method not allowed", e.getMessage(), null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return of(req, 415, "unsupported-media-type", "Unsupported media type", e.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e, HttpServletRequest req) {
        return of(req, 403, "forbidden", "Forbidden", "missing scope or role", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
        // full details in the server log only; the body is generic (no exception message, no stack trace)
        ProblemDetail p = Problems.problem(500, "internal-error", "Internal server error", "An unexpected error occurred.",
                req.getRequestURI(), null);
        log.error("unhandled error on {} {} (traceId {})", req.getMethod(), req.getRequestURI(), p.getProperties().get("traceId"), e);
        return Problems.entity(p);
    }
}
