package io.openchain.app.demo;

import io.openchain.alerting.alert.AlertService;
import io.openchain.app.chain.EventHistory;
import io.openchain.intelligence.IntelligencePipeline;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Demo-only beans (OCI_DEMO_MODE / demo profile). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "oci.demo", name = "enabled", havingValue = "true")
public class DemoConfiguration {

    @Bean DemoNotificationSink demoNotificationSink(Clock clock) { return new DemoNotificationSink(clock); }

    @Bean DemoScenarios demoScenarios(IntelligencePipeline intelligence, AlertService alerts, EventHistory history, DemoNotificationSink sink) {
        return new DemoScenarios(intelligence, alerts, history, sink);
    }
}
