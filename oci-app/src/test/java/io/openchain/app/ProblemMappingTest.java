package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.api.ApiException;
import io.openchain.app.api.GlobalExceptionHandler;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** 429 has no limiter yet, so its mapping is checked on the handler directly. */
class ProblemMappingTest {

    @Test
    @SuppressWarnings("unchecked")
    void rateLimitedMapsTo429WithRetryAfter() throws Exception {
        GlobalExceptionHandler h = new GlobalExceptionHandler();
        Method m = GlobalExceptionHandler.class.getDeclaredMethod("api", ApiException.class, jakarta.servlet.http.HttpServletRequest.class);
        m.setAccessible(true);
        var r = (ResponseEntity<ProblemDetail>) m.invoke(h, new ApiException.RateLimited("slow down", 42), new MockHttpServletRequest("GET", "/api/v1/graph/x"));
        assertThat(r.getStatusCode().value()).isEqualTo(429);
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("42");
        assertThat(r.getBody().getType().toString()).isEqualTo("https://openchain.io/problems/rate-limited");
    }
}
