package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/** ADR-0023 amendment 2 through the API: a what-if is SIMULATED, carries its hypothesis, and changes nothing observed. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SimulationApiTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Instant T0 = Instant.parse("2026-11-18T05:00:00Z");
    static int seq;

    @Autowired IntelligencePipeline intel;

    static Address tron(String tag) { return new Address(Network.TRON, ("TSim" + tag + "000000000000000000000000000000000").substring(0, 34)); }

    void flow(Address from, Address to, String amount, int minute) {
        String tx = "%064x".formatted(0xB10_0000L + (++seq));
        intel.flows().add(ValueFlow.of(new TokenTransfer(Network.TRON, tx, 0, 13_000 + seq, "0xsim" + seq, T0.plusSeconds(60L * minute), from, to,
                USDT, new BigDecimal(amount).movePointRight(6).toBigIntegerExact())));
    }

    String body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    @Test void aWhatIfIsSimulatedCarriesItsHypothesisAndChangesNothing() throws Exception {
        Address x = tron("hypo"), b = tron("down"), c = tron("down2");
        flow(x, b, "300", 1);
        flow(b, c, "200", 2);
        int labelsBefore = intel.labels().size();
        long signalsBefore = intel.signals().findByAddress(b).size();

        String r = body(mvc.perform(get("/api/v1/simulations/exposure").header("X-API-Key", READER_INV)
                .param("network", "tron").param("address", x.value()).param("category", "sanctioned").param("depth", "2")).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(r, "$.level")).isEqualTo("SIMULATED");
        assertThat((String) JsonPath.read(r, "$.hypothesis.address")).isEqualTo(x.value());
        assertThat((String) JsonPath.read(r, "$.hypothesis.category")).isEqualTo("SANCTIONED");
        assertThat((String) JsonPath.read(r, "$.statement")).startsWith("Simulation:").contains("hypothesis, not a listing");
        assertThat((List<String>) JsonPath.read(r, "$.exposed[*].address")).containsExactlyInAnyOrder(b.value(), c.value());
        assertThat((List<String>) JsonPath.read(r, "$.exposed[*].level")).allMatch("SIMULATED"::equals);

        // nothing observed changed: no label, no signal, and the real exposure of b shows nothing about x
        assertThat(intel.labels().size()).isEqualTo(labelsBefore);
        assertThat(intel.labels().findByAddress(x)).isEmpty();
        assertThat(intel.signals().findByAddress(b)).hasSize((int) signalsBefore);
        String real = body(mvc.perform(get("/api/v1/wallets/" + b.value() + "/exposure").param("network", "tron").header("X-API-Key", ADMIN))
                .andExpect(status().isOk()));
        assertThat(real).doesNotContain(x.value());
    }

    @Test void refusalsAndPermissions() throws Exception {
        Address x = tron("refuse");
        mvc.perform(get("/api/v1/simulations/exposure").header("X-API-Key", READER_INV).param("network", "tron").param("address", x.value())
                .param("category", "exchange")).andExpect(status().isBadRequest()); // not a risky category
        mvc.perform(get("/api/v1/simulations/exposure").header("X-API-Key", READER_INV).param("network", "tron").param("address", x.value())
                .param("category", "sanctioned").param("depth", "9")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/simulations/exposure").header("X-API-Key", READER).param("network", "tron").param("address", x.value())
                .param("category", "sanctioned")).andExpect(status().isForbidden());
        String empty = body(mvc.perform(get("/api/v1/simulations/exposure").header("X-API-Key", READER_INV).param("network", "tron")
                .param("address", x.value()).param("category", "sanctioned")).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(empty, "$.statement")).as("no wallet, without complete coverage, is not 'nobody'")
                .contains("in the data covered");
    }

    static final String READER_INV = "oci_test_invread1_TestInvReaderKeyOnlyForUnitTests00000001";
}
