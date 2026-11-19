package io.openchain.app.alerts;

import io.openchain.alerting.notify.ConnectorRegistry;
import io.openchain.alerting.notify.delivery.DeliveryService;
import io.openchain.app.config.OciProperties;
import io.openchain.app.rules.RuleRegistry;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * Which enabled rules notify nobody (spec 2026-11-19 §3), for the overview's reliability band. An action reaches someone
 * when its connector exists; a placeholder id ({@link DeliveryService#PLACEHOLDERS}) goes to {@code OCI_DEFAULT_CONNECTOR}
 * when that is set and exists, else to a connector of the same id. A rule is unrouted when it is enabled and none of its
 * actions reaches someone. Unlike the start-up warning, demo mode is not skipped. Connectors that exist but keep failing
 * are not checked here.
 */
@Service
public class NotificationRouting {

    /** What the check needs from a rule. */
    public record RuleActions(String id, boolean enabled, List<String> connectorIds) {}

    private final RuleRegistry rules;
    private final ConnectorRegistry connectors;
    private final OciProperties props;

    public NotificationRouting(RuleRegistry rules, ConnectorRegistry connectors, OciProperties props) {
        this.rules = rules;
        this.connectors = connectors;
        this.props = props;
    }

    /** Sorted ids of the enabled rules whose alerts reach nobody. */
    public List<String> unroutedRules() {
        List<RuleActions> all = rules.list().stream().map(r -> new RuleActions(r.id(), r.enabled(),
                r.current().rule().actions().stream().map(a -> a.connectorId()).toList())).toList();
        return unrouted(all, id -> connectors.find(id).isPresent(), props.getNotifications().getDefaultConnector());
    }

    static List<String> unrouted(List<RuleActions> rules, Predicate<String> exists, String defaultConnector) {
        String def = defaultConnector == null || defaultConnector.isBlank() ? null : defaultConnector.strip();
        Predicate<String> reaches = id -> {
            if (id == null) return false;
            if (DeliveryService.PLACEHOLDERS.contains(id) && def != null) return exists.test(def);
            return exists.test(id);
        };
        return rules.stream().filter(RuleActions::enabled).filter(r -> r.connectorIds().stream().noneMatch(reaches))
                .map(RuleActions::id).sorted().toList();
    }
}
