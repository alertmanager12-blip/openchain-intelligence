package io.openchain.app.demo;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo mode only: fictitious scenarios A-F and the evidence the real pipeline produced for them. */
@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnProperty(prefix = "oci.demo", name = "enabled", havingValue = "true")
public class DemoController {

    private final DemoScenarios scenarios;
    private final DemoNotificationSink sink;

    public DemoController(DemoScenarios scenarios, DemoNotificationSink sink) {
        this.scenarios = scenarios;
        this.sink = sink;
    }

    @GetMapping("/scenarios")
    public Map<String, Object> scenarios() {
        return Map.of("fictitious", true, "items", scenarios.evidence());
    }

    /** Notifications captured by the demo sink (nothing is sent anywhere), newest first. */
    @GetMapping("/notifications")
    public List<DemoNotificationSink.Received> notifications() { return sink.received().stream().limit(200).toList(); }
}
