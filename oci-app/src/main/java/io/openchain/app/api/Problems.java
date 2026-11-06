package io.openchain.app.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/** Builds RFC 9457 problem documents with the contract's type URIs, a traceId and optional {@code errors[]}. */
public final class Problems {

    public static final String TYPE_BASE = "https://openchain.io/problems/";

    private Problems() {}

    public static ProblemDetail problem(int status, String type, String title, String detail, String instance,
                                        List<ApiException.FieldError> errors) {
        ProblemDetail p = ProblemDetail.forStatus(status);
        p.setType(URI.create(TYPE_BASE + type));
        p.setTitle(title);
        p.setDetail(detail);
        if (instance != null) p.setInstance(URI.create(instance));
        p.setProperty("traceId", traceId());
        if (errors != null && !errors.isEmpty()) p.setProperty("errors", errors.stream().map(ApiException.FieldError::toMap).toList());
        return p;
    }

    public static ResponseEntity<ProblemDetail> entity(ProblemDetail p) {
        return ResponseEntity.status(p.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    /** For filters / security handlers that run outside Spring MVC. */
    public static void write(HttpServletRequest req, HttpServletResponse res, JsonMapper json, HttpStatus status, String type,
                             String title, String detail) throws IOException {
        ProblemDetail p = problem(status.value(), type, title, detail, req.getRequestURI(), null);
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) res.setHeader("WWW-Authenticate", "Bearer realm=\"oci\"");
        json.writeValue(res.getOutputStream(), p);
    }

    private static String traceId() {
        byte[] b = new byte[16];
        ThreadLocalRandom.current().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
