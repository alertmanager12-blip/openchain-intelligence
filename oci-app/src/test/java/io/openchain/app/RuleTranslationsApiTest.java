package io.openchain.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.engine.RuleMatch;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Rule texts in French come from the rule YAML ({@code translations.fr}) and reach rules and alerts through the API. */
class RuleTranslationsApiTest extends ApiTestSupport {

    @Autowired AlertService alerts;

    private String newAlert(String ruleId) {
        String key = UUID.randomUUID().toString();
        RuleMatch m = new RuleMatch(ruleId, "1.0.0", "Rule " + ruleId, Severity.HIGH, EvidenceLevel.ANOMALY, Network.TRON, key, key,
                "tron:0xabc:" + key, Instant.now(), List.of("tron:0xabc:" + key), Map.of("kind", "STATELESS"), List.of(), 0);
        return alerts.onMatch(m, Duration.ofMinutes(5)).alert().id();
    }

    @Test
    void shippedRuleCarriesItsFrenchTexts() throws Exception {
        mvc.perform(get("/api/v1/rules/wallet-draining").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Wallet drained (>80% of balance out in 2 minutes)"))
                .andExpect(jsonPath("$.translations.fr.name").value("Portefeuille vidé"))
                .andExpect(jsonPath("$.translations.fr.description").isNotEmpty())
                .andExpect(jsonPath("$.translations.fr.documentation").isNotEmpty());
    }

    @Test
    void alertsCarryTheCurrentTranslationsOfTheirRule() throws Exception {
        String id = newAlert("wallet-draining");
        mvc.perform(get("/api/v1/alerts?ruleId=wallet-draining&limit=1").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ruleId").value("wallet-draining"))
                .andExpect(jsonPath("$.items[0].ruleName").value("Rule wallet-draining"))
                .andExpect(jsonPath("$.items[0].ruleTranslations.fr.name").value("Portefeuille vidé"));
        mvc.perform(get("/api/v1/alerts/" + id).header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleTranslations.fr.name").value("Portefeuille vidé"));
    }

    @Test
    void alertOfARuleThatIsGoneHasNoTranslations() throws Exception {
        String id = newAlert("rule-that-does-not-exist");
        mvc.perform(get("/api/v1/alerts/" + id).header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleName").value("Rule rule-that-does-not-exist"))
                .andExpect(jsonPath("$.ruleTranslations").isMap())
                .andExpect(jsonPath("$.ruleTranslations").isEmpty());
    }

    /** Review Focus 2: a rule translated by name only sends no French description/documentation (the console falls back). */
    @Test
    void untranslatedTextsAreAbsentNotEmpty() throws Exception {
        String yaml = RuleApiTest.VALID.replace("test-big-transfer", "test-fr-name-only")
                + "translations:\n  fr:\n    name: \"Gros transfert de test\"\n";
        try {
            mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                            .content(RuleApiTest.json("yaml", yaml)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.translations.fr.name").value("Gros transfert de test"))
                    .andExpect(jsonPath("$.translations.fr.description").doesNotExist())
                    .andExpect(jsonPath("$.translations.fr.documentation").doesNotExist());
            mvc.perform(get("/api/v1/rules/test-fr-name-only").header("X-API-Key", READER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.translations.fr.name").value("Gros transfert de test"))
                    .andExpect(jsonPath("$.translations.fr.description").doesNotExist());
            mvc.perform(get("/api/v1/rules/large-usdt-transfer").header("X-API-Key", READER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.translations.fr.name").value("Gros transfert USDT (TRC20)"));
        } finally {
            mvc.perform(delete("/api/v1/rules/test-fr-name-only").header("X-API-Key", ADMIN));
        }
    }

    @Test
    void ruleWithoutTranslationsHasAnEmptyMap() throws Exception {
        String yaml = RuleApiTest.VALID.replace("test-big-transfer", "test-no-translations");
        try {
            mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType(MediaType.APPLICATION_JSON)
                            .content(RuleApiTest.json("yaml", yaml)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.translations").isMap())
                    .andExpect(jsonPath("$.translations").isEmpty());
        } finally {
            mvc.perform(delete("/api/v1/rules/test-no-translations").header("X-API-Key", ADMIN));
        }
    }
}
