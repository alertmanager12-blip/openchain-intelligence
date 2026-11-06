package io.openchain.app.api;

import io.openchain.app.chain.FreezeBackfillService;
import io.openchain.app.security.ApiKeyService;
import io.openchain.core.model.Network;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational freeze backfill (permission {@code keys:admin}, see SecurityConfiguration {@code /api/v1/admin/**}).
 * {@code POST} queues a run (asynchronous, 202) for one network or for every configured one; a backfill already queued
 * or running is not started twice. {@code GET} returns the progress.
 */
@RestController
@RequestMapping("/api/v1/admin/backfill/freezes")
public class BackfillController {

    private final FreezeBackfillService backfills;

    public BackfillController(FreezeBackfillService backfills) { this.backfills = backfills; }

    @GetMapping
    public Map<String, Object> list() { return Map.of("items", backfills.statuses()); }

    @PostMapping
    public ResponseEntity<Map<String, Object>> trigger(@RequestParam(name = "network", required = false) String network, Authentication auth) {
        List<FreezeBackfillService.Status> out;
        if (network != null && !network.isBlank()) {
            Network n;
            try {
                n = Network.fromId(network.strip());
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("unknown network", List.of(new ApiException.FieldError("network", "invalid_value",
                        "unknown network '" + network + "'")));
            }
            if (!backfills.registered(n)) {
                throw ApiException.notFound("no freeze backfill configured for " + n.id()
                        + " (needs a real provider and oci.chains." + n.id() + ".freeze-backfill.from-block)");
            }
            out = List.of(backfills.trigger(n));
        } else {
            out = backfills.statuses().stream().map(s -> backfills.trigger(Network.fromId(s.network()))).toList();
            if (out.isEmpty()) throw ApiException.conflict("no freeze backfill is configured (oci.chains.<network>.freeze-backfill.from-block)");
        }
        ApiKeyService.AUDIT.info("backfill.freezes.trigger networks={} actor={}", out.stream().map(FreezeBackfillService.Status::network).toList(),
                auth.getName());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("items", out));
    }
}
