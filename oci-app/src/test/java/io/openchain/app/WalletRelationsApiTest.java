package io.openchain.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/** Phase 2: {@code GET /wallets/{a}/relations} returns inferences with confidence and evidence, behind investigation:read. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // adds flows: never leak them to other classes
class WalletRelationsApiTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-16T08:00:00Z");

    @Autowired IntelligencePipeline intel;

    static Address tron(String tag) { return new Address(Network.TRON, ("TRel" + tag + "000000000000000000000000000000000").substring(0, 34)); }

    ValueFlow flow(Address from, Address to, String amount, int minute, int n) {
        String tx = "%064x".formatted(0xA0_0000L + n);
        ValueFlow f = ValueFlow.of(new TokenTransfer(Network.TRON, tx, 0, 3_000 + n, "0xblock" + n, T0.plusSeconds(60L * minute), from, to, USDT,
                new BigDecimal(amount).movePointRight(6).toBigIntegerExact()));
        intel.flows().add(f);
        return f;
    }

    @Test void aCommonFunderIsReturnedAsAnInferenceWithItsEvidence() throws Exception {
        Address f = tron("funder"), a = tron("walletA"), b = tron("walletB");
        ValueFlow fa = flow(f, a, "500", 0, 1), fb = flow(f, b, "500", 20, 2);
        mvc.perform(get("/api/v1/wallets/" + a.value() + "/relations?network=tron").header("X-API-Key", ANALYST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relations[0].other").value(b.value()))
                .andExpect(jsonPath("$.relations[0].type").value("COMMON_FUNDER"))
                .andExpect(jsonPath("$.relations[0].level").value("INFERRED"))
                .andExpect(jsonPath("$.relations[0].via").value(f.value()))
                .andExpect(jsonPath("$.relations[0].eventIds").value(org.hamcrest.Matchers.containsInAnyOrder(fa.eventId(), fb.eventId())))
                .andExpect(jsonPath("$.limits[0]").value(org.hamcrest.Matchers.containsString("not proof of common ownership")))
                .andExpect(jsonPath("$.coverage.state").exists());
        mvc.perform(get("/api/v1/wallets/" + a.value() + "/relations?network=tron").header("X-API-Key", READER))
                .andExpect(status().isForbidden());
    }
}
