package io.openchain.alerting.notify.connector;

import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.notify.NotificationFormatter;
import io.openchain.alerting.notify.http.OutboundHttp;
import io.openchain.alerting.rule.ActionType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Discord webhook (discord.com / discordapp.com). Mentions are disabled via {@code allowed_mentions}. */
public final class DiscordConnector implements NotificationConnector {

    private final String id;
    private final URI webhook;
    private final OutboundHttp http;

    public DiscordConnector(String id, String webhookUrl, OutboundHttp http) {
        this.id = id;
        this.webhook = HttpConnectorSupport.requireHttpsUri(webhookUrl, "discord.com", "discordapp.com");
        if (!webhook.getPath().startsWith("/api/webhooks/")) throw new IllegalArgumentException("not a Discord webhook URL");
        this.http = http;
    }

    @Override public String id() { return id; }
    @Override public ActionType type() { return ActionType.DISCORD; }

    @Override public DeliveryResult send(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", NotificationFormatter.text(n, 1900));
        m.put("allowed_mentions", Map.of("parse", List.of()));
        byte[] body = HttpConnectorSupport.json(m).getBytes(StandardCharsets.UTF_8);
        return HttpConnectorSupport.post(http, webhook, Map.of("Content-Type", "application/json"), body);
    }
}
