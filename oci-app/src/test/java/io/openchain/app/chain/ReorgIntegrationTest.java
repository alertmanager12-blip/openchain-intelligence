package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.app.ApiTestSupport;
import io.openchain.app.OciApplication;
import io.openchain.app.labels.LabelCatalog;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * P0 item 2 (docs/P0_REORG_DESIGN.md), end to end through the indexer sink: a transfer from a sanctioned address raises an
 * exposure signal and an alert (shipped rule sanctioned-exposure-signal); its block leaves the canonical chain; the same
 * transaction is then re-included in another block. SYNTHETIC addresses.
 */
class ReorgIntegrationTest {
    static ConfigurableApplicationContext ctx;

    @BeforeAll static void start() {
        ctx = new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class).profiles("test")
                .run("--server.port=0", "--oci.demo.networks=tron");
    }

    @AfterAll static void stop() { if (ctx != null) ctx.close(); }

    static final String USDT = "0xc2132d05d31c914a87c6611c10748aeb04b58e8f";

    static ChainEvent transfer(String id, String tx, Address from, Address to, String raw, Instant at, boolean removed) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("transaction.hash", tx);
        f.put("transaction.log_index", 0);
        f.put("transaction.from", from.value());
        f.put("transaction.to", to.value());
        f.put("transaction.amount_raw", raw);
        f.put("transaction.amount", new java.math.BigDecimal(raw).movePointLeft(6));
        f.put("transaction.token.contract", USDT);
        f.put("transaction.token.symbol", "USDT");
        f.put("transaction.token.decimals", 6);
        f.put("transaction.token.known", true);
        return new ChainEvent(id, ChainEvent.EventType.TOKEN_TRANSFER, Network.POLYGON, 100, removed ? "0xorphan" : "0xblock", at, at, removed, f);
    }

    @Test void reorgRevertsTheIntelligenceAndQualifiesTheAlertThenReinclusionRestoresIt() {
        ChainIngestionService chains = ctx.getBean(ChainIngestionService.class);
        IntelligencePipeline intel = ctx.getBean(IntelligencePipeline.class);
        AlertService alerts = ctx.getBean(AlertService.class);
        Address sanctioned = new Address(Network.POLYGON, "0x" + "5a".repeat(20)), victim = new Address(Network.POLYGON, "0x" + "5b".repeat(20));
        Instant at = Instant.now().minusSeconds(600);
        ctx.getBean(LabelCatalog.class).save(new AddressLabel(sanctioned, LabelCategory.SANCTIONED, "SYNTHETIC sanctioned", "synthetic-list",
                1.0, at.minusSeconds(86400), Signal.EvidenceLevel.CONFIRMED_BY_SOURCE, "synthetic://list"));
        ChainEvent in = transfer("polygon:0xreorg1:0", "0xreorg1", sanctioned, victim, "10000000000", at, false);

        chains.onEvent(in);
        Signal exposure = intel.signals().findByAddress(victim).stream().filter(s -> s.type() == SignalType.SANCTIONED_EXPOSURE)
                .findFirst().orElseThrow();
        Alert alert = alerts.repository().findByMatchedEventId("signal:" + exposure.id()).stream().findFirst().orElseThrow();
        assertThat(alert.evidenceState()).isEqualTo(Alert.EvidenceState.VALID);
        int scoreBefore = intel.scoring().latest(victim).orElseThrow().score();
        long withdrawnBefore = chains.reorgSignalsRemoved();

        // the block leaves the canonical chain
        chains.onEvent(transfer(in.eventId(), "0xreorg1", sanctioned, victim, "10000000000", at, true));

        assertThat(intel.flows().contains(in.eventId())).isFalse();
        assertThat(intel.signals().findById(exposure.id())).isEmpty();
        assertThat(intel.scoring().latest(victim).orElseThrow().score()).isLessThan(scoreBefore);
        assertThat(chains.recentSignals()).noneMatch(s -> s.id().equals(exposure.id()));
        assertThat(chains.reorgSignalsRemoved()).isEqualTo(withdrawnBefore + 1);
        Alert withdrawn = alerts.find(alert.id()).orElseThrow();
        assertThat(withdrawn.evidenceState()).isEqualTo(Alert.EvidenceState.WITHDRAWN);
        assertThat(withdrawn.status()).isEqualTo(alert.status());

        // a repeated notice changes nothing
        long reverted = chains.reorgRevertedFlows();
        chains.onEvent(transfer(in.eventId(), "0xreorg1", sanctioned, victim, "10000000000", at, true));
        assertThat(chains.reorgRevertedFlows()).isEqualTo(reverted);
        assertThat(alerts.find(alert.id()).orElseThrow().history()).hasSize(withdrawn.history().size());

        // the same transaction in another canonical block
        chains.onEvent(in);
        assertThat(intel.flows().contains(in.eventId())).isTrue();
        assertThat(intel.profiles().profile(victim).orElseThrow().inboundTransfers()).isEqualTo(1); // counted once
        assertThat(intel.signals().findById(exposure.id())).as("same signal id").isPresent();
        Alert back = alerts.find(alert.id()).orElseThrow();
        assertThat(back.evidenceState()).isEqualTo(Alert.EvidenceState.VALID);
        assertThat(alerts.repository().findByMatchedEventId("signal:" + exposure.id())).as("no second alert").hasSize(1);
        assertThat(List.of(back.history().getLast().reason())).anyMatch(r -> r.contains("re-included"));
    }
}
