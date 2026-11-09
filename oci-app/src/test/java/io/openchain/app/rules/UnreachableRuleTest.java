package io.openchain.app.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.compile.RuleCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** contract-exploit shipped enabled although it could never fire (CONTRACT_CALL, contract.outflow_ratio never produced). */
class UnreachableRuleTest {
    final InMemoryRuleRepository repo = new InMemoryRuleRepository();
    final RuleRegistry registry = new RuleRegistry(new RuleLoader(), new RuleCompiler(), new RuleEngine(),
            Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC), repo);

    static String packRule() throws Exception {
        return Files.readString(Path.of("..", "rules", "ethereum", "contract-exploit.yaml"));
    }

    @Test void anInertRuleIsLoadedDisabledAndCannotBeEnabled(@TempDir Path dir) throws Exception {
        // even if a pack file says enabled: true
        Files.writeString(dir.resolve("contract-exploit.yaml"), packRule().replace("enabled: false", "enabled: true"));
        registry.loadDirectory(dir);
        assertThat(registry.get("contract-exploit").enabled()).isFalse();
        assertThatThrownBy(() -> registry.setEnabled("contract-exploit", true))
                .isInstanceOf(RuleRegistry.InvalidRuleException.class)
                .satisfies(e -> assertThat(((RuleRegistry.InvalidRuleException) e).errors())
                        .anySatisfy(err -> assertThat(err.message()).contains("CONTRACT_CALL")));
    }

    @Test void anInertRuleAlreadyEnabledIsDisabledAtStartUp(@TempDir Path dir) throws Exception {
        // an older pack version that could fire, enabled in the database (production situation before this check)
        String reachable = packRule().replace("enabled: false", "enabled: true")
                .replace("value: CONTRACT_CALL", "value: CONTRACT_ADMIN_CHANGE")
                .replace("field: contract.outflow_ratio\n            operator: GREATER_THAN_OR_EQUAL\n            value: \"0.5\"",
                        "field: contract.address\n            operator: EXISTS\n            value: true");
        Path file = dir.resolve("contract-exploit.yaml");
        Files.writeString(file, reachable);
        registry.loadDirectory(dir);
        assertThat(registry.get("contract-exploit").enabled()).isTrue();

        // the pack now ships the inert version: reloading keeps the operator's enabled flag ...
        Files.writeString(file, packRule());
        registry.loadDirectory(dir);
        assertThat(registry.get("contract-exploit").enabled()).isTrue();
        // ... and the start-up check disables it, with its reasons logged
        assertThat(registry.disableUnreachable()).containsExactly("contract-exploit");
        assertThat(registry.get("contract-exploit").enabled()).isFalse();
    }
}
