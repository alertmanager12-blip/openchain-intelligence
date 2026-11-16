package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Fund Flow Trace P0-C, server part (docs/FUND_FLOW_TRACE_P0_C_DIRECTED_GRAPH.md § 4, T1 to T7 and T10): with
 * {@code direction}, the graph follows value in its direction and in time order (trace engine), and says where and why it
 * stopped. Each test uses its own addresses.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // adds flows: never leak them to other classes
class DirectedGraphApiTest extends ApiTestSupport {
    static final Token USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
    static final Token TRX = Token.nativeOf(Network.TRON, "TRX", 6);
    static final Instant T0 = Instant.parse("2026-11-16T08:00:00Z");
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired IntelligencePipeline intel;

    static Address tron(String tag) { return new Address(Network.TRON, ("TGraph" + tag + "000000000000000000000000000000").substring(0, 34)); }

    ValueFlow flow(Address from, Address to, String amount, Token tok, int minute) {
        int n = SEQ.incrementAndGet();
        String tx = "%064x".formatted(0xC0_0000L + n);
        ValueFlow f = ValueFlow.of(new TokenTransfer(Network.TRON, tx, tok.isNative() ? 0 : 0, 2_000 + n, "0xblock" + n,
                T0.plusSeconds(60L * minute), from, to, tok, new BigDecimal(amount).movePointRight(tok.decimals()).toBigIntegerExact()));
        intel.flows().add(f);
        return f;
    }

    ValueFlow flow(Address from, Address to, String amount, int minute) { return flow(from, to, amount, USDT, minute); }

    String graph(Address root, String direction, int depth) throws Exception {
        return mvc.perform(get("/api/v1/graph/" + root.value() + "?network=tron&depth=" + depth + "&direction=" + direction)
                        .header("X-API-Key", ANALYST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.direction").value(direction))
                .andReturn().getResponse().getContentAsString();
    }

    static List<String> nodes(String json) { return JsonPath.read(json, "$.nodes[*].address"); }

    @Test void t1ValueFollowedForwardInTimeOrder() throws Exception {
        Address a = tron("t1a"), b = tron("t1b"), c = tron("t1c");
        ValueFlow ab = flow(a, b, "100", 1), bc = flow(b, c, "100", 2);
        String g = graph(a, "out", 2);
        assertThat(nodes(g)).containsExactlyInAnyOrder(a.value(), b.value(), c.value());
        List<String> ids = JsonPath.read(g, "$.edges[*].eventIds[*]");
        assertThat(ids).containsExactlyInAnyOrder(ab.eventId(), bc.eventId());
        List<String> first = JsonPath.read(g, "$.edges[?(@.source == '" + b.value() + "')].firstTimestamp");
        assertThat(first).containsExactly(bc.timestamp().toString());
    }

    @Test void t2AnEarlierFlowCannotCarryLaterValue() throws Exception {
        Address a = tron("t2a"), b = tron("t2b"), c = tron("t2c");
        flow(b, c, "100", 1);
        flow(a, b, "100", 2);
        assertThat(nodes(graph(a, "out", 2))).doesNotContain(c.value());
    }

    @Test void t3DirectionMatters() throws Exception {
        Address a = tron("t3a"), b = tron("t3b"), c = tron("t3c");
        flow(a, b, "100", 1);
        flow(c, b, "100", 2);
        assertThat(nodes(graph(a, "out", 2))).doesNotContain(c.value());
        assertThat(nodes(graph(b, "both", 1))).contains(a.value(), c.value());
        List<String> sides = JsonPath.read(graph(b, "both", 1), "$.nodes[?(@.address == '" + c.value() + "')].side");
        assertThat(sides).containsExactly("in");
    }

    @Test void t4ALoopIsFoundOnceAndNotFollowed() throws Exception {
        Address a = tron("t4a"), b = tron("t4b"), c = tron("t4c");
        flow(a, b, "100", 1); flow(b, c, "100", 2); flow(c, a, "100", 3);
        String g = graph(a, "out", 3);
        List<Boolean> cycles = JsonPath.read(g, "$.edges[?(@.source == '" + c.value() + "')].cycle");
        assertThat(cycles).containsExactly(true);
        List<String> reasons = JsonPath.read(g, "$.stops[*].reason");
        assertThat(reasons).contains("CYCLE");
    }

    @Test void t5SameInstantIsAcceptedAndSaidSo() throws Exception {
        Address a = tron("t5a"), b = tron("t5b"), c = tron("t5c");
        flow(a, b, "100", 1); flow(b, c, "100", 1);
        String g = graph(a, "out", 2);
        assertThat(nodes(g)).contains(c.value());
        List<String> reasons = JsonPath.read(g, "$.stops[*].reason");
        assertThat(reasons).contains("SAME_TIME_ORDER");
    }

    @Test void t6AServiceLikeAddressStopsTheTraceThereWithAReason() throws Exception {
        Address a = tron("t6a"), b = tron("t6b"), c = tron("t6c");
        flow(a, b, "100", 1);
        for (int i = 0; i < 5_001; i++) flow(tron("t6z" + i), b, "1", 0);
        flow(b, c, "50", 2);
        String g = graph(a, "out", 2);
        assertThat(nodes(g)).contains(b.value()).doesNotContain(c.value());
        List<String> at = JsonPath.read(g, "$.stops[?(@.reason == 'SERVICE_LIKE')].address");
        assertThat(at).containsExactly(b.value());
        assertThat((String) JsonPath.read(g, "$.status")).isEqualTo("PARTIAL");
    }

    @Test void t7TheSameDataGiveTheSameGraph() throws Exception {
        Address a = tron("t7a"), b = tron("t7b"), c = tron("t7c"), d = tron("t7d");
        flow(a, b, "60", 1); flow(a, c, "40", 1); flow(b, d, "30", 2); flow(c, d, "20", 3);
        assertThat(graph(a, "out", 3)).isEqualTo(graph(a, "out", 3));
    }

    @Test void t10TokensAreNeverAdded() throws Exception {
        Address a = tron("t10a"), b = tron("t10b");
        flow(a, b, "100", USDT, 1);
        flow(a, b, "7", TRX, 2);
        String g = graph(a, "out", 1);
        List<String> tokens = JsonPath.read(g, "$.edges[*].token.symbol");
        assertThat(tokens).containsExactlyInAnyOrder("USDT", "TRX");
        List<String> usdt = JsonPath.read(g, "$.edges[?(@.token.symbol == 'USDT')].amount");
        assertThat(usdt).singleElement().satisfies(x -> assertThat(new BigDecimal(x)).isEqualByComparingTo("100"));
    }

    /** T9: the server gives a directed, chronological path with its transactions, or says there is none. */
    @Test void t9PathValueCouldHaveTaken() throws Exception {
        Address a = tron("t9a"), b = tron("t9b"), c = tron("t9c"), d = tron("t9d"), e = tron("t9e");
        ValueFlow ab = flow(a, b, "100", 1), bc = flow(b, c, "80", 2), cd = flow(c, d, "60", 3);
        flow(e, b, "5", 4); // e only sent to b
        String found = mvc.perform(get("/api/v1/graph/" + a.value() + "/path?network=tron&target=" + d.value()).header("X-API-Key", ANALYST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FOUND")).andReturn().getResponse().getContentAsString();
        List<String> txs = JsonPath.read(found, "$.hops[*].txHash");
        assertThat(txs).containsExactly(ab.txHash(), bc.txHash(), cd.txHash());
        mvc.perform(get("/api/v1/graph/" + a.value() + "/path?network=tron&target=" + e.value()).header("X-API-Key", ANALYST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NO_PATH"))
                .andExpect(jsonPath("$.hops.length()").value(0));
    }

    /** T8: a transfer removed after the graph was displayed is listed by the verification. */
    @Test void t8VerificationListsTransfersNoLongerHeld() throws Exception {
        Address a = tron("t8a"), b = tron("t8b"), c = tron("t8c");
        ValueFlow ab = flow(a, b, "100", 1), bc = flow(b, c, "100", 2);
        List<String> shown = JsonPath.read(graph(a, "out", 2), "$.edges[*].eventIds[*]");
        assertThat(shown).contains(ab.eventId(), bc.eventId());
        intel.retractFlow(bc.eventId()); // chain reorganisation
        String body = "{\"network\":\"tron\",\"eventIds\":[\"" + String.join("\",\"", shown) + "\"]}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/graph/verify").header("X-API-Key", ANALYST)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.missing").value(org.hamcrest.Matchers.contains(bc.eventId())));
        assertThat(nodes(graph(a, "out", 2))).doesNotContain(c.value());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/graph/verify").header("X-API-Key", READER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test void withoutDirectionTheDeprecatedUndirectedGraphIsUnchanged() throws Exception {
        Address a = tron("legacyA"), b = tron("legacyB"), c = tron("legacyC");
        flow(a, b, "100", 1);
        flow(c, b, "100", 2);
        String g = mvc.perform(get("/api/v1/graph/" + a.value() + "?network=tron&depth=2").header("X-API-Key", ANALYST))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(nodes(g)).contains(c.value()); // reached against the direction of value: the reason it is deprecated
    }
}
