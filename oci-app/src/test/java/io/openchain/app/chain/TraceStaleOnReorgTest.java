package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.ApiTestSupport;
import io.openchain.app.OciApplication;
import io.openchain.app.trace.TraceArtifact;
import io.openchain.app.trace.TraceArtifactService;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.trace.Trace;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** ADR-0022 amendment 1, step 3-3: a transfer that leaves the canonical chain makes the stored traces holding it STALE. */
class TraceStaleOnReorgTest {
    static ConfigurableApplicationContext ctx;

    @BeforeAll static void start() {
        ctx = new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class).profiles("test")
                .run("--server.port=0", "--oci.demo.networks=tron");
    }

    @AfterAll static void stop() { if (ctx != null) ctx.close(); }

    @Test void aReorgedTransferMakesTheTracesHoldingItStaleAndTheyStayReadable() {
        ChainIngestionService chains = ctx.getBean(ChainIngestionService.class);
        TraceArtifactService traces = ctx.getBean(TraceArtifactService.class);
        Address from = new Address(Network.POLYGON, "0x" + "6a".repeat(20)), to = new Address(Network.POLYGON, "0x" + "6b".repeat(20));
        Instant at = Instant.now().minusSeconds(600);
        ChainEvent in = ReorgIntegrationTest.transfer("polygon:0xstale1:0", "0xstale1", from, to, "50000000", at, false);
        chains.onEvent(in);

        TraceArtifact t = traces.run("default", Trace.Request.of(from, Trace.Direction.FORWARD), "analyst");
        TraceArtifact other = traces.run("default", Trace.Request.of(new Address(Network.POLYGON, "0x" + "6c".repeat(20)),
                Trace.Direction.FORWARD), "analyst");
        assertThat(t.eventIds()).contains(in.eventId());
        assertThat(traces.find("default", t.id()).orElseThrow().stale()).isFalse();

        chains.onEvent(ReorgIntegrationTest.transfer(in.eventId(), "0xstale1", from, to, "50000000", at, true)); // the block left the chain

        TraceArtifact stale = traces.find("default", t.id()).orElseThrow();
        assertThat(stale.stale()).isTrue();
        assertThat(stale.staleEventIds()).containsExactly(in.eventId());
        assertThat(stale.resultJson()).isEqualTo(t.resultJson()); // still readable as it was computed
        assertThat(traces.replay("default", t.id())).isPresent();   // and explainable from its frozen evidence
        assertThat(traces.find("default", other.id()).orElseThrow().stale()).isFalse();
    }
}
