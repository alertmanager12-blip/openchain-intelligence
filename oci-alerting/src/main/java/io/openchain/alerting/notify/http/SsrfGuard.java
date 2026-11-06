package io.openchain.alerting.notify.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Outbound URL policy (SSRF protection), applied to every connector URL before each request:
 * <ul>
 *   <li>https only; http only with the explicit {@code allowInsecureHttp} dev flag; no user-info;</li>
 *   <li>IP literals must be canonical dotted-quad / bracketed IPv6 (decimal, octal, hex and short forms such as
 *       {@code 2130706433}, {@code 0x7f.1}, {@code 0177.0.0.1}, {@code 127.1} are rejected);</li>
 *   <li>hostnames are resolved and EVERY address must be public: loopback, private (RFC1918, ULA), link-local
 *       (incl. 169.254.169.254 cloud metadata), CGNAT, multicast, reserved, documentation and IPv6 transition forms
 *       embedding such an IPv4 (IPv4-mapped/compatible, 6to4, NAT64) are rejected;</li>
 *   <li>single-label names, {@code localhost}, {@code *.localhost}, {@code *.internal} and metadata names are rejected;</li>
 *   <li>optional host allow-list (exact or {@code *.suffix}).</li>
 * </ul>
 * The returned {@link Target} carries the vetted address, which {@link SafeHttpClient} connects to directly (DNS pinning,
 * no re-resolution between check and use). {@code allowPrivateNetworks} exists for local development and tests only.
 */
public final class SsrfGuard {

    public record Policy(boolean allowInsecureHttp, boolean allowPrivateNetworks, Set<String> allowedHosts) {
        public Policy { allowedHosts = Set.copyOf(allowedHosts); }
        public static Policy strict() { return new Policy(false, false, Set.of()); }
        public Policy withAllowedHosts(Set<String> hosts) { return new Policy(allowInsecureHttp, allowPrivateNetworks, hosts); }
    }

    /** A vetted destination; {@code address} is the pinned IP to connect to. */
    public record Target(URI uri, String host, int port, boolean tls, InetAddress address) {}

    private static final Pattern HOSTNAME = Pattern.compile("^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");
    private static final Pattern NUMERIC_LABELS = Pattern.compile("^(0x[0-9a-f]*|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]+))*$");
    private static final Pattern CANONICAL_V4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Set<String> BLOCKED_NAMES = Set.of("localhost", "metadata", "metadata.google.internal",
            "instance-data", "instance-data.ec2.internal", "metadata.azure.com", "kubernetes.default", "kubernetes.default.svc");

    private final Policy policy;
    private final DnsResolver resolver;

    public SsrfGuard(Policy policy, DnsResolver resolver) {
        this.policy = policy;
        this.resolver = resolver;
    }

    public SsrfGuard() { this(Policy.strict(), DnsResolver.system()); }

    public Policy policy() { return policy; }

    /** @throws SsrfException if the URL must not be called */
    public Target check(URI uri) {
        if (uri == null || uri.isOpaque()) throw new SsrfException("URL must be absolute and hierarchical");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean tls = scheme.equals("https");
        if (!tls && !(scheme.equals("http") && policy.allowInsecureHttp()))
            throw new SsrfException("scheme '" + scheme + "' not allowed (https only)");
        if (uri.getRawUserInfo() != null) throw new SsrfException("user-info in URL is not allowed");
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isEmpty()) throw new SsrfException("URL has no (valid) host");
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        int port = uri.getPort() == -1 ? (tls ? 443 : 80) : uri.getPort();
        if (port < 1 || port > 65535) throw new SsrfException("invalid port");
        if (!policy.allowedHosts().isEmpty() && !hostAllowed(host))
            throw new SsrfException("host '" + host + "' is not in the allow-list");

        InetAddress pinned;
        if (host.startsWith("[")) {
            pinned = ipv6Literal(host);
            check(pinned, host);
        } else if (NUMERIC_LABELS.matcher(host).matches()) {
            if (!CANONICAL_V4.matcher(host).matches())
                throw new SsrfException("non-canonical IPv4 literal '" + host + "' is not allowed");
            pinned = literal(host);
            check(pinned, host);
        } else {
            if (!HOSTNAME.matcher(host).matches()) throw new SsrfException("invalid or single-label host name '" + host + "'");
            if (!policy.allowPrivateNetworks() && (BLOCKED_NAMES.contains(host) || host.endsWith(".localhost")
                    || host.endsWith(".internal") || host.endsWith(".local")))
                throw new SsrfException("host '" + host + "' is an internal name");
            List<InetAddress> addresses;
            try {
                addresses = resolver.resolve(host);
            } catch (UnknownHostException e) {
                throw new SsrfException("host '" + host + "' does not resolve");
            }
            if (addresses == null || addresses.isEmpty()) throw new SsrfException("host '" + host + "' does not resolve");
            for (InetAddress a : addresses) check(a, host); // every record must be public (anti DNS-rebinding mix)
            pinned = addresses.getFirst();
        }
        return new Target(uri, host, port, tls, pinned);
    }

    private void check(InetAddress a, String host) {
        if (policy.allowPrivateNetworks()) return;
        String reason = blockedReason(a);
        if (reason != null) throw new SsrfException("host '" + host + "' resolves to a " + reason + " address (" + a.getHostAddress() + ")");
    }

    private boolean hostAllowed(String host) {
        for (String allowed : policy.allowedHosts()) {
            String a = allowed.toLowerCase(Locale.ROOT);
            if (a.startsWith("*.") ? host.endsWith(a.substring(1)) : host.equals(a)) return true;
        }
        return false;
    }

    private static InetAddress ipv6Literal(String bracketed) {
        if (!bracketed.endsWith("]")) throw new SsrfException("malformed IPv6 literal");
        String inner = bracketed.substring(1, bracketed.length() - 1);
        if (inner.contains("%")) throw new SsrfException("IPv6 zone ids are not allowed");
        if (!inner.matches("^[0-9a-f:.]+$")) throw new SsrfException("malformed IPv6 literal");
        return literal(inner);
    }

    /** Parses an IP literal without DNS (input is pre-validated to contain only literal characters). */
    private static InetAddress literal(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            throw new SsrfException("malformed IP literal");
        }
    }

    /** @return null if the address is publicly routable, else a short category */
    public static String blockedReason(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return v4Reason(b, 0);
        if (a instanceof Inet6Address) {
            if (a.isAnyLocalAddress()) return "unspecified";
            if (a.isLoopbackAddress()) return "loopback";
            if (a.isLinkLocalAddress()) return "link-local";
            if (a.isSiteLocalAddress()) return "site-local";
            if (a.isMulticastAddress()) return "multicast";
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return "unique-local (private)";
            boolean first10Zero = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { first10Zero = false; break; }
            if (first10Zero && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) return embedded(b, 12, "IPv4-mapped");
            if (first10Zero && b[10] == 0 && b[11] == 0) return embedded(b, 12, "IPv4-compatible");
            if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) return embedded(b, 12, "NAT64");
            if (b0 == 0x20 && b1 == 0x02) return embedded(b, 2, "6to4");
            if (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0) return "Teredo";
            if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) return "documentation";
            if (b0 == 0x01 && b1 == 0x00 && b[2] == 0 && b[3] == 0) return "discard";
            if ((b0 & 0xe0) != 0x20) return "non-global-unicast"; // only 2000::/3 is global unicast
            return null;
        }
        return "unknown-family";
    }

    private static String embedded(byte[] b, int off, String kind) {
        String r = v4Reason(b, off);
        return r == null ? kind + " (transition)" : kind + " " + r;
    }

    private static String v4Reason(byte[] b, int o) {
        int a = b[o] & 0xff, c = b[o + 1] & 0xff, d = b[o + 2] & 0xff;
        if (a == 0) return "unspecified/this-network";
        if (a == 10) return "private";
        if (a == 100 && c >= 64 && c <= 127) return "CGNAT";
        if (a == 127) return "loopback";
        if (a == 169 && c == 254) return "link-local/metadata";
        if (a == 172 && c >= 16 && c <= 31) return "private";
        if (a == 192 && c == 0 && d == 0) return "IETF-reserved";
        if (a == 192 && c == 0 && d == 2) return "documentation";
        if (a == 192 && c == 88 && d == 99) return "6to4-relay";
        if (a == 192 && c == 168) return "private";
        if (a == 198 && (c == 18 || c == 19)) return "benchmark";
        if (a == 198 && c == 51 && d == 100) return "documentation";
        if (a == 203 && c == 0 && d == 113) return "documentation";
        if (a >= 224 && a <= 239) return "multicast";
        if (a >= 240) return "reserved/broadcast";
        return null;
    }
}
