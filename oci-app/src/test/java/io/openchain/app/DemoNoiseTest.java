package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.demo.DemoScenarios;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The demo must look like a credible monitoring console: with the production demo settings (TRON + Ethereum, 60 minutes
 * of seeded history) plus one hour of live mining, routine simulated traffic produces tens of open alerts spread across
 * rules, not hundreds of "wallet drained" alerts, and every scripted scenario (A-F) is still fully evidenced.
 */
class DemoNoiseTest {

    @Test void routineDemoTrafficProducesACredibleAlertMix() {
        String[] args = {"--oci.demo.networks=tron,ethereum", "--oci.demo.seed-minutes=60", "--oci.demo.live=false", "--server.port=0"};
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class)
                .profiles("test").run(args)) {
            ChainIngestionService chains = ctx.getBean(ChainIngestionService.class);
            Map<String, Long> atStartup = openByRule(ctx);
            // one hour of live mining, as on a running demo stack
            for (ChainIngestionService.NetworkRuntime rt : chains.runtimes()) {
                var sim = rt.simulator().orElseThrow();
                long blocks = Duration.ofHours(1).toSeconds() / rt.blockTime().toSeconds();
                for (long i = 0; i < blocks; i++) {
                    sim.mine(1);
                    if (i % 20 == 0) chains.poll(rt);
                }
                chains.poll(rt);
            }
            Map<String, Long> afterHour = openByRule(ctx);
            long total = afterHour.values().stream().mapToLong(Long::longValue).sum();
            System.out.println("DEMO OPEN ALERTS AT STARTUP: " + atStartup + " total=" + atStartup.values().stream().mapToLong(Long::longValue).sum());
            System.out.println("DEMO OPEN ALERTS AFTER 1H LIVE: " + afterHour + " total=" + total);

            // samples of the readable wording (printed for the change report)
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            var facade = ctx.getBean(io.openchain.app.alerts.AlertFacade.class);
            ctx.getBean(AlertService.class).repository().findAll().stream().filter(a -> a.ruleId().equals("wallet-draining")).findFirst()
                    .ifPresent(a -> System.out.println("SAMPLE ALERT: " + json.writeValueAsString(facade.dto(a))));
            var investigation = ctx.getBean(io.openchain.app.analysis.InvestigationService.class);
            chains.recentSignals().stream().filter(x -> x.type() == io.openchain.core.detection.SignalType.RAPID_DRAIN
                            || x.type() == io.openchain.core.detection.SignalType.SANCTIONED_EXPOSURE).limit(2)
                    .forEach(x -> System.out.println("SAMPLE RISK: " + json.writeValueAsString(investigation.risk(x.address(), false))));

            var scenarios = ctx.getBean(DemoScenarios.class).evidence();
            assertThat(scenarios).hasSize(6);
            assertThat(scenarios.toString()).doesNotContain("=false");

            assertThat(total).as("open alerts").isBetween(10L, 80L);
            assertThat(afterHour.getOrDefault("wallet-draining", 0L)).as("wallet-draining alerts").isLessThanOrEqualTo(total / 2);
            assertThat(afterHour.size()).as("rules with open alerts").isGreaterThanOrEqualTo(4);
        }
    }

    static Map<String, Long> openByRule(ConfigurableApplicationContext ctx) {
        var open = EnumSet.of(AlertStatus.DETECTED, AlertStatus.PENDING, AlertStatus.NOTIFIED);
        return ctx.getBean(AlertService.class).repository().findAll().stream().filter(a -> open.contains(a.status()))
                .collect(Collectors.groupingBy(Alert::ruleId, TreeMap::new, Collectors.counting()));
    }
}
