package io.openchain.alerting.notify.connector;

import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.http.OutboundHttp;
import io.openchain.alerting.notify.http.OutboundHttp.HttpResult;
import io.openchain.alerting.notify.http.SsrfException;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Shared POST + result mapping: 2xx ok; 3xx permanent (redirects are not followed); 408/425/429/5xx retry. */
final class HttpConnectorSupport {
    private HttpConnectorSupport() {}

    static DeliveryResult post(OutboundHttp http, URI uri, Map<String, String> headers, byte[] body) {
        try {
            HttpResult r = http.post(uri, headers, body);
            if (r.success()) return DeliveryResult.ok(r.status());
            if (r.redirect()) return DeliveryResult.permanent(r.status(), "redirect not followed");
            if (r.retryable()) return DeliveryResult.retry(r.status(), "HTTP " + r.status());
            return DeliveryResult.permanent(r.status(), "HTTP " + r.status());
        } catch (SsrfException e) {
            return DeliveryResult.permanent(0, "blocked by outbound policy: " + e.getMessage());
        } catch (IOException e) {
            return DeliveryResult.retry(0, "I/O: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return DeliveryResult.permanent(0, "invalid request: " + e.getMessage());
        }
    }

    static URI requireHttpsUri(String url, String... allowedHosts) {
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme())) throw new IllegalArgumentException("connector URL must be https");
        if (allowedHosts.length > 0) {
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
            boolean ok = false;
            for (String h : allowedHosts) ok |= host.equals(h);
            if (!ok) throw new IllegalArgumentException("connector host must be one of " + String.join(", ", allowedHosts));
        }
        return u;
    }

    static String json(Map<String, Object> m) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(m);
    }
}
