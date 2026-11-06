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
import java.util.Map;
import java.util.regex.Pattern;

/** Telegram Bot API sendMessage (plain text, no parse_mode, so alert data cannot inject markup). */
public final class TelegramConnector implements NotificationConnector {

    private static final Pattern TOKEN = Pattern.compile("^[0-9]{3,20}:[A-Za-z0-9_-]{20,64}$");
    private static final Pattern CHAT = Pattern.compile("^(-?[0-9]{1,20}|@[A-Za-z0-9_]{5,64})$");

    private final String id;
    private final URI endpoint;
    private final String chatId;
    private final OutboundHttp http;

    public TelegramConnector(String id, String botToken, String chatId, OutboundHttp http) {
        if (botToken == null || !TOKEN.matcher(botToken).matches()) throw new IllegalArgumentException("invalid Telegram bot token format");
        if (chatId == null || !CHAT.matcher(chatId).matches()) throw new IllegalArgumentException("invalid Telegram chat id");
        this.id = id;
        this.endpoint = URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage");
        this.chatId = chatId;
        this.http = http;
    }

    @Override public String id() { return id; }
    @Override public ActionType type() { return ActionType.TELEGRAM; }

    @Override public DeliveryResult send(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chat_id", chatId);
        m.put("text", NotificationFormatter.text(n, 4000));
        m.put("disable_web_page_preview", true);
        byte[] body = HttpConnectorSupport.json(m).getBytes(StandardCharsets.UTF_8);
        DeliveryResult r = HttpConnectorSupport.post(http, endpoint, Map.of("Content-Type", "application/json"), body);
        // never leak the bot token (part of the URL) in diagnostics
        return new DeliveryResult(r.success(), r.retryable(), r.statusCode(), r.detail().replace(endpoint.toString(), "<telegram-endpoint>"));
    }
}
