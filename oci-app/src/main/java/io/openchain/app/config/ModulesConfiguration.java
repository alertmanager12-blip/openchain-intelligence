package io.openchain.app.config;

import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.EscalationPolicy;
import io.openchain.alerting.alert.InMemoryAlertRepository;
import io.openchain.alerting.engine.EngineConfig;
import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.notify.ConnectorRegistry;
import io.openchain.alerting.notify.http.OutboundHttp;
import io.openchain.alerting.notify.http.SafeHttpClient;
import io.openchain.alerting.notify.http.SsrfGuard;
import io.openchain.alerting.notify.delivery.BackoffPolicy;
import io.openchain.alerting.notify.delivery.DeliveryService;
import io.openchain.alerting.notify.delivery.InMemoryDeliveryStore;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.alerting.rule.FieldCatalog;
import io.openchain.alerting.rule.RuleLimits;
import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.compile.RuleCompiler;
import io.openchain.alerting.testing.RuleTester;
import io.openchain.app.chain.EventHistory;
import io.openchain.app.labels.LabelCatalog;
import io.openchain.blockchain.token.DefaultTokenRegistry;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.detection.DetectionEngine;
import io.openchain.intelligence.detection.InMemorySignalRepository;
import io.openchain.intelligence.flow.InMemoryFlowStore;
import io.openchain.intelligence.profile.WalletProfileService;
import io.openchain.intelligence.risk.InMemoryRiskScoreRepository;
import io.openchain.intelligence.risk.RiskScoringEngine;
import io.openchain.intelligence.risk.RiskScoringService;
import io.openchain.intelligence.risk.ScoringConfig;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the domain modules (they are plain Java, no Spring inside, ADR-0001). Everything is in memory for now.
 * TODO(persistence): swap the InMemory* stores for PostgreSQL adapters (Flyway migrations, outbox table).
 */
@Configuration(proxyBeanMethods = false)
public class ModulesConfiguration {

    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean TokenRegistry tokenRegistry() { return new DefaultTokenRegistry(); }

    @Bean LabelCatalog labelCatalog() { return new LabelCatalog(); }

    @Bean EventHistory eventHistory(OciProperties props) { return new EventHistory(props.getIngestion().getEventHistorySize()); }

    @Bean
    IntelligencePipeline intelligencePipeline(LabelCatalog labels, Clock clock) {
        var flows = new InMemoryFlowStore();
        var signals = new InMemorySignalRepository();
        var scores = new InMemoryRiskScoreRepository();
        var profiles = new WalletProfileService(clock, 10, scores::history);
        var scoring = new RiskScoringService(new RiskScoringEngine(ScoringConfig.defaults()), labels, signals,
                profiles::profileOrEmpty, scores, clock);
        return new IntelligencePipeline(flows, profiles, labels, signals, DetectionEngine.withDefaultDetectors(signals),
                scoring, DetectionConfig.defaults(), clock);
    }

    @Bean FieldCatalog fieldCatalog() { return FieldCatalog.defaults(); }

    @Bean RuleLimits ruleLimits() { return RuleLimits.defaults(); }

    @Bean RuleLoader ruleLoader(FieldCatalog catalog, RuleLimits limits) { return new RuleLoader(catalog, limits); }

    @Bean RuleCompiler ruleCompiler(FieldCatalog catalog, RuleLimits limits) { return new RuleCompiler(catalog, limits); }

    @Bean RuleTester ruleTester(RuleLoader loader) { return new RuleTester(loader); }

    @Bean RuleEngine ruleEngine(FieldCatalog catalog, Clock clock) {
        return new RuleEngine(EngineConfig.defaults().withClock(clock), catalog);
    }

    @Bean AlertService alertService(Clock clock) {
        return new AlertService(new InMemoryAlertRepository(), clock, EscalationPolicy.defaults(), () -> UUID.randomUUID().toString());
    }

    @Bean ConnectorRegistry connectorRegistry() { return new ConnectorRegistry(); }

    /** Outbound HTTP for webhooks: SSRF-guarded (https only, public addresses only, no redirects). */
    @Bean OutboundHttp outboundHttp() { return new SafeHttpClient(new SsrfGuard()); }

    @Bean DeliveryService deliveryService(ConnectorRegistry connectors, Clock clock) {
        return new DeliveryService(new InMemoryDeliveryStore(), connectors, BackoffPolicy.defaults(), clock);
    }

    @Bean AlertingPipeline alertingPipeline(RuleEngine engine, AlertService alerts, DeliveryService delivery) {
        return new AlertingPipeline(engine, alerts, delivery);
    }
}
