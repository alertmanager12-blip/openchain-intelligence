package io.openchain.alerting.notify.http;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Minimal outbound HTTP port used by connectors (POST only). Implementations must enforce the SSRF policy. */
public interface OutboundHttp {

    /**
     * @throws SsrfException if the destination is not allowed (not retryable)
     * @throws IOException   on network failure / timeout (retryable)
     */
    HttpResult post(URI uri, Map<String, String> headers, byte[] body) throws IOException;

    /** @param truncated true if the response body exceeded the size cap and was cut */
    record HttpResult(int status, Map<String, String> headers, byte[] body, boolean truncated) {
        public boolean success() { return status >= 200 && status < 300; }
        public boolean redirect() { return status >= 300 && status < 400; }
        /** 408, 425, 429 and 5xx are transient. */
        public boolean retryable() { return status == 408 || status == 425 || status == 429 || status >= 500; }
    }
}
