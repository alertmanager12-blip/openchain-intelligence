package io.openchain.app.persistence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption of secrets at rest (connector signing secrets and credential-bearing targets, ADR-0009).
 * Each value gets a fresh 96-bit nonce from {@link SecureRandom}; the 128-bit tag authenticates the ciphertext and an
 * associated-data string binding it to its row and column (a ciphertext copied to another row does not decrypt).
 * The key id is a fingerprint of the master key, stored with every value so that a wrong or rotated key is detected
 * explicitly. Only {@code java.base} (javax.crypto) is used. The key and plaintexts are never logged.
 */
public final class SecretCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** An encrypted value as stored: key id + nonce + ciphertext (with tag). */
    public record Sealed(String keyId, byte[] nonce, byte[] ciphertext) {}

    /** Wrong key, tampered ciphertext or mismatched associated data. Never contains secret material. */
    public static final class DecryptionException extends RuntimeException {
        public DecryptionException(String message) { super(message); }
    }

    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(byte[] rawKey) {
        if (rawKey == null || rawKey.length != 32) throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        this.key = new SecretKeySpec(rawKey.clone(), "AES");
        this.keyId = fingerprint(rawKey);
    }

    /**
     * Parses {@code OCI_CRYPTO_MASTER_KEY} (base64 of 32 random bytes).
     * @throws PersistenceStartupException with an actionable message when missing or invalid (value never echoed)
     */
    public static SecretCipher fromBase64(String base64, boolean demoMode) {
        String action = "Generate a key with `openssl rand -base64 32`, set it as OCI_CRYPTO_MASTER_KEY "
                + "(property oci.security.crypto-master-key) and keep it in your secret store: losing it makes stored "
                + "connector secrets unrecoverable.";
        if (base64 == null || base64.isBlank()) {
            throw new PersistenceStartupException("OCI_CRYPTO_MASTER_KEY is required when PostgreSQL persistence is enabled "
                    + "(spring.datasource.url is set): connector secrets are encrypted at rest with it.", action);
        }
        String v = base64.strip();
        if (v.equals(DevDefaults.DEV_MASTER_KEY) && !demoMode) {
            throw new PersistenceStartupException("OCI_CRYPTO_MASTER_KEY is the publicly known development key from "
                    + "docker-compose.yml, which is refused outside demo mode (OCI_DEMO_MODE=false).", action);
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(v);
        } catch (IllegalArgumentException e) {
            throw new PersistenceStartupException("OCI_CRYPTO_MASTER_KEY is not valid base64.", action);
        }
        if (raw.length != 32) {
            Arrays.fill(raw, (byte) 0);
            throw new PersistenceStartupException("OCI_CRYPTO_MASTER_KEY must decode to exactly 32 bytes (AES-256); got "
                    + raw.length + " bytes.", action);
        }
        try {
            return new SecretCipher(raw);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    public String keyId() { return keyId; }

    public Sealed seal(String plaintext, String associatedData) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(associatedData));
            return new Sealed(keyId, nonce, c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed: " + e.getClass().getSimpleName());
        }
    }

    public String open(Sealed s, String associatedData) {
        if (!keyId.equals(s.keyId())) {
            throw new DecryptionException("value was encrypted with master key " + s.keyId() + " but the configured "
                    + "OCI_CRYPTO_MASTER_KEY has id " + keyId);
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, s.nonce()));
            c.updateAAD(aad(associatedData));
            return new String(c.doFinal(s.ciphertext()), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new DecryptionException("authentication tag mismatch (wrong key, tampered value or wrong row)");
        } catch (GeneralSecurityException e) {
            throw new DecryptionException("AES-GCM decryption failed: " + e.getClass().getSimpleName());
        }
    }

    private static byte[] aad(String s) { return ("oci:v1:" + s).getBytes(StandardCharsets.UTF_8); }

    /** Non-reversible key identifier: {@code mk-} + 12 hex chars of SHA-256 over a domain-separated key. */
    static String fingerprint(byte[] rawKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("oci-master-key-id:".getBytes(StandardCharsets.UTF_8));
            return "mk-" + HexFormat.of().formatHex(md.digest(rawKey)).substring(0, 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
