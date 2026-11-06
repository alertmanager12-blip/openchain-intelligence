package io.openchain.alerting.notify;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Operator-configured connectors by id. */
public final class ConnectorRegistry {

    private final Map<String, NotificationConnector> connectors = new ConcurrentHashMap<>();

    public ConnectorRegistry register(NotificationConnector c) {
        if (connectors.putIfAbsent(c.id(), c) != null) throw new IllegalArgumentException("duplicate connector id " + c.id());
        return this;
    }

    public Optional<NotificationConnector> find(String id) { return Optional.ofNullable(connectors.get(id)); }

    public Collection<NotificationConnector> all() { return connectors.values(); }
}
