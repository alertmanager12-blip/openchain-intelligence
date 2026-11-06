package io.openchain.app.api;

import io.openchain.app.alerts.AlertFacade;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Alerts: list/detail (alerts:read), acknowledge/status/assign/comment (alerts:write). Illegal transitions: 409. */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertFacade alerts;

    public AlertController(AlertFacade alerts) { this.alerts = alerts; }

    @GetMapping
    public CursorPage<Dto.AlertDto> list(@RequestParam(required = false) String status, @RequestParam(required = false) String severity,
                                         @RequestParam(required = false) String ruleId, @RequestParam(required = false) String address,
                                         @RequestParam(required = false) String network, @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to, @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        var f = new AlertFacade.Filter(status, severity, ruleId, address, network,
                InvestigationController.instant(from, "from"), InvestigationController.instant(to, "to"));
        return CursorPage.of(alerts.list(f), cursor, limit);
    }

    @GetMapping("/{id}")
    public Dto.AlertDto get(@PathVariable String id) { return alerts.dto(alerts.get(id)); }

    @PostMapping("/{id}/acknowledge")
    public Dto.AlertDto acknowledge(@PathVariable String id, Authentication auth) { return alerts.acknowledge(id, auth.getName()); }

    public record StatusRequest(String status, String reason) {}

    @PostMapping("/{id}/status")
    public Dto.AlertDto status(@PathVariable String id, @RequestBody StatusRequest req, Authentication auth) {
        alerts.get(id);
        return alerts.moveTo(id, AlertFacade.parseTarget(req.status()), auth.getName(), req.reason());
    }

    /** Accepts {@code assigneeId} (doc) or {@code assignee} (SPA); null unassigns. */
    @PostMapping("/{id}/assign")
    public Dto.AlertDto assign(@PathVariable String id, @RequestBody Map<String, Object> body, Authentication auth) {
        Object v = body.containsKey("assigneeId") ? body.get("assigneeId") : body.get("assignee");
        return alerts.assign(id, v == null ? null : v.toString(), auth.getName());
    }

    public record CommentRequest(String body) {}

    @PostMapping("/{id}/comments")
    public Dto.AlertDto comment(@PathVariable String id, @RequestBody CommentRequest req, Authentication auth) {
        return alerts.comment(id, req.body(), auth.getName());
    }
}
