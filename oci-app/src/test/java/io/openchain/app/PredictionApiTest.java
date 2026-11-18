package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.app.prediction.PredictionService;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/** ADR-0023 amendment 2 through the API: a prediction is PREDICTED, uncalibrated until measured, with its condition and limits. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PredictionApiTest extends ApiTestSupport {
    @Autowired PredictionService predictions;

    @Test void predictionsAndRates() throws Exception {
        Address w = new Address(Network.TRON, "TPredApiWallet111111111111111111");
        Token usdt = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
        ValueFlow wake = new ValueFlow("tron:" + "%064x".formatted(77) + ":0", Network.TRON, "%064x".formatted(77), Instant.parse("2026-11-18T06:00:00Z"),
                w, new Address(Network.TRON, "TPredApiOther1111111111111111111"), usdt, BigInteger.TEN);
        predictions.observe(wake, List.of(new Signal("sig-api", SignalType.DORMANT_REACTIVATION, w, wake.txHash(), Severity.LOW, 0.8,
                Signal.EvidenceLevel.ANOMALY, List.of(), "dormant-reactivation@1", wake.timestamp(), null, null)));
        predictions.tick();

        String r = mvc.perform(get("/api/v1/predictions").param("outcome", "open").header("X-API-Key", ANALYST)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(r, "$[?(@.subject == '" + w.value() + "')].level")).containsExactly("PREDICTED");
        assertThat((List<Object>) JsonPath.read(r, "$[?(@.subject == '" + w.value() + "')].confidence")).containsExactly((Object) null);
        assertThat((List<String>) JsonPath.read(r, "$[?(@.subject == '" + w.value() + "')].condition")).allMatch(c -> c.startsWith("at least 1 outgoing"));
        String rates = mvc.perform(get("/api/v1/predictions/rates").header("X-API-Key", ANALYST)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<Boolean>) JsonPath.read(rates, "$[*].established")).containsExactly(false);
        mvc.perform(get("/api/v1/predictions").param("outcome", "maybe").header("X-API-Key", ANALYST)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/predictions").header("X-API-Key", READER)).andExpect(status().isForbidden());
    }
}
