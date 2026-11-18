package io.openchain.app.api;

import io.openchain.app.trace.InvestigationSummaryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The AI providers an analyst may choose for a summary (ADR-0022 amendment 5); never their keys or URLs. */
@RestController
@RequestMapping("/api/v1/ai-summary")
public class AiSummaryController {
    private final InvestigationSummaryService summaries;

    public AiSummaryController(InvestigationSummaryService summaries) { this.summaries = summaries; }

    /** {@code dataLocation}: where the assertions go when this provider is chosen. Empty when the feature is off. */
    public record ProviderDto(String id, String label, String connector, String model, String dataLocation) {}

    @GetMapping("/providers")
    public List<ProviderDto> providers() {
        return summaries.providers().stream().map(p -> new ProviderDto(p.id(), p.label(), p.connector(), p.model(), p.dataLocation())).toList();
    }
}
