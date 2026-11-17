package io.openchain.app.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.trace.Trace;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-0022 amendment 1: budgets per client scope protect provider quotas; a full queue refuses instead of piling up. */
class TraceBudgetTest {
    final InMemoryTraceArtifactStore store = new InMemoryTraceArtifactStore();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
    final Trace.Source empty = new Trace.Source() {
        @Override public List<io.openchain.intelligence.flow.ValueFlow> outgoing(Address a) { return List.of(); }
        @Override public List<io.openchain.intelligence.flow.ValueFlow> incoming(Address a) { return List.of(); }
        @Override public boolean complete(Address a, Trace.Window w) { return true; }
        @Override public Optional<java.math.BigInteger> openingBalance(Address a, String k, Trace.Window w) { return Optional.empty(); }
        @Override public List<io.openchain.intelligence.label.AddressLabel> labels(Address a) { return List.of(); }
    };
    final Trace.Request req = Trace.Request.of(new Address(Network.TRON, "TBudget0000000000000000000000000000"), Trace.Direction.FORWARD);

    @Test void activeRunsPerScopeAreBounded() {
        TraceArtifactService s = new TraceArtifactService(store, () -> empty, clock, r -> { }, new TraceArtifactService.Budget(2, 100)); // never runs
        TraceArtifact first = s.submit("default", req, "a");
        s.submit("default", req, "a");
        assertThatThrownBy(() -> s.submit("default", req, "a")).isInstanceOf(TraceArtifactService.BudgetExceeded.class);
        s.submit("client-b", req, "b"); // another scope has its own budget
        s.cancel("default", first.id(), "a");
        assertThat(s.submit("default", req, "a").status()).isEqualTo(TraceArtifact.Status.PENDING);
    }

    @Test void runsPerDayAreBounded() {
        TraceArtifactService s = new TraceArtifactService(store, () -> empty, clock, Runnable::run, new TraceArtifactService.Budget(5, 2));
        s.submit("default", req, "a");
        s.submit("default", req, "a");
        assertThatThrownBy(() -> s.submit("default", req, "a")).isInstanceOf(TraceArtifactService.BudgetExceeded.class)
                .hasMessageContaining("per day");
    }

    @Test void aFullQueueFailsTheRunAndRefuses() {
        TraceArtifactService s = new TraceArtifactService(store, () -> empty, clock,
                r -> { throw new java.util.concurrent.RejectedExecutionException("full"); }, TraceArtifactService.Budget.DEFAULTS);
        assertThatThrownBy(() -> s.submit("default", req, "a")).isInstanceOf(TraceArtifactService.BudgetExceeded.class);
        assertThat(store.list("default", 10)).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(TraceArtifact.Status.FAILED);
            assertThat(a.error()).isEqualTo("trace queue full");
        });
    }

    @Test void unfinishedRunsAreResumed() {
        TraceArtifactService queued = new TraceArtifactService(store, () -> empty, clock, r -> { }, TraceArtifactService.Budget.DEFAULTS);
        TraceArtifact p = queued.submit("default", req, "a"); // stays PENDING (restart before it ran)
        TraceArtifactService restarted = new TraceArtifactService(store, () -> empty, clock, Runnable::run, TraceArtifactService.Budget.DEFAULTS);
        assertThat(restarted.resumePending()).isEqualTo(1);
        assertThat(store.find("default", p.id()).orElseThrow().status()).isEqualTo(TraceArtifact.Status.DONE);
    }
}
