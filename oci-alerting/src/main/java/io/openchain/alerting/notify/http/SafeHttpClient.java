package io.openchain.alerting.notify.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Small HTTP/1.1 POST client hardened for untrusted destinations:
 * <ul>
 *   <li>every request goes through {@link SsrfGuard#check}, and the socket connects to the vetted IP itself
 *       (DNS pinning: no second resolution an attacker could rebind);</li>
 *   <li>TLS uses the JDK trust store, SNI and HTTPS hostname verification against the original host name;</li>
 *   <li>redirects are never followed (3xx is returned as-is);</li>
 *   <li>connect timeout, per-read timeout and an overall deadline; response headers and body are size-capped;</li>
 *   <li>header names/values are checked for CR/LF injection.</li>
 * </ul>
 */
public final class SafeHttpClient implements OutboundHttp {

    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private final SsrfGuard guard;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration totalTimeout;
    private final int maxResponseBytes;
    private final SSLSocketFactory tls;

    public SafeHttpClient(SsrfGuard guard, Duration connectTimeout, Duration readTimeout, Duration totalTimeout,
                          int maxResponseBytes, SSLSocketFactory tls) {
        this.guard = guard;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.totalTimeout = totalTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.tls = tls;
    }

    public SafeHttpClient(SsrfGuard guard) {
        this(guard, Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10), 64 * 1024,
                (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    @Override
    public HttpResult post(URI uri, Map<String, String> headers, byte[] body) throws IOException {
        SsrfGuard.Target t = guard.check(uri);
        long deadline = System.nanoTime() + totalTimeout.toNanos();
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(t.address(), t.port()), (int) connectTimeout.toMillis());
            raw.setSoTimeout((int) readTimeout.toMillis());
            Socket s = raw;
            if (t.tls()) {
                SSLSocket ssl = (SSLSocket) tls.createSocket(raw, t.host(), t.port(), true);
                SSLParameters p = ssl.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                if (!t.host().startsWith("[") && !Character.isDigit(t.host().charAt(t.host().length() - 1)))
                    p.setServerNames(List.of(new SNIHostName(t.host())));
                ssl.setSSLParameters(p);
                ssl.startHandshake();
                s = ssl;
            }
            OutputStream out = s.getOutputStream();
            out.write(requestHead(t, headers, body.length).getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return readResponse(s.getInputStream(), deadline);
        } finally {
            try { raw.close(); } catch (IOException ignored) { }
        }
    }

    private static String requestHead(SsrfGuard.Target t, Map<String, String> headers, int length) {
        URI u = t.uri();
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
        boolean defaultPort = (t.tls() && t.port() == 443) || (!t.tls() && t.port() == 80);
        StringBuilder sb = new StringBuilder();
        sb.append("POST ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(t.host()).append(defaultPort ? "" : ":" + t.port()).append("\r\n");
        sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("User-Agent: OpenChain-Intelligence-Webhook/0.1\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            String name = h.getKey(), value = h.getValue();
            if (!name.matches("^[A-Za-z0-9-]{1,64}$")) throw new IllegalArgumentException("invalid header name");
            if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.length() > 8192)
                throw new IllegalArgumentException("invalid header value for " + name);
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("host") || lower.equals("content-length") || lower.equals("connection") || lower.equals("transfer-encoding"))
                continue; // controlled by the client
            sb.append(name).append(": ").append(value).append("\r\n");
        }
        return sb.append("\r\n").toString();
    }

    private HttpResult readResponse(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;
        while (last4 != 0x0d0a0d0a) { // until CRLF CRLF
            checkDeadline(deadline);
            int c = in.read();
            if (c < 0) throw new IOException("connection closed before response headers");
            head.write(c);
            if (head.size() > MAX_HEADER_BYTES) throw new IOException("response headers too large");
            last4 = (last4 << 8) | c;
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/1.")) throw new IOException("malformed status line");
        int code;
        try { code = Integer.parseInt(status[1]); } catch (NumberFormatException e) { throw new IOException("malformed status code"); }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean truncated = false;
        long expected = -1;
        if (headers.containsKey("content-length")) {
            try { expected = Long.parseLong(headers.get("content-length")); } catch (NumberFormatException ignored) { }
        }
        boolean chunked = "chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", ""));
        byte[] buf = new byte[4096];
        // chunked bodies are kept raw (connectors only need status + a bounded diagnostic excerpt)
        while (expected != 0 || chunked) {
            checkDeadline(deadline);
            int want = (int) Math.min(buf.length, expected > 0 ? expected : buf.length);
            int n = in.read(buf, 0, want);
            if (n < 0) break;
            int room = maxResponseBytes - body.size();
            if (n > room) { body.write(buf, 0, Math.max(room, 0)); truncated = true; break; }
            body.write(buf, 0, n);
            if (expected > 0) expected -= n;
        }
        return new HttpResult(code, headers, body.toByteArray(), truncated);
    }

    private static void checkDeadline(long deadline) throws SocketTimeoutException {
        if (System.nanoTime() > deadline) throw new SocketTimeoutException("overall request deadline exceeded");
    }
}
