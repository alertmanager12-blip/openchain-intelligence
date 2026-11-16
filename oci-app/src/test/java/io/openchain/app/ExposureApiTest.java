package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Fund Flow Trace P0-A ({@code docs/FUND_FLOW_TRACE_P0_A_EXPOSURE.md} § 4): {@code GET /wallets/{a}/exposure} qualifies
 * exposure like the detector (same {@code ExposurePaths}), reports exact amounts per token, says what it ignored and why,
 * and never presents its data as complete. Each test uses its own addresses.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // adds flows and labels: never leak them to other classes
class ExposureApiTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Token TRX = Token.nativeOf(Network.TRON, "TRX", 6);
    static final Token FAKE = new Token(Network.TRON, "TFakeTokenContract0000000000000001", Token.UNKNOWN_SYMBOL, 6);
    static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired IntelligencePipeline intel;

    static Address tron(String tag) { return new Address(Network.TRON, ("TExpo" + tag + "0000000000000000000000000000000").substring(0, 34)); }

    ValueFlow flow(Address from, Address to, String amount, Token tok, int minute) {
        int n = SEQ.incrementAndGet();
        String tx = "%064x".formatted(0xE0_0000L + n);
        ValueFlow f = ValueFlow.of(new TokenTransfer(Network.TRON, tx, 0, 1_000 + n, "0xblock" + n, T0.plusSeconds(60L * minute),
                from, to, tok, new BigDecimal(amount).movePointRight(tok.decimals()).toBigIntegerExact()));
        intel.flows().add(f);
        return f;
    }

    void label(Address a, LabelCategory c) {
        intel.labels().save(new AddressLabel(a, c, "test " + c, "test-list-" + c.name().toLowerCase(), 1.0, T0.minusSeconds(86_400),
                c == LabelCategory.SANCTIONED ? EvidenceLevel.CONFIRMED_BY_SOURCE : EvidenceLevel.RISK_SIGNAL, "test://" + a.value()));
    }

    ResultActions exposure(Address a) throws Exception {
        return mvc.perform(get("/api/v1/wallets/" + a.value() + "/exposure?network=tron&depth=3").header("X-API-Key", ANALYST))
                .andExpect(status().isOk());
    }

    @Test void r1DirectExposureWithExactAmountsPerToken() throws Exception {
        Address s = tron("r1s"), me = tron("r1me");
        label(s, LabelCategory.SANCTIONED);
        ValueFlow f = flow(s, me, "1000", USDT, 0);
        ValueFlow back = flow(me, s, "0.5", TRX, 1);
        exposure(me).andExpect(jsonPath("$.direct.length()").value(1))
                .andExpect(jsonPath("$.direct[0].category").value("sanctioned"))
                .andExpect(jsonPath("$.direct[0].evidenceLevel").value("CONFIRMED_BY_SOURCE"))
                .andExpect(jsonPath("$.direct[0].transfers").value(2))
                .andExpect(jsonPath("$.direct[0].amounts[?(@.token == 'USDT')].received").value("1000"))
                .andExpect(jsonPath("$.direct[0].amounts[?(@.token == 'USDT')].sent").value("0"))
                .andExpect(jsonPath("$.direct[0].amounts[?(@.token == 'TRX')].sent").value("0.5"))
                .andExpect(jsonPath("$.direct[0].txHashes").value(org.hamcrest.Matchers.contains(back.txHash(), f.txHash()))); // newest first
    }

    @Test void r2DustIsListedAsIgnoredNotAsExposure() throws Exception {
        Address s = tron("r2s"), me = tron("r2me");
        label(s, LabelCategory.SANCTIONED);
        flow(s, me, "1", USDT, 0);
        exposure(me).andExpect(jsonPath("$.direct.length()").value(0))
                .andExpect(jsonPath("$.ignored.length()").value(1))
                .andExpect(jsonPath("$.ignored[0].reason").value("below_dust_threshold"))
                .andExpect(jsonPath("$.ignored[0].amount").value("1"));
    }

    @Test void r3AnUnverifiedTokenIsIgnored() throws Exception {
        Address s = tron("r3s"), me = tron("r3me");
        label(s, LabelCategory.SANCTIONED);
        flow(s, me, "5000", FAKE, 0);
        exposure(me).andExpect(jsonPath("$.direct.length()").value(0))
                .andExpect(jsonPath("$.ignored[0].reason").value("unverified_token"));
    }

    @Test void r5FundsReceivedLaterAreNotTheOrigin() throws Exception {
        Address s = tron("r5s"), b = tron("r5b"), me = tron("r5me");
        label(s, LabelCategory.SANCTIONED);
        flow(b, me, "100", USDT, 0);
        flow(s, b, "300", USDT, 10);
        exposure(me).andExpect(jsonPath("$.indirect.length()").value(0));
    }

    @Test void r6IndirectExposureIsTracedInOneTokenWithItsPath() throws Exception {
        Address s = tron("r6s"), x = tron("r6x"), b = tron("r6b"), me = tron("r6me");
        label(s, LabelCategory.SANCTIONED);
        flow(s, b, "100", USDT, 0);
        flow(x, b, "1", TRX, 1);
        ValueFlow down = flow(b, me, "100", USDT, 10);
        exposure(me).andExpect(jsonPath("$.indirect.length()").value(1))
                .andExpect(jsonPath("$.indirect[0].hops").value(2))
                .andExpect(jsonPath("$.indirect[0].traced.token").value("USDT"))
                .andExpect(jsonPath("$.indirect[0].traced.amount").value("100"))
                .andExpect(jsonPath("$.indirect[0].confidence").value(0.5))
                .andExpect(jsonPath("$.indirect[0].path.length()").value(2))
                .andExpect(jsonPath("$.indirect[0].path[1].txHash").value(down.txHash()));
    }

    @Test void r9ANonRiskyCounterpartyIsInformationNotExposure() throws Exception {
        Address exchange = tron("r9ex"), me = tron("r9me");
        label(exchange, LabelCategory.EXCHANGE);
        flow(me, exchange, "1000", USDT, 0);
        exposure(me).andExpect(jsonPath("$.direct.length()").value(0))
                .andExpect(jsonPath("$.counterparties.length()").value(1))
                .andExpect(jsonPath("$.counterparties[0].category").value("exchange"));
    }

    @Test void r10TheCoverageIsNeverPresentedAsComplete() throws Exception {
        Address me = tron("r10me"), b = tron("r10b");
        flow(b, me, "50", USDT, 3);
        exposure(me).andExpect(jsonPath("$.direct.length()").value(0))
                .andExpect(jsonPath("$.coverage.state").value("PARTIAL"))
                .andExpect(jsonPath("$.coverage.dataFrom").value(T0.plusSeconds(180).toString()))
                .andExpect(jsonPath("$.coverage.limits").isNotEmpty());
    }

    @Test void r12AFlowRemovedByAReorganisationNoLongerCreatesExposure() throws Exception {
        Address s = tron("r12s"), me = tron("r12me");
        label(s, LabelCategory.SANCTIONED);
        ValueFlow f = flow(s, me, "1000", USDT, 0);
        exposure(me).andExpect(jsonPath("$.direct.length()").value(1));
        assertThat(intel.flows().remove(f.eventId())).isPresent();
        exposure(me).andExpect(jsonPath("$.direct.length()").value(0));
    }
}
