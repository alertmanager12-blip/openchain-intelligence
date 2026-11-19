package io.openchain.app.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.alerts.NotificationRouting.RuleActions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which enabled rules notify nobody (spec 2026-11-19 §3). */
class NotificationRoutingTest {
    static final Set<String> EXISTING = Set.of("ops-telegram");

    static List<String> unrouted(String defaultConnector, RuleActions... rules) {
        return NotificationRouting.unrouted(List.of(rules), EXISTING::contains, defaultConnector);
    }

    @Test void aPlaceholderWithoutDefaultConnectorReachesNobody() {
        assertThat(unrouted(null, new RuleActions("wallet-draining", true, List.of("default-webhook")))).containsExactly("wallet-draining");
        assertThat(unrouted("  ", new RuleActions("wallet-draining", true, List.of("default")))).containsExactly("wallet-draining");
    }

    @Test void aPlaceholderGoesToAnExistingDefaultConnector() {
        assertThat(unrouted("ops-telegram", new RuleActions("wallet-draining", true, List.of("default-webhook")))).isEmpty();
    }

    @Test void aDefaultConnectorThatDoesNotExistReachesNobody() {
        assertThat(unrouted("missing", new RuleActions("wallet-draining", true, List.of("default-webhook")))).containsExactly("wallet-draining");
    }

    @Test void oneWorkingActionIsEnough() {
        assertThat(unrouted(null, new RuleActions("large-usdt-transfer", true, List.of("default-webhook", "ops-telegram")))).isEmpty();
    }

    @Test void aDisabledRuleIsNotListedAndTheListIsSorted() {
        assertThat(unrouted(null,
                new RuleActions("z-rule", true, List.of("nowhere")),
                new RuleActions("off", false, List.of("default-webhook")),
                new RuleActions("a-rule", true, List.of()))).containsExactly("a-rule", "z-rule");
    }
}
