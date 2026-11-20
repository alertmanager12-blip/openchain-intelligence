package io.openchain.app.api;

import io.openchain.app.webhooks.WebhookRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
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

    /**
     * {@code url} (doc) or {@code target} (SPA). Types: WEBHOOK (default), SLACK, DISCORD (https incoming-webhook URL),
     * TELEGRAM (chat id; the bot token is server configuration). EMAIL is not available: no mail transport yet.
     */
    public record CreateRequest(String type, String name, String url, String target, List<String> events) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dto.WebhookDto create(@RequestBody CreateRequest req, Authentication auth) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        String type = req.type() == null || req.type().isBlank() ? "WEBHOOK" : req.type().trim().toUpperCase(java.util.Locale.ROOT);
        if (!WebhookRegistry.TYPES.contains(type)) {
            errors.add("EMAIL".equals(type) ? ApiException.FieldError.of("/type", ApiCode.FIELD_EMAIL_UNAVAILABLE, Map.of())
                    : ApiException.FieldError.of("/type", ApiCode.FIELD_UNSUPPORTED_TYPE, Map.of("types", String.join(", ", WebhookRegistry.TYPES))));
        } else if (type.equals("TELEGRAM") && !webhooks.telegramConfigured()) {
            errors.add(ApiException.FieldError.of("/type", ApiCode.FIELD_NOT_CONFIGURED, Map.of()));
        }
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty()) errors.add(ApiException.FieldError.of("/name", ApiCode.FIELD_REQUIRED, Map.of("field", "name"), "name is required (max 100 chars)"));
        else if (name.length() > 100) errors.add(ApiException.FieldError.of("/name", ApiCode.FIELD_TOO_LONG, Map.of("field", "name", "max", 100), "name is required (max 100 chars)"));
        String raw = req.url() != null ? req.url() : req.target();
        String ptr = req.url() != null ? "/url" : "/target";
        String target = raw == null ? "" : raw.strip();
        if (target.isEmpty()) {
            errors.add(ApiException.FieldError.of(ptr, ApiCode.FIELD_REQUIRED, Map.of("field", type.equals("TELEGRAM") ? "chat id" : "url")));
        } else if (!type.equals("TELEGRAM")) {
            try {
                URI uri = new URI(target);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                    errors.add(ApiException.FieldError.of(ptr, ApiCode.FIELD_INVALID_URL, Map.of()));
            } catch (URISyntaxException e) {
                errors.add(ApiException.FieldError.of(ptr, ApiCode.FIELD_MALFORMED_URL, Map.of()));
            }
        }
        List<String> events = req.events() == null ? List.of() : req.events();
        for (int i = 0; i < events.size(); i++) {
            if (!WebhookRegistry.EVENTS.contains(events.get(i)))
                errors.add(ApiException.FieldError.of("/events/" + i, ApiCode.FIELD_UNKNOWN_EVENT, Map.of("event", String.valueOf(events.get(i)))));
        }
        if (!errors.isEmpty()) throw ApiException.validation(ApiCode.CONNECTOR_INVALID, errors);
        WebhookRegistry.Created c;
        try {
            c = webhooks.create(type, name, target, events, auth.getName());
        } catch (IllegalArgumentException e) {
            // the connector's own target validation (e.g. Slack host, Telegram chat id format)
            throw ApiException.validation(ApiCode.CONNECTOR_INVALID, List.of(ApiException.FieldError.of(ptr, ApiCode.FIELD_INVALID_TARGET,
                    Map.of("reason", String.valueOf(e.getMessage())))));
        }
        return dto(c.webhook(), c.secret());
    }

    @GetMapping
    public List<Dto.WebhookDto> list() { return webhooks.list().stream().map(w -> dto(w, null)).toList(); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication auth) {
        if (!webhooks.delete(id, auth.getName())) throw ApiException.notFound(ApiCode.WEBHOOK_NOT_FOUND, Map.of("id", id));
        return ResponseEntity.noContent().build();
    }

    /** Sends a signed test notification through the real connector: {@code {delivered, statusCode, message}}. */
    @PostMapping("/{id}/test")
    public WebhookRegistry.TestResult test(@PathVariable String id, Authentication auth) {
        webhooks.find(id).orElseThrow(() -> ApiException.notFound(ApiCode.WEBHOOK_NOT_FOUND, Map.of("id", id)));
        return webhooks.test(id, auth.getName());
    }

    private static Dto.WebhookDto dto(WebhookRegistry.Webhook w, String secret) {
        // targets are masked everywhere (Slack/Discord URL paths and Telegram chat ids are credentials-like)
        String shown = WebhookRegistry.masked(w);
        return new Dto.WebhookDto(w.id(), w.type(), w.name(), shown, shown, w.events(), w.enabled(), w.createdAt(),
                w.lastDeliveryAt(), w.lastDeliveryStatus(), secret);
    }
}
