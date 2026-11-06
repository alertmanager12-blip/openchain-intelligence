package io.openchain.app;

import io.openchain.alerting.notify.http.OutboundHttp;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shared Spring context: demo mode with a small simulated TRON chain, fixed test keys, stubbed outbound HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiTestSupport.TestBeans.class)
public abstract class ApiTestSupport {

    public static final String ADMIN = "oci_test_admin001_TestAdminKeyOnlyForUnitTests000000000001";
    public static final String READER = "oci_test_reader01_TestReaderKeyOnlyForUnitTests00000000001";
    public static final String ANALYST = "oci_test_analyst1_TestAnalystKeyOnlyForUnitTests0000000001";

    @Autowired protected MockMvc mvc;

    /** Records webhook calls instead of sending them; answers 204. */
    public static final class StubHttp implements OutboundHttp {
        public final List<URI> calls = new CopyOnWriteArrayList<>();
        public final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        @Override public HttpResult post(URI uri, Map<String, String> h, byte[] body) {
            calls.add(uri);
            headers.add(h);
            return new HttpResult(204, Map.of(), new byte[0], false);
        }
    }

    /** Endpoint that fails with an internal message, to check that 500 bodies stay generic (picked up by component scan). */
    @RestController
    public static class BoomController {
        @GetMapping("/api/v1/test-boom")
        public String boom() { throw new IllegalStateException("secret internal detail db=jdbc:postgresql://10.0.0.1"); }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary StubHttp stubHttp() { return new StubHttp(); }
    }
}
