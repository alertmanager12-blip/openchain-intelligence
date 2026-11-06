package io.openchain.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void roundTripWithFreshNonces() {
        SecretCipher c = SecretCipher.fromBase64(KEY, false);
        SecretCipher.Sealed a = c.seal("whsec_secret", "connectors:wh-1:secret");
        SecretCipher.Sealed b = c.seal("whsec_secret", "connectors:wh-1:secret");
        assertThat(a.nonce()).hasSize(12).isNotEqualTo(b.nonce());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
        assertThat(c.open(a, "connectors:wh-1:secret")).isEqualTo("whsec_secret");
        assertThat(a.keyId()).startsWith("mk-").isEqualTo(SecretCipher.fromBase64(KEY, false).keyId());
    }

    @Test
    void wrongKeyOrAssociatedDataIsRejected() {
        SecretCipher c = SecretCipher.fromBase64(KEY, false);
        SecretCipher.Sealed s = c.seal("x", "aad-1");
        assertThatThrownBy(() -> c.open(s, "aad-2")).isInstanceOf(SecretCipher.DecryptionException.class);
        SecretCipher other = SecretCipher.fromBase64(Base64.getEncoder().encodeToString(new byte[32]), false);
        assertThatThrownBy(() -> other.open(s, "aad-1")).isInstanceOf(SecretCipher.DecryptionException.class)
                .hasMessageContaining(c.keyId()).hasMessageContaining(other.keyId());
    }

    @Test
    void masterKeyValidationFailsFastWithoutEchoingTheValue() {
        assertThatThrownBy(() -> SecretCipher.fromBase64(null, false)).isInstanceOf(PersistenceStartupException.class)
                .hasMessageContaining("OCI_CRYPTO_MASTER_KEY is required");
        assertThatThrownBy(() -> SecretCipher.fromBase64("not base64 !!", false)).isInstanceOf(PersistenceStartupException.class)
                .hasMessageContaining("not valid base64").hasMessageNotContaining("not base64 !!");
        String short16 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> SecretCipher.fromBase64(short16, false)).isInstanceOf(PersistenceStartupException.class)
                .hasMessageContaining("32 bytes").hasMessageNotContaining(short16);
        // the publicly known compose default: demo only
        assertThatThrownBy(() -> SecretCipher.fromBase64(DevDefaults.DEV_MASTER_KEY, false)).isInstanceOf(PersistenceStartupException.class)
                .hasMessageContaining("refused outside demo mode");
        assertThat(SecretCipher.fromBase64(DevDefaults.DEV_MASTER_KEY, true).keyId()).startsWith("mk-");
    }

    @Test
    void typedJsonKeepsExactTypes() {
        var in = new java.util.LinkedHashMap<String, Object>();
        in.put("amount", new java.math.BigDecimal("1.10"));
        in.put("big", new java.math.BigInteger("115792089237316195423570985008687907853269984665640564039457584007913129639935"));
        in.put("at", java.time.Instant.parse("2026-01-01T00:00:00.123456Z"));
        in.put("n", 3);
        in.put("d", 0.5);
        in.put("nested", java.util.List.of(java.util.Map.of("x", new java.math.BigDecimal("2"))));
        assertThat(TypedJson.readMap(TypedJson.write(in))).isEqualTo(in);
    }
}
