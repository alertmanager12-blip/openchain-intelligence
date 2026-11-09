package io.openchain.alerting.notify;

import static io.openchain.alerting.Fx.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.notify.connector.DiscordConnector;
import io.openchain.alerting.notify.connector.EmailConnector;
import io.openchain.alerting.notify.connector.InternalEventConnector;
import io.openchain.alerting.notify.connector.SlackConnector;
import io.openchain.alerting.notify.connector.TelegramConnector;
import io.openchain.alerting.notify.connector.WebhookConnector;
import io.openchain.alerting.notify.http.OutboundHttp;
import io.openchain.alerting.notify.http.SsrfException;
import io.openchain.alerting.rule.ActionType;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Network;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConnectorsTest {

    record Call(URI uri, Map<String, String> headers, byte[] body) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    /** Recording fake transport returning a fixed status. */
    static final class FakeHttp implements OutboundHttp {
        final List<Call> calls = new ArrayList<>();
        int status = 200;
        IOException fail;
        @Override public HttpResult post(URI uri, Map<String, String> headers, byte[] body) throws IOException {
            calls.add(new Call(uri, headers, body));
            if (fail != null) throw fail;
            return new HttpResult(status, Map.of(), new byte[0], false);
        }
    }

    static final Clock CLOCK = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
    static final byte[] SECRET = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t".getBytes(StandardCharsets.UTF_8);

    static Notification notification(ActionType type, String connector) {
        Alert a = Alert.builder().id("alert-1").ruleId("fund-splitting").ruleVersion("1.0.0").title("Fund <!channel> splitting")
                .severity(Severity.HIGH).priority(Priority.P2).evidenceLevel(EvidenceLevel.ANOMALY).network(Network.TRON)
                .groupKey("tron|TA").dedupKey("fund-splitting:tron|TA").firstSeen(T0).lastSeen(T0.plusSeconds(60))
                .occurrenceCount(3).addEventIds(List.of("e1", "e2")).createdAt(T0).updatedAt(T0)
                .evidence(Map.of("kind", "AGGREGATION", "function", "DISTINCT_COUNT", "field", "transaction.to", "value", "11",
                        "operator", "GREATER_THAN", "threshold", "10", "window", "PT3M"))
                .build();
        return Notification.of("delivery-42", type, connector, Map.of(), a, T0);
    }

    @Test void webhookSendsSignedIdempotentJson() {
        FakeHttp http = new FakeHttp();
        WebhookConnector c = new WebhookConnector("soc", URI.create("https://hooks.example.com/oc"), SECRET, http, CLOCK);
        assertThat(c.send(notification(ActionType.WEBHOOK, "soc")).success()).isTrue();
        Call call = http.calls.getFirst();
        assertThat(call.headers()).containsEntry(WebhookSignature.IDEMPOTENCY_HEADER, "delivery-42")
                .containsEntry("Content-Type", "application/json");
        String sig = call.headers().get(WebhookSignature.HEADER);
        assertThat(sig).startsWith("t=" + CLOCK.instant().getEpochSecond() + ",v1=");
        assertThat(WebhookSignature.verify(SECRET, sig, call.body(), CLOCK.instant(), Duration.ofMinutes(5))).isTrue();
        JsonNode json = JsonMapper.builder().build().readTree(call.text());
        assertThat(json.get("alertId").asString()).isEqualTo("alert-1");
        assertThat(json.get("rule").get("version").asString()).isEqualTo("1.0.0");
        assertThat(json.get("matchedEventIds").size()).isEqualTo(2);
        assertThat(json.get("disclaimer").asString()).contains("not an accusation");
        // deterministic body: retries are byte-identical
        c.send(notification(ActionType.WEBHOOK, "soc"));
        assertThat(http.calls.get(1).body()).isEqualTo(call.body());
    }

    @Test void webhookRequiresStrongSecretAndMapsFailures() {
        assertThatThrownBy(() -> new WebhookConnector("w", URI.create("https://x.example.com"), "short".getBytes(), new FakeHttp(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        FakeHttp http = new FakeHttp();
        WebhookConnector c = new WebhookConnector("w", URI.create("https://hooks.example.com/oc"), SECRET, http, CLOCK);
        http.status = 503;
        assertThat(c.send(notification(ActionType.WEBHOOK, "w"))).satisfies(r -> { assertThat(r.retryable()).isTrue(); assertThat(r.statusCode()).isEqualTo(503); });
        http.status = 429;
        assertThat(c.send(notification(ActionType.WEBHOOK, "w")).retryable()).isTrue();
        http.status = 400;
        assertThat(c.send(notification(ActionType.WEBHOOK, "w")).retryable()).isFalse();
        http.status = 301;
        assertThat(c.send(notification(ActionType.WEBHOOK, "w"))).satisfies(r -> { assertThat(r.retryable()).isFalse(); assertThat(r.detail()).contains("redirect"); });
        http.fail = new IOException("connection reset");
        assertThat(c.send(notification(ActionType.WEBHOOK, "w")).retryable()).isTrue();
        OutboundHttp ssrf = (u, h, b) -> { throw new SsrfException("resolves to loopback"); };
        assertThat(new WebhookConnector("w", URI.create("https://hooks.example.com/oc"), SECRET, ssrf, CLOCK)
                .send(notification(ActionType.WEBHOOK, "w"))).satisfies(r -> { assertThat(r.retryable()).isFalse(); assertThat(r.detail()).contains("outbound policy"); });
    }

    @Test void slackEscapesControlSequencesAndLocksHost() {
        FakeHttp http = new FakeHttp();
        new SlackConnector("slack", "https://hooks.slack.com/services/T/B/X", http).send(notification(ActionType.SLACK, "slack"));
        assertThat(http.calls.getFirst().text()).contains("&lt;!channel&gt;").doesNotContain("<!channel>");
        assertThatThrownBy(() -> new SlackConnector("s", "https://evil.example.com/services/x", http)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlackConnector("s", "http://hooks.slack.com/services/x", http)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void discordDisablesMentions() {
        FakeHttp http = new FakeHttp();
        new DiscordConnector("d", "https://discord.com/api/webhooks/1/abc", http).send(notification(ActionType.DISCORD, "d"));
        JsonNode json = JsonMapper.builder().build().readTree(http.calls.getFirst().text());
        assertThat(json.get("allowed_mentions").get("parse").isEmpty()).isTrue();
        assertThat(json.get("content").asString()).contains("DISTINCT_COUNT transaction.to = 11 GREATER_THAN 10");
        assertThatThrownBy(() -> new DiscordConnector("d", "https://discord.com/other", http)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void telegramValidatesTokenAndNeverLeaksIt() {
        FakeHttp http = new FakeHttp();
        String token = "123456789:AAEhBOweik6ad9r_QXMENQjcrGbqCr4K-ab";
        TelegramConnector t = new TelegramConnector("tg", token, "-1001234567890", http);
        assertThat(t.send(notification(ActionType.TELEGRAM, "tg")).success()).isTrue();
        assertThat(http.calls.getFirst().uri().toString()).isEqualTo("https://api.telegram.org/bot" + token + "/sendMessage");
        assertThat(http.calls.getFirst().text()).contains("\"chat_id\":\"-1001234567890\"").doesNotContain("parse_mode");
        http.status = 500;
        assertThat(t.send(notification(ActionType.TELEGRAM, "tg")).detail()).doesNotContain(token);
        assertThatThrownBy(() -> new TelegramConnector("tg", "../../evil", "1", http)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelegramConnector("tg", token, "1; drop", http)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void emailConnectorBuildsASafeMessage() {
        List<EmailConnector.EmailMessage> mails = new java.util.concurrent.CopyOnWriteArrayList<>();
        EmailConnector.EmailSender mail = mails::add;
        EmailConnector email = new EmailConnector("mail", mail, "alerts@openchain.example", List.of("soc@corp.example"));
        Notification n = notification(ActionType.EMAIL, "mail");
        assertThat(email.send(n).success()).isTrue();
        assertThat(mails).singleElement().satisfies(m -> {
            assertThat(m.subject()).startsWith("[OpenChain HIGH]").doesNotContain("\n");
            assertThat(m.idempotencyId()).isEqualTo("delivery-42");
            assertThat(m.textBody()).contains(NotificationFormatter.DISCLAIMER);
        });
        assertThatThrownBy(() -> new EmailConnector("m", mail, "bad", List.of("x@y.example"))).isInstanceOf(IllegalArgumentException.class);
        EmailConnector failing = new EmailConnector("m", m -> { throw new IllegalStateException("smtp down"); }, "a@b.example", List.of("c@d.example"));
        assertThat(failing.send(n).retryable()).isTrue();
    }

    @Test void internalEventCarriesLoopProtection() {
        List<ChainEvent> bus = new ArrayList<>();
        InternalEventConnector c = new InternalEventConnector("bus", bus::add, CLOCK);
        assertThat(c.send(notification(ActionType.INTERNAL_EVENT, "bus")).success()).isTrue();
        ChainEvent e = bus.getFirst();
        assertThat(e.eventId()).isEqualTo("internal:alert:alert-1");
        assertThat(e.type()).isEqualTo(ChainEvent.EventType.DETECTION_SIGNAL);
        assertThat(e.fields()).containsEntry("internal.hop", 1L).containsEntry("internal.origin_chain", "fund-splitting")
                .containsEntry("alert.rule_id", "fund-splitting");
    }
}
