package io.openchain.alerting.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.alerting.notify.http.DnsResolver;
import io.openchain.alerting.notify.http.SsrfException;
import io.openchain.alerting.notify.http.SsrfGuard;
import io.openchain.alerting.notify.http.SsrfGuard.Policy;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfGuardTest {

    /** Fake DNS: no real network lookups in tests. */
    static final Map<String, List<String>> ZONE = Map.of(
            "hooks.example.com", List.of("93.184.216.34"),
            "rebind.attacker.test", List.of("127.0.0.1"),
            "internal.attacker.test", List.of("10.0.0.5"),
            "metadata.attacker.test", List.of("169.254.169.254"),
            "mixed.attacker.test", List.of("93.184.216.34", "192.168.1.10"),
            "v6.attacker.test", List.of("::1"),
            "public6.example.com", List.of("2606:4700:4700::1111"));

    static final DnsResolver FAKE = host -> {
        List<String> ips = ZONE.get(host);
        if (ips == null) throw new UnknownHostException(host);
        List<InetAddress> out = new java.util.ArrayList<>();
        for (String ip : ips) out.add(InetAddress.getByName(ip)); // literals only: no DNS
        return out;
    };

    final SsrfGuard strict = new SsrfGuard(Policy.strict(), FAKE);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook",
            "https://127.1.2.3/hook",
            "https://10.1.2.3/",
            "https://172.16.0.1/",
            "https://192.168.0.10/",
            "https://169.254.169.254/latest/meta-data/",
            "https://100.64.0.1/",                 // CGNAT
            "https://100.100.100.200/",            // Alibaba metadata (CGNAT range)
            "https://0.0.0.0/",
            "https://224.0.0.1/",                  // multicast
            "https://255.255.255.255/",
            "https://[::1]/",
            "https://[::]/",
            "https://[::ffff:127.0.0.1]/",         // IPv4-mapped IPv6
            "https://[::ffff:a9fe:a9fe]/",         // IPv4-mapped metadata, hex form
            "https://[fe80::1]/",                  // link-local
            "https://[fc00::1]/",                  // unique local
            "https://[fd00:ec2::254]/",            // AWS IPv6 metadata
            "https://[ff02::1]/",                  // multicast
            "https://[64:ff9b::7f00:1]/",          // NAT64 of 127.0.0.1
            "https://[2002:7f00:1::]/",            // 6to4 of 127.0.0.1
            "https://[2001:db8::1]/",              // documentation
            "https://2130706433/",                 // decimal-encoded 127.0.0.1
            "https://0x7f000001/",                 // hex-encoded
            "https://0177.0.0.1/",                 // octal
            "https://0x7f.1/",
            "https://127.1/",                      // short form
            "https://017700000001/",
            "https://localhost/",
            "https://LOCALHOST./",
            "https://api.localhost/",
            "https://metadata.google.internal/",
            "https://intranet/",                   // single label (search-domain expansion)
            "https://rebind.attacker.test/",       // DNS name resolving to loopback
            "https://internal.attacker.test/",     // ... to RFC1918
            "https://metadata.attacker.test/",     // ... to cloud metadata
            "https://mixed.attacker.test/",        // one public + one private record
            "https://v6.attacker.test/",
            "https://unknown.example.org/",        // does not resolve
            "http://hooks.example.com/",           // plain http without dev flag
            "ftp://hooks.example.com/",
            "file:///etc/passwd",
            "https://user:pass@hooks.example.com/",
            "https://[fe80::1%25eth0]/",
    })
    void blocked(String url) {
        assertThatThrownBy(() -> strict.check(URI.create(url))).as(url).isInstanceOf(SsrfException.class);
    }

    @Test void publicDestinationsAreAllowedAndPinned() throws Exception {
        SsrfGuard.Target t = strict.check(URI.create("https://hooks.example.com:8443/path?q=1"));
        assertThat(t.address()).isEqualTo(InetAddress.getByName("93.184.216.34"));
        assertThat(t.port()).isEqualTo(8443);
        assertThat(t.tls()).isTrue();
        assertThat(t.host()).isEqualTo("hooks.example.com");
        assertThat(strict.check(URI.create("https://93.184.216.34/")).address().getHostAddress()).isEqualTo("93.184.216.34");
        assertThat(strict.check(URI.create("https://public6.example.com/")).address()).isInstanceOf(java.net.Inet6Address.class);
        assertThat(strict.check(URI.create("https://[2606:4700:4700::1111]/")).port()).isEqualTo(443);
    }

    @Test void devFlagsAndAllowList() {
        SsrfGuard dev = new SsrfGuard(new Policy(true, false, Set.of()), FAKE);
        assertThat(dev.check(URI.create("http://hooks.example.com/")).port()).isEqualTo(80);
        assertThatThrownBy(() -> dev.check(URI.create("http://127.0.0.1/"))).isInstanceOf(SsrfException.class);
        SsrfGuard local = new SsrfGuard(new Policy(true, true, Set.of()), FAKE);
        assertThat(local.check(URI.create("http://127.0.0.1:8080/")).port()).isEqualTo(8080);

        SsrfGuard allow = new SsrfGuard(Policy.strict().withAllowedHosts(Set.of("*.example.com")), FAKE);
        assertThat(allow.check(URI.create("https://hooks.example.com/")).host()).isEqualTo("hooks.example.com");
        assertThatThrownBy(() -> allow.check(URI.create("https://93.184.216.34/"))).hasMessageContaining("allow-list");
    }

    @Test void blockedReasonCategories() throws Exception {
        assertThat(SsrfGuard.blockedReason(InetAddress.getByName("169.254.169.254"))).isEqualTo("link-local/metadata");
        assertThat(SsrfGuard.blockedReason(InetAddress.getByName("100.64.1.1"))).isEqualTo("CGNAT");
        assertThat(SsrfGuard.blockedReason(InetAddress.getByName("8.8.8.8"))).isNull();
        // IPv4-mapped kept as Inet6Address by explicit construction
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff; mapped[11] = (byte) 0xff; mapped[12] = 127; mapped[15] = 1;
        assertThat(SsrfGuard.blockedReason(java.net.Inet6Address.getByAddress(null, mapped, -1))).contains("loopback");
    }
}
