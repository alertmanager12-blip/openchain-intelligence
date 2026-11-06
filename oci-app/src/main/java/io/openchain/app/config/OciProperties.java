package io.openchain.app.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All {@code oci.*} settings. See application.yml for defaults and the matching environment variables. */
@ConfigurationProperties(prefix = "oci")
public class OciProperties {

    private final Demo demo = new Demo();
    private final Rules rules = new Rules();
    private final Labels labels = new Labels();
    private final Security security = new Security();
    private final Ingestion ingestion = new Ingestion();
    /** Real chain providers, keyed by network id (tron, ethereum, bsc, polygon). Off unless enabled. */
    private Map<String, Chain> chains = new LinkedHashMap<>();

    public Demo getDemo() { return demo; }
    public Rules getRules() { return rules; }
    public Labels getLabels() { return labels; }
    public Security getSecurity() { return security; }
    public Ingestion getIngestion() { return ingestion; }
    public Map<String, Chain> getChains() { return chains; }
    public void setChains(Map<String, Chain> chains) { this.chains = chains; }

    public static class Demo {
        /** OCI_DEMO_MODE: simulated chains, demo labels and a generated admin API key. */
        private boolean enabled;
        /** Networks simulated in demo mode. */
        private List<String> networks = new ArrayList<>(List.of("tron", "ethereum"));
        /** On-chain minutes of history generated at startup (per network). */
        private int seedMinutes = 60;
        /** Keep mining blocks in real time after startup. */
        private boolean live = true;
        private long seed = 42;
        private int addressPoolSize = 40;
        private int maxTransfersPerBlock = 3;
        /** Fixed demo ADMIN key (plaintext); when empty a random one is generated and logged once at startup. */
        private String apiKey;
        /**
         * Fixed, publicly documented key for the Angular dev server (demo profile only). Limited permissions:
         * read + alerts/rules write + label reports; never webhooks:write or keys:admin.
         */
        private String uiKey;
        private List<String> uiKeyScopes = new ArrayList<>(List.of("wallet:read", "transaction:read", "risk:read",
                "investigation:read", "alerts:read", "alerts:write", "rules:read", "rules:write", "webhooks:read", "labels:report"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getNetworks() { return networks; }
        public void setNetworks(List<String> networks) { this.networks = networks; }
        public int getSeedMinutes() { return seedMinutes; }
        public void setSeedMinutes(int seedMinutes) { this.seedMinutes = seedMinutes; }
        public boolean isLive() { return live; }
        public void setLive(boolean live) { this.live = live; }
        public long getSeed() { return seed; }
        public void setSeed(long seed) { this.seed = seed; }
        public int getAddressPoolSize() { return addressPoolSize; }
        public void setAddressPoolSize(int addressPoolSize) { this.addressPoolSize = addressPoolSize; }
        public int getMaxTransfersPerBlock() { return maxTransfersPerBlock; }
        public void setMaxTransfersPerBlock(int maxTransfersPerBlock) { this.maxTransfersPerBlock = maxTransfersPerBlock; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getUiKey() { return uiKey; }
        public void setUiKey(String uiKey) { this.uiKey = uiKey; }
        public List<String> getUiKeyScopes() { return uiKeyScopes; }
        public void setUiKeyScopes(List<String> uiKeyScopes) { this.uiKeyScopes = uiKeyScopes; }
    }

    public static class Rules {
        /** Directories scanned recursively for *.yaml / *.yml / *.json rules; the first existing one is used. */
        private List<String> directories = new ArrayList<>(List.of("rules", "../rules"));
        private boolean loadOnStartup = true;
        /** Dry runs replay at most this many recorded events. */
        private int maxDryRunEvents = 50_000;

        public List<String> getDirectories() { return directories; }
        public void setDirectories(List<String> directories) { this.directories = directories; }
        public boolean isLoadOnStartup() { return loadOnStartup; }
        public void setLoadOnStartup(boolean loadOnStartup) { this.loadOnStartup = loadOnStartup; }
        public int getMaxDryRunEvents() { return maxDryRunEvents; }
        public void setMaxDryRunEvents(int maxDryRunEvents) { this.maxDryRunEvents = maxDryRunEvents; }
    }

    public static class Labels {
        /** Label files (CSV/JSON, see LabelFileLoader); {@code classpath:} or file paths. */
        private List<String> files = new ArrayList<>();

        public List<String> getFiles() { return files; }
        public void setFiles(List<String> files) { this.files = files; }
    }

    public static class Ingestion {
        /** Indexer poll period for real providers. */
        private Duration pollInterval = Duration.ofSeconds(5);
        /** Outbox dispatch period. */
        private Duration dispatchInterval = Duration.ofSeconds(2);
        /** Recorded (enriched) events kept in memory for alert context and dry runs. */
        private int eventHistorySize = 100_000;

        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
        public Duration getDispatchInterval() { return dispatchInterval; }
        public void setDispatchInterval(Duration dispatchInterval) { this.dispatchInterval = dispatchInterval; }
        public int getEventHistorySize() { return eventHistorySize; }
        public void setEventHistorySize(int eventHistorySize) { this.eventHistorySize = eventHistorySize; }
    }

    public static class Chain {
        private boolean enabled;
        /** trongrid (TRON) or json-rpc (EVM). */
        private String provider;
        private String url;
        /** Optional header sent to the provider (e.g. TRON-PRO-API-KEY); never logged. */
        private String apiKeyHeader;
        private String apiKey;
        /** First block to index when no checkpoint exists; negative = head - |value|. */
        private long startBlock = -100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getApiKeyHeader() { return apiKeyHeader; }
        public void setApiKeyHeader(String apiKeyHeader) { this.apiKeyHeader = apiKeyHeader; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public long getStartBlock() { return startBlock; }
        public void setStartBlock(long startBlock) { this.startBlock = startBlock; }
    }

    public static class Security {
        /** OCI_SECURITY_API_KEY_PEPPER: HMAC key for API key hashes. A random ephemeral pepper is used when empty. */
        private String apiKeyPepper;
        /** {@code <env>} part of generated keys ({@code oci_<env>_...}). */
        private String keyEnvironment = "live";
        /** Expiry applied to created keys when the request gives none. */
        private Duration defaultKeyTtl = Duration.ofDays(90);
        /** Upper bound for requested expiries. */
        private Duration maxKeyTtl = Duration.ofDays(365);
        private List<StaticKey> keys = new ArrayList<>();
        private List<String> corsAllowedOrigins = new ArrayList<>(List.of("http://localhost:4200"));
        private final Oidc oidc = new Oidc();

        public String getApiKeyPepper() { return apiKeyPepper; }
        public void setApiKeyPepper(String apiKeyPepper) { this.apiKeyPepper = apiKeyPepper; }
        public String getKeyEnvironment() { return keyEnvironment; }
        public void setKeyEnvironment(String keyEnvironment) { this.keyEnvironment = keyEnvironment; }
        public Duration getDefaultKeyTtl() { return defaultKeyTtl; }
        public void setDefaultKeyTtl(Duration defaultKeyTtl) { this.defaultKeyTtl = defaultKeyTtl; }
        public Duration getMaxKeyTtl() { return maxKeyTtl; }
        public void setMaxKeyTtl(Duration maxKeyTtl) { this.maxKeyTtl = maxKeyTtl; }
        public List<StaticKey> getKeys() { return keys; }
        public void setKeys(List<StaticKey> keys) { this.keys = keys; }
        public List<String> getCorsAllowedOrigins() { return corsAllowedOrigins; }
        public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
        public Oidc getOidc() { return oidc; }
    }

    public static class StaticKey {
        private String name;
        private String key;
        /** Scopes, or {@code *} for all. */
        private List<String> scopes = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
    }

    public static class Oidc {
        /** OCI_SECURITY_OIDC_ISSUER_URI; JWT resource server is configured only when set. */
        private String issuerUri;
        /** Expected audience (optional). */
        private String audience;
        private String rolesClaim = "oci_roles";

        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getRolesClaim() { return rolesClaim; }
        public void setRolesClaim(String rolesClaim) { this.rolesClaim = rolesClaim; }
    }
}
