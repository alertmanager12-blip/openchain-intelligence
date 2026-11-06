package io.openchain.app.api;

import io.openchain.app.webhooks.WebhookRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Webhook connectors (webhooks:write). The signing secret is returned once, in the creation response only. */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final WebhookRegistry webhooks;

    public WebhookController(WebhookRegistry webhooks) { this.webhooks = webhooks; }

    /** {@code url} (doc) or {@code target} (SPA). Only type WEBHOOK is supported in this version. */
    public record CreateRequest(String type, String name, String url, String target, List<String> events) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dto.WebhookDto create(@RequestBody CreateRequest req, Authentication auth) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        String type = req.type() == null || req.type().isBlank() ? "WEBHOOK" : req.type().trim().toUpperCase();
        if (!type.equals("WEBHOOK")) errors.add(new ApiException.FieldError("/type", "unsupported", "only WEBHOOK connectors are supported"));
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty() || name.length() > 100) errors.add(new ApiException.FieldError("/name", "required", "name is required (max 100 chars)"));
        String raw = req.url() != null ? req.url() : req.target();
        URI uri = null;
        String ptr = req.url() != null ? "/url" : "/target";
        if (raw == null || raw.isBlank()) {
            errors.add(new ApiException.FieldError(ptr, "required", "url is required"));
        } else {
            try {
                uri = new URI(raw.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                    errors.add(new ApiException.FieldError(ptr, "invalid_url", "must be an https URL without credentials"));
            } catch (URISyntaxException e) {
                errors.add(new ApiException.FieldError(ptr, "invalid_url", "malformed URL"));
            }
        }
        List<String> events = req.events() == null ? List.of() : req.events();
        for (int i = 0; i < events.size(); i++) {
            if (!WebhookRegistry.EVENTS.contains(events.get(i)))
                errors.add(new ApiException.FieldError("/events/" + i, "unknown_event", "unknown event " + events.get(i)));
        }
        if (!errors.isEmpty()) throw ApiException.validation("webhook is invalid", errors);
        WebhookRegistry.Created c = webhooks.create(name, uri, events, auth.getName());
        return dto(c.webhook(), c.secret());
    }

    @GetMapping
    public List<Dto.WebhookDto> list() { return webhooks.list().stream().map(w -> dto(w, null)).toList(); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication auth) {
        if (!webhooks.delete(id, auth.getName())) throw ApiException.notFound("webhook " + id + " does not exist");
        return ResponseEntity.noContent().build();
    }

    /** Sends a signed test notification through the real connector: {@code {delivered, statusCode, message}}. */
    @PostMapping("/{id}/test")
    public WebhookRegistry.TestResult test(@PathVariable String id, Authentication auth) {
        webhooks.find(id).orElseThrow(() -> ApiException.notFound("webhook " + id + " does not exist"));
        return webhooks.test(id, auth.getName());
    }

    private static Dto.WebhookDto dto(WebhookRegistry.Webhook w, String secret) {
        return new Dto.WebhookDto(w.id(), w.type(), w.name(), w.url(), w.url(), w.events(), w.enabled(), w.createdAt(),
                w.lastDeliveryAt(), w.lastDeliveryStatus(), secret);
    }
}
