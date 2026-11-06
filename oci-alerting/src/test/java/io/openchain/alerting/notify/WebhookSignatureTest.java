package io.openchain.alerting.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    static final byte[] BODY = "{\"alertId\":\"a1\"}".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.ofEpochSecond(1_780_000_000L);

    @Test void headerFormatMatchesIndependentHmacComputation() throws Exception {
        String header = WebhookSignature.header(SECRET, NOW.getEpochSecond(), BODY);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal((NOW.getEpochSecond() + "." + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));
        assertThat(header).isEqualTo("t=" + NOW.getEpochSecond() + ",v1=" + expected);
    }

    @Test void verifyAcceptsValidSignature() {
        String h = WebhookSignature.header(SECRET, NOW.getEpochSecond(), BODY);
        assertThat(WebhookSignature.verify(SECRET, h, BODY, NOW.plusSeconds(30), Duration.ofMinutes(5))).isTrue();
    }

    @Test void verifyRejectsTamperingReplayAndGarbage() {
        String h = WebhookSignature.header(SECRET, NOW.getEpochSecond(), BODY);
        byte[] tampered = "{\"alertId\":\"a2\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSignature.verify(SECRET, h, tampered, NOW, Duration.ofMinutes(5))).isFalse();
        assertThat(WebhookSignature.verify("another-secret-another-secret-xx".getBytes(), h, BODY, NOW, Duration.ofMinutes(5))).isFalse();
        assertThat(WebhookSignature.verify(SECRET, h, BODY, NOW.plus(Duration.ofMinutes(6)), Duration.ofMinutes(5))).isFalse(); // replay
        String shiftedTs = h.replace("t=" + NOW.getEpochSecond(), "t=" + (NOW.getEpochSecond() + 1));
        assertThat(WebhookSignature.verify(SECRET, shiftedTs, BODY, NOW, Duration.ofMinutes(5))).isFalse(); // ts is signed
        for (String bad : new String[]{null, "", "v1=abcd", "t=1", "t=x,v1=zz", "t=1,v1=" + "a".repeat(600)})
            assertThat(WebhookSignature.verify(SECRET, bad, BODY, NOW, Duration.ofMinutes(5))).as(String.valueOf(bad)).isFalse();
    }
}
