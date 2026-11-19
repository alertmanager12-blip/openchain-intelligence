package io.openchain.alerting.alert;

import static io.openchain.alerting.Fx.T0;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The overview's queries: the most severe open alerts, and per rule the open ones and the recent ones. */
class AlertRepositoryDefaultsTest {
    static final Set<AlertStatus> OPEN = EnumSet.of(AlertStatus.DETECTED, AlertStatus.PENDING, AlertStatus.NOTIFIED);

    static Alert alert(String id, String rule, Severity sev, AlertStatus st, long createdSec) {
        return Alert.builder().id(id).ruleId(rule).ruleVersion("1.0.0").title(rule).severity(sev).priority(Priority.P2).status(st)
                .evidenceLevel(EvidenceLevel.RISK_SIGNAL).network(Network.TRON).groupKey("g").dedupKey(id)
                .firstSeen(T0).lastSeen(T0).createdAt(T0.plusSeconds(createdSec)).updatedAt(T0.plusSeconds(createdSec)).build();
    }

    final InMemoryAlertRepository repo = new InMemoryAlertRepository();

    void seed() {
        repo.save(alert("high-old", "rule-a", Severity.HIGH, AlertStatus.PENDING, 0));
        repo.save(alert("high-new", "rule-a", Severity.HIGH, AlertStatus.DETECTED, 600));
        repo.save(alert("crit-open", "rule-b", Severity.CRITICAL, AlertStatus.NOTIFIED, 60));
        repo.save(alert("crit-resolved", "rule-b", Severity.CRITICAL, AlertStatus.RESOLVED, 900));
        repo.save(alert("med-ack", "rule-a", Severity.MEDIUM, AlertStatus.ACKNOWLEDGED, 900));
        repo.save(alert("low-open", "rule-c", Severity.LOW, AlertStatus.PENDING, 900));
    }

    @Test void topOpenIsTheMostSevereFirstThenTheNewest() {
        seed();
        assertThat(repo.findTopOpen(OPEN, 3)).extracting(Alert::id).containsExactly("crit-open", "high-new", "high-old");
        assertThat(repo.findTopOpen(OPEN, 10)).extracting(Alert::id).containsExactly("crit-open", "high-new", "high-old", "low-open");
    }

    @Test void openAndRecentAreCountedPerRule() {
        seed();
        assertThat(repo.openAndRecentByRule(OPEN, T0.plusSeconds(300))).containsExactlyInAnyOrder(
                new AlertRepository.RuleOpenAndRecent("rule-a", 2, 2),   // high-old + high-new open; high-new + med-ack recent
                new AlertRepository.RuleOpenAndRecent("rule-b", 1, 1),   // crit-open open; crit-resolved recent
                new AlertRepository.RuleOpenAndRecent("rule-c", 1, 1));
    }
}
