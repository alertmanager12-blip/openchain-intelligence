package io.openchain.app.api;

import io.openchain.app.chain.ExemptWorkingSetMonitor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-address measurement of the addresses exempt from the working-set trim (permission {@code keys:admin}, see
 * SecurityConfiguration {@code /api/v1/admin/**}). Observation only (docs/EXEMPT_ADDRESSES_ANALYSIS.md, step 2 point 1):
 * a fresh snapshot per request; nothing is changed.
 */
@RestController
@RequestMapping("/api/v1/admin/working-set/exempt")
public class ExemptWorkingSetController {
    private final ExemptWorkingSetMonitor monitor;

    public ExemptWorkingSetController(ExemptWorkingSetMonitor monitor) { this.monitor = monitor; }

    @GetMapping
    public ExemptWorkingSetMonitor.Snapshot snapshot() { return monitor.refresh(); }
}
