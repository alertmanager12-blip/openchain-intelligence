package io.openchain.alerting.notify.connector;

import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.notify.NotificationFormatter;
import io.openchain.alerting.notify.http.OutboundHttp;
import io.openchain.alerting.rule.ActionType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Slack incoming webhook (host locked to hooks.slack.com). Text is escaped so alert data cannot inject mentions/links. */
public final class SlackConnector implements NotificationConnector {

    private final String id;
    private final URI webhook;
    private final OutboundHttp http;

    public SlackConnector(String id, String webhookUrl, OutboundHttp http) {
        this.id = id;
        this.webhook = HttpConnectorSupport.requireHttpsUri(webhookUrl, "hooks.slack.com");
        this.http = http;
    }

    @Override public String id() { return id; }
    @Override public ActionType type() { return ActionType.SLACK; }

    @Override public DeliveryResult send(Notification n) {
        String text = escape(NotificationFormatter.text(n, 3000));
        byte[] body = HttpConnectorSupport.json(Map.of("text", text)).getBytes(StandardCharsets.UTF_8);
        return HttpConnectorSupport.post(http, webhook, Map.of("Content-Type", "application/json"), body);
    }

    /** Slack control characters: &, <, > (prevents <!channel>, <@U..>, <url|label> injection). */
    static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
