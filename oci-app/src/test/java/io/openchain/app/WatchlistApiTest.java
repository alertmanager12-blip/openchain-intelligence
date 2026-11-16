package io.openchain.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class WatchlistApiTest extends ApiTestSupport {
    static final String ADDR = "TQhbMYQdBr827qh1LU4Qf47RCKQXGnDpd5";

    @Test void watchlistLifecycleAndPermissions() throws Exception {
        String body = "{\"network\":\"tron\",\"address\":\"" + ADDR + "\",\"name\":\"Hot wallet\"}";
        mvc.perform(post("/api/v1/watchlist").header("X-API-Key", READER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()); // reading the list needs wallet:read, changing it watchlist:write (ANALYST role and above)
        String created = mvc.perform(post("/api/v1/watchlist").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.address").value(ADDR)).andExpect(jsonPath("$.name").value("Hot wallet"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        try {
            mvc.perform(post("/api/v1/watchlist").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict());
            mvc.perform(post("/api/v1/watchlist").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"network\":\"tron\",\"address\":\"not-an-address\"}")).andExpect(status().is4xxClientError());
            mvc.perform(get("/api/v1/watchlist").header("X-API-Key", READER))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == '" + id + "')].network").value("tron"));
        } finally {
            mvc.perform(delete("/api/v1/watchlist/" + id).header("X-API-Key", ADMIN)).andExpect(status().isNoContent());
        }
        mvc.perform(delete("/api/v1/watchlist/" + id).header("X-API-Key", ADMIN)).andExpect(status().isNotFound());
    }

    /** Code review I35: the watchlist changes what the behavioural rules look at, so adding and removing are audited. */
    @Test void watchlistChangesAreAudited() throws Exception {
        var audit = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("oci.audit");
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        audit.addAppender(captured);
        try {
            String addr = "TXLAQ63Xg1NAzckPwKHvzw7CSEmLMEqcdj";
            String created = mvc.perform(post("/api/v1/watchlist").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"network\":\"tron\",\"address\":\"" + addr + "\",\"name\":\"Treasury\"}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String id = JsonPath.read(created, "$.id");
            mvc.perform(delete("/api/v1/watchlist/" + id).header("X-API-Key", ADMIN)).andExpect(status().isNoContent());
            java.util.List<String> lines = captured.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            org.assertj.core.api.Assertions.assertThat(lines).anySatisfy(l -> org.assertj.core.api.Assertions.assertThat(l)
                    .startsWith("watchlist.add id=" + id).contains("network=tron", "address=" + addr, "actor=apikey:"));
            org.assertj.core.api.Assertions.assertThat(lines).anySatisfy(l -> org.assertj.core.api.Assertions.assertThat(l)
                    .startsWith("watchlist.remove id=" + id).contains("address=" + addr, "actor=apikey:"));
        } finally {
            audit.detachAppender(captured);
        }
    }
}
