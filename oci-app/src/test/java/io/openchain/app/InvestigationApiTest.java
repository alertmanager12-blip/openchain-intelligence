package io.openchain.app;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.isA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openchain.app.chain.ChainIngestionService;
import io.openchain.core.model.Network;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class InvestigationApiTest extends ApiTestSupport {

    @Autowired ChainIngestionService chains;

    private String activeAddress() {
        return chains.runtime(Network.TRON).orElseThrow().simulator().orElseThrow().addressPool().get(0).value();
    }

    @Test
    void networksListsTheSimulatedChain() throws Exception {
        mvc.perform(get("/api/v1/networks").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].network").value("tron"))
                .andExpect(jsonPath("$[0].healthy").value(true))
                .andExpect(jsonPath("$[0].simulated").value(true))
                .andExpect(jsonPath("$[0].headBlock", greaterThan(0)));
    }

    @Test
    void walletAnalysisShape() throws Exception {
        mvc.perform(get("/api/v1/wallets/" + activeAddress() + "/analysis?network=tron").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value(activeAddress()))
                .andExpect(jsonPath("$.network").value("tron"))
                .andExpect(jsonPath("$.txCount", greaterThan(0)))
                .andExpect(jsonPath("$.firstSeen", isA(String.class)))
                .andExpect(jsonPath("$.risk.score", isA(Integer.class)))
                .andExpect(jsonPath("$.risk.level", isA(String.class)))
                .andExpect(jsonPath("$.flows[0].rawIn", isA(String.class)))
                .andExpect(jsonPath("$.flows[0].token.symbol", isA(String.class)))
                .andExpect(jsonPath("$.balances[0].rawAmount", isA(String.class)))
                .andExpect(jsonPath("$.counterparties[0].txCount", greaterThan(0)))
                .andExpect(jsonPath("$.activity.length()").value(30))
                .andExpect(jsonPath("$.labels").isArray())
                .andExpect(jsonPath("$.signals").isArray())
                .andExpect(jsonPath("$.disclaimer", isA(String.class)));
    }

    @Test
    void badParametersAre400Or422() throws Exception {
        mvc.perform(get("/api/v1/wallets/abc/analysis?network=dogecoin").header("X-API-Key", READER))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/wallets/abc/analysis?network=bitcoin").header("X-API-Key", READER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/validation-error"));
        mvc.perform(get("/api/v1/wallets/abc/analysis").header("X-API-Key", READER)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownTransactionIs404Problem() throws Exception {
        mvc.perform(get("/api/v1/transactions/deadbeefdeadbeefdeadbeef/analysis?network=tron").header("X-API-Key", ANALYST))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://openchain.io/problems/not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/transactions/deadbeefdeadbeefdeadbeef/analysis"))
                .andExpect(jsonPath("$.traceId", isA(String.class)));
    }

    @Test
    void graphExposureTransactionsAndLabels() throws Exception {
        String a = activeAddress();
        mvc.perform(get("/api/v1/graph/" + a + "?network=tron&depth=2").header("X-API-Key", ANALYST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root").value(a))
                .andExpect(jsonPath("$.nodes.length()", greaterThan(1)))
                .andExpect(jsonPath("$.edges[0].rawAmount", isA(String.class)));
        mvc.perform(get("/api/v1/wallets/" + a + "/exposure?network=tron").header("X-API-Key", ANALYST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.direct").isArray());
        mvc.perform(get("/api/v1/wallets/" + a + "/transactions?network=tron&limit=5").header("X-API-Key", ANALYST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].value", isA(String.class)))
                .andExpect(jsonPath("$.truncated").value(false));
        mvc.perform(get("/api/v1/labels?network=tron").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].category", hasItem("sanctioned")));
        mvc.perform(get("/api/v1/signals?limit=5").header("X-API-Key", ANALYST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }
}
