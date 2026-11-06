package io.openchain.alerting.notify.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Injectable name resolution (tests, DoH, caching resolvers). */
@FunctionalInterface
public interface DnsResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;

    static DnsResolver system() { return host -> List.of(InetAddress.getAllByName(host)); }
}
