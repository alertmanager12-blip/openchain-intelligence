package io.openchain.app.api;

import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.profile.OrphanKeyReport;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 3 diagnostic (permission {@code keys:admin}, see SecurityConfiguration {@code /api/v1/admin/**}): dedup keys kept
 * by wallet profiles after their flow left the flow store, and the instance-sharing statistics. Read-only, computed only
 * when requested (no periodic cost); see docs/KEY_COMPACTION_IMPLEMENTATION_REPORT.md for the method and its limits.
 */
@RestController
@RequestMapping("/api/v1/admin/working-set/profile-keys")
public class ProfileKeyDiagnosticController {
    private final IntelligencePipeline intelligence;

    public ProfileKeyDiagnosticController(IntelligencePipeline intelligence) { this.intelligence = intelligence; }

    @GetMapping
    public Map<String, Object> report() {
        OrphanKeyReport r = intelligence.profiles().orphanKeys(intelligence.flows()::contains, 63);
        return Map.of("orphanKeys", r, "canonicalInstances", intelligence.canonicalInstances().stats());
    }
}
