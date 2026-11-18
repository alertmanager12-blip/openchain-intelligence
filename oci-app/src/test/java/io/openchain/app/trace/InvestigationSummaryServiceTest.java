package io.openchain.app.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.app.config.OciProperties;
import io.openchain.app.summary.SummaryWriter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ADR-0022 amendment 5: off means nothing is sent; a misconfigured provider is left out, never half-configured. */
class InvestigationSummaryServiceTest {
    @Test void whenTheFeatureIsOffNothingIsSentAndNoProviderIsOffered() {
        AtomicInteger calls = new AtomicInteger();
        SummaryWriter w = (s, u) -> { calls.incrementAndGet(); return SummaryWriter.Draft.failed("m", "x"); };
        var svc = new InvestigationSummaryService(new InMemoryTraceArtifactStore(), new InMemoryInvestigationSummaryStore(),
                List.of(new InvestigationSummaryService.Provider("p", "P", "claude", "m", null, w)), false, 20, Clock.systemUTC());
        assertThat(svc.providers()).isEmpty();
        assertThatThrownBy(() -> svc.request("default", "inv", "p", "a")).isInstanceOf(InvestigationSummaryService.Disabled.class);
        assertThat(calls).hasValue(0);
    }

    @Test void aMisconfiguredProviderIsRefused() {
        var p = new OciProperties.AiSummary.Provider();
        p.setId("claude");
        assertThatThrownBy(() -> TraceConfiguration.provider(p, Duration.ofSeconds(5))).hasMessageContaining("api key");
        p.setConnector("openai-compatible");
        p.setModel("gpt-x");
        p.setBaseUrl("http://api.example.com/v1");
        assertThatThrownBy(() -> TraceConfiguration.provider(p, Duration.ofSeconds(5))).hasMessageContaining("https");
        p.setConnector("other");
        assertThatThrownBy(() -> TraceConfiguration.provider(p, Duration.ofSeconds(5))).hasMessageContaining("connector");
        p.setConnector("openai-compatible");
        p.setBaseUrl("http://localhost:11434/v1");
        assertThat(TraceConfiguration.provider(p, Duration.ofSeconds(5)).id()).isEqualTo("claude");
        p.setId("Bad Id!");
        assertThatThrownBy(() -> TraceConfiguration.provider(p, Duration.ofSeconds(5))).hasMessageContaining("slug");
    }
}
