package io.openchain.app.security;

import io.openchain.app.config.OciProperties;
import io.openchain.app.persistence.DevDefaults;
import io.openchain.app.persistence.PersistenceMode;
import io.openchain.app.persistence.PersistenceStartupException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * API keys (ADR-0010): format {@code oci_<env>_<prefix>_<secret>}; the secret is 32 bytes from {@link SecureRandom}
 * (base62, 43 chars). Only the prefix and {@code HMAC-SHA256(pepper, secret)} are stored ({@link ApiKeyRepository}:
 * in memory, or the {@code api_keys} table); verification is constant time and computes the HMAC even for unknown
 * prefixes. Supports expiry and revocation. Plaintext keys are never logged; sensitive operations go to the
 * {@code oci.audit} logger.
 * <p>Pepper: with PostgreSQL persistence an ephemeral pepper would silently invalidate every stored key at the next
 * restart, so {@code OCI_SECURITY_API_KEY_PEPPER} is required there (demo mode falls back to a documented dev-only
 * pepper). In memory mode an ephemeral random pepper is used when none is configured.
 * TODO(persistence): audit_log table, tenant scoping.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    public static final Logger AUDIT = LoggerFactory.getLogger("oci.audit");
    private static final Pattern FORMAT = Pattern.compile("^oci_([a-z0-9]{2,16})_([A-Za-z0-9]{8})_([A-Za-z0-9]{32,128})$");
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SECRET_BYTES = 32;
    /** {@code last_used_at} is written at most once per this interval per key (no write on every request). */
    static final Duration LAST_USED_RESOLUTION = Duration.ofSeconds(60);

    /** Stored metadata (immutable snapshot); {@code hash} is the only trace of the secret. */
    public static final class StoredKey {
        private final String id, name, env, prefix, createdBy;
        private final byte[] hash;
        private final Set<String> scopes;
        private final Instant createdAt, expiresAt, lastUsedAt, revokedAt;

        StoredKey(String id, String name, String env, String prefix, byte[] hash, Set<String> scopes, Instant createdAt,
                  Instant expiresAt, String createdBy, Instant lastUsedAt, Instant revokedAt) {
            this.id = id; this.name = name; this.env = env; this.prefix = prefix; this.hash = hash.clone(); this.scopes = Set.copyOf(scopes);
            this.createdAt = createdAt; this.expiresAt = expiresAt; this.createdBy = createdBy;
            this.lastUsedAt = lastUsedAt; this.revokedAt = revokedAt;
        }

        public String id() { return id; }
        public String name() { return name; }
        /** Public part shown in listings, e.g. {@code oci_demo_Ab12Cd34}. */
        public String displayPrefix() { return "oci_" + env + "_" + prefix; }
        public Set<String> scopes() { return scopes; }
        public Instant createdAt() { return createdAt; }
        public Instant expiresAt() { return expiresAt; }
        public Instant lastUsedAt() { return lastUsedAt; }
        public Instant revokedAt() { return revokedAt; }
        public boolean revoked() { return revokedAt != null; }
        public String createdBy() { return createdBy; }
        String env() { return env; }
        String prefix() { return prefix; }
        byte[] hash() { return hash.clone(); }

        StoredKey withLastUsedAt(Instant at) { return new StoredKey(id, name, env, prefix, hash, scopes, createdAt, expiresAt, createdBy, at, revokedAt); }
        StoredKey withRevokedAt(Instant at) { return new StoredKey(id, name, env, prefix, hash, scopes, createdAt, expiresAt, createdBy, lastUsedAt, at); }
        StoredKey withScopes(Set<String> s) { return new StoredKey(id, name, env, prefix, hash, s, createdAt, expiresAt, createdBy, lastUsedAt, revokedAt); }
    }

    /** Authenticated principal of a key. */
    public record KeyPrincipal(String keyId, String name, String prefix, Set<String> scopes) {}

    /** Creation result: the plaintext is returned to the caller once and never stored. */
    public record Created(StoredKey key, String plaintext) {}

    private final byte[] pepper;
    private final Clock clock;
    private final String env;
    private final ApiKeyRepository repository;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(OciProperties props, Clock clock, ApiKeyRepository repository, PersistenceMode mode) {
        this.clock = clock;
        this.repository = repository;
        this.env = props.getSecurity().getKeyEnvironment();
        this.pepper = pepper(props, mode);
        for (OciProperties.StaticKey k : props.getSecurity().getKeys()) {
            if (k.getKey() == null || k.getKey().isBlank()) continue;
            register(k.getKey(), k.getName() == null ? "static" : k.getName(), Scopes.normalise(k.getScopes()), null, "config");
        }
    }

    private byte[] pepper(OciProperties props, PersistenceMode mode) {
        String p = props.getSecurity().getApiKeyPepper();
        boolean demo = props.getDemo().isEnabled();
        if (p != null && !p.isBlank()) {
            if (mode.jdbc() && !demo && p.equals(DevDefaults.DEV_API_KEY_PEPPER)) {
                throw new PersistenceStartupException("OCI_SECURITY_API_KEY_PEPPER is the publicly known demo pepper, which is "
                        + "refused outside demo mode.", "Generate a pepper with `openssl rand -base64 48` and set OCI_SECURITY_API_KEY_PEPPER.");
            }
            return p.getBytes(StandardCharsets.UTF_8);
        }
        if (mode.jdbc()) {
            if (demo) {
                log.warn("DEMO MODE: OCI_SECURITY_API_KEY_PEPPER is not set; using the publicly known dev-only pepper so that "
                        + "persisted demo keys survive a restart. Never use demo mode in production.");
                return DevDefaults.DEV_API_KEY_PEPPER.getBytes(StandardCharsets.UTF_8);
            }
            throw new PersistenceStartupException("OCI_SECURITY_API_KEY_PEPPER is required when PostgreSQL persistence is enabled: "
                    + "an ephemeral pepper would make every stored API key invalid after a restart.",
                    "Generate a pepper with `openssl rand -base64 48`, set OCI_SECURITY_API_KEY_PEPPER and keep it stable "
                            + "(changing it invalidates all API keys).");
        }
        byte[] b = new byte[32];
        random.nextBytes(b);
        log.warn("OCI_SECURITY_API_KEY_PEPPER is not set: using an ephemeral random pepper (keys do not survive a restart)");
        return b;
    }

    /**
     * Registers a provisioned plaintext key (configuration, demo bootstrap); the plaintext is not kept. Idempotent:
     * a key already stored with the same prefix and secret is returned (its scopes follow the configuration; a
     * revocation is kept). A different secret under an existing prefix is rejected.
     */
    public synchronized StoredKey register(String plaintext, String name, Collection<String> scopes, Instant expiresAt, String actor) {
        var m = FORMAT.matcher(plaintext.trim());
        if (!m.matches()) throw new IllegalArgumentException("API key '" + name + "' does not match oci_<env>_<prefix>_<secret>");
        byte[] hash = hmac(m.group(3));
        Set<String> normalised = Scopes.normalise(scopes);
        Optional<StoredKey> existing = repository.findByPrefix(m.group(2));
        if (existing.isPresent()) {
            StoredKey k = existing.get();
            if (!MessageDigest.isEqual(k.hash, hash) || !k.env.equals(m.group(1))) {
                throw new IllegalStateException("duplicate API key prefix " + k.displayPrefix());
            }
            if (!k.scopes.equals(normalised)) {
                repository.updateScopes(k.id, normalised);
                AUDIT.info("api_key.rescope id={} name={} prefix={} scopes={} actor={}", k.id, name, k.displayPrefix(), normalised, actor);
                k = k.withScopes(normalised);
            }
            return k;
        }
        StoredKey k = new StoredKey(UUID.randomUUID().toString(), name, m.group(1), m.group(2), hash, normalised, clock.instant(),
                expiresAt, actor, null, null);
        repository.insert(k);
        AUDIT.info("api_key.register id={} name={} prefix={} scopes={} expiresAt={} actor={}", k.id, name, k.displayPrefix(),
                k.scopes, expiresAt, actor);
        return k;
    }

    /** Creates a new random key. */
    public Created create(String name, Collection<String> scopes, Duration ttl, String actor) {
        String prefix;
        do { prefix = base62Chars(8); } while (repository.findByPrefix(prefix).isPresent());
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        String plaintext = "oci_" + env + "_" + prefix + "_" + base62(secret);
        Instant expires = ttl == null ? null : clock.instant().plus(ttl);
        var m = FORMAT.matcher(plaintext);
        if (!m.matches()) throw new IllegalStateException("generated key does not match the key format");
        StoredKey k = new StoredKey(UUID.randomUUID().toString(), name, env, prefix, hmac(m.group(3)), Scopes.normalise(scopes),
                clock.instant(), expires, actor, null, null);
        repository.insert(k);
        AUDIT.info("api_key.create id={} name={} prefix={} scopes={} expiresAt={} actor={}", k.id, name, k.displayPrefix(),
                k.scopes, expires, actor);
        return new Created(k, plaintext);
    }

    public Optional<StoredKey> find(String id) { return repository.findById(id); }

    /** Idempotent. */
    public StoredKey revoke(String id, String actor) {
        StoredKey k = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("unknown key " + id));
        if (k.revokedAt == null) {
            Instant now = clock.instant();
            repository.markRevoked(id, now);
            k = repository.findById(id).orElse(k.withRevokedAt(now));
            AUDIT.info("api_key.revoke id={} prefix={} actor={}", id, k.displayPrefix(), actor);
        }
        return k;
    }

    /**
     * Demo bootstrap housekeeping: revokes the still-active keys {@code name} created by {@code createdBy} (keys
     * generated at a previous start) and deletes those that were already revoked before, so a persistent database
     * keeps at most the previous (revoked) and the current generated demo key. Returns the number revoked.
     */
    public synchronized int retireGenerated(String name, String createdBy, String actor) {
        int revoked = 0;
        for (StoredKey k : repository.findAll()) {
            if (!name.equals(k.name) || !createdBy.equals(k.createdBy)) continue;
            if (k.revoked()) {
                repository.delete(k.id);
                AUDIT.info("api_key.purge id={} prefix={} actor={}", k.id, k.displayPrefix(), actor);
            } else {
                revoke(k.id, actor);
                revoked++;
            }
        }
        return revoked;
    }

    public Optional<KeyPrincipal> verify(String presented) {
        if (presented == null) return Optional.empty();
        var m = FORMAT.matcher(presented.trim());
        if (!m.matches()) return Optional.empty();
        StoredKey k = repository.findByPrefix(m.group(2)).orElse(null);
        byte[] actual = hmac(m.group(3)); // computed even for unknown prefixes (timing)
        byte[] expected = k == null ? new byte[actual.length] : k.hash;
        boolean hashOk = MessageDigest.isEqual(expected, actual);
        Instant now = clock.instant();
        boolean ok = hashOk && k != null && k.env.equals(m.group(1)) && k.revokedAt == null
                && (k.expiresAt == null || now.isBefore(k.expiresAt));
        if (!ok) return Optional.empty();
        if (k.lastUsedAt == null || !now.isBefore(k.lastUsedAt.plus(LAST_USED_RESOLUTION))) repository.touch(k.id, now);
        return Optional.of(new KeyPrincipal(k.id, k.name, k.displayPrefix(), k.scopes));
    }

    public List<StoredKey> list() {
        return repository.findAll().stream().sorted(Comparator.comparing(StoredKey::createdAt).reversed()).toList();
    }

    private byte[] hmac(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fixed-width (43 chars) base62 encoding of 32 bytes. */
    private static String base62(byte[] bytes) {
        java.math.BigInteger v = new java.math.BigInteger(1, bytes);
        java.math.BigInteger base = java.math.BigInteger.valueOf(62);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 43; i++) {
            java.math.BigInteger[] qr = v.divideAndRemainder(base);
            sb.append(BASE62.charAt(qr[1].intValue()));
            v = qr[0];
        }
        return sb.reverse().toString();
    }

    private String base62Chars(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(BASE62.charAt(random.nextInt(BASE62.length())));
        return sb.toString();
    }

    /** For log lines: never the secret. */
    public static String redact(String key) {
        var m = FORMAT.matcher(key == null ? "" : key.trim());
        return m.matches() ? "oci_" + m.group(1) + "_" + m.group(2) + "_***" : "***";
    }
}
