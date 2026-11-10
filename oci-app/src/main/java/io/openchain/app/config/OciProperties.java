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
    private final Notifications notifications = new Notifications();
    private final Persistence persistence = new Persistence();
    /** Real chain providers, keyed by network id (tron, ethereum, bsc, polygon). Off unless enabled. */
    private Map<String, Chain> chains = new LinkedHashMap<>();

    public Demo getDemo() { return demo; }
    public Rules getRules() { return rules; }
    public Labels getLabels() { return labels; }
    public Security getSecurity() { return security; }
    public Ingestion getIngestion() { return ingestion; }
    public Notifications getNotifications() { return notifications; }
    public Persistence getPersistence() { return persistence; }
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
        /**
         * In-memory working set bounds (ADR-0024 step 3), except for watched wallets and addresses with loaded history:
         * flows older than {@code workingSetFlowRetention}, signals older than {@code workingSetSignalRetention} (HIGH and
         * CRITICAL kept) and profiles of wallets idle for {@code workingSetProfileIdle}; trimmed every {@code workingSetTrimInterval}.
         */
        private Duration workingSetFlowRetention = Duration.ofHours(24);
        private Duration workingSetSignalRetention = Duration.ofHours(48);
        private Duration workingSetProfileIdle = Duration.ofDays(7);
        private Duration workingSetTrimInterval = Duration.ofMinutes(10);
        /**
         * Hard caps on the working set (ADR-0024 step 3b), applied after the time windows: the oldest items beyond them are
         * removed. Sized for a 3 GB heap with Ethereum mainnet and TRON indexed at the head; raise them with the heap.
         */
        private int workingSetMaxFlows = 400_000;
        private int workingSetMaxSignals = 150_000;
        private int workingSetMaxProfiles = 300_000;

        public int getWorkingSetMaxFlows() { return workingSetMaxFlows; }
        public void setWorkingSetMaxFlows(int v) { this.workingSetMaxFlows = v; }
        public int getWorkingSetMaxSignals() { return workingSetMaxSignals; }
        public void setWorkingSetMaxSignals(int v) { this.workingSetMaxSignals = v; }
        public int getWorkingSetMaxProfiles() { return workingSetMaxProfiles; }
        public void setWorkingSetMaxProfiles(int v) { this.workingSetMaxProfiles = v; }

        public Duration getWorkingSetFlowRetention() { return workingSetFlowRetention; }
        public void setWorkingSetFlowRetention(Duration v) { this.workingSetFlowRetention = v; }
        public Duration getWorkingSetSignalRetention() { return workingSetSignalRetention; }
        public void setWorkingSetSignalRetention(Duration v) { this.workingSetSignalRetention = v; }
        public Duration getWorkingSetProfileIdle() { return workingSetProfileIdle; }
        public void setWorkingSetProfileIdle(Duration v) { this.workingSetProfileIdle = v; }
        public Duration getWorkingSetTrimInterval() { return workingSetTrimInterval; }
        public void setWorkingSetTrimInterval(Duration v) { this.workingSetTrimInterval = v; }
        /**
         * Observation time a real chain needs before "first seen by the platform" fields (wallet.age_days, wallet.tx_count,
         * counterparty.age_days, approval.spender_age_days) are emitted (OCI_INGESTION_AGE_WARMUP, default 30 days).
         */
        private Duration ageWarmup = Duration.ofDays(30);

        public Duration getAgeWarmup() { return ageWarmup; }
        public void setAgeWarmup(Duration ageWarmup) { this.ageWarmup = ageWarmup; }

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
        /**
         * Fallback endpoints (OCI_CHAINS_<NETWORK>_FALLBACK_URLS, comma-separated), tried in order when the primary fails
         * with a retryable error. The API key header is sent to the primary {@code url} only, never to a fallback.
         */
        private List<String> fallbackUrls = new ArrayList<>();

        public List<String> getFallbackUrls() { return fallbackUrls; }
        public void setFallbackUrls(List<String> fallbackUrls) { this.fallbackUrls = fallbackUrls; }

        /** Optional header sent to the provider (e.g. TRON-PRO-API-KEY); never logged. */
        private String apiKeyHeader;
        private String apiKey;
        /** First block to index when no checkpoint exists; negative = head - |value|. */
        private long startBlock = -100;
        /**
         * Also ingest APPROVAL events of tokens the token registry does not know (default false: only registry tokens,
         * e.g. USDT/USDC; anyone can deploy a token emitting approvals).
         */
        private boolean approvalsOfUnknownTokens;
        /**
         * Contracts whose admin changes (OwnershipTransferred, EIP-1967 Upgraded / AdminChanged) are ingested as
         * CONTRACT_ADMIN_CHANGE events, in addition to the token registry's contracts. Empty by default.
         */
        private List<String> watchedContracts = new ArrayList<>();
        /** Ingest admin changes of every contract (default false: registry tokens + watched contracts only). */
        private boolean adminChangesOfAllContracts;
        private final FreezeBackfill freezeBackfill = new FreezeBackfill();
        private final History history = new History();
        /**
         * Real on-chain balance lookups (wallet.balance, rapid drain) cost one RPC call each. They are skipped while the
         * indexer catches up (more than {@code realBalanceMaxLag} blocks behind the head) and capped per block; a skipped
         * lookup leaves the field absent, so balance-based rules and detectors stay silent instead of guessing.
         */
        private int realBalanceMaxLookupsPerBlock = 5;
        /** Blocks per indexer pass on a real chain (each network is polled on its own thread). */
        private int maxBlocksPerPoll = 10;
        /**
         * After a restart indexing resumes from the last processed block (no gap) unless that is more than this many blocks
         * behind the head (a non-archive node may no longer serve them, and catching up would take hours): it then
         * restarts near the head and logs the skipped range. 0 = always resume.
         */
        private long maxResumeGap = 7_200;

        public long getMaxResumeGap() { return maxResumeGap; }
        public void setMaxResumeGap(long v) { this.maxResumeGap = v; }

        public int getMaxBlocksPerPoll() { return maxBlocksPerPoll; }
        public void setMaxBlocksPerPoll(int v) { this.maxBlocksPerPoll = v; }
        private long realBalanceMaxLag = 32;

        public int getRealBalanceMaxLookupsPerBlock() { return realBalanceMaxLookupsPerBlock; }
        public void setRealBalanceMaxLookupsPerBlock(int v) { this.realBalanceMaxLookupsPerBlock = v; }
        public long getRealBalanceMaxLag() { return realBalanceMaxLag; }
        public void setRealBalanceMaxLag(long v) { this.realBalanceMaxLag = v; }

        public List<String> getWatchedContracts() { return watchedContracts; }
        public void setWatchedContracts(List<String> watchedContracts) { this.watchedContracts = watchedContracts; }
        public boolean isAdminChangesOfAllContracts() { return adminChangesOfAllContracts; }
        public void setAdminChangesOfAllContracts(boolean adminChangesOfAllContracts) { this.adminChangesOfAllContracts = adminChangesOfAllContracts; }
        public FreezeBackfill getFreezeBackfill() { return freezeBackfill; }
        public History getHistory() { return history; }

        public boolean isApprovalsOfUnknownTokens() { return approvalsOfUnknownTokens; }
        public void setApprovalsOfUnknownTokens(boolean approvalsOfUnknownTokens) { this.approvalsOfUnknownTokens = approvalsOfUnknownTokens; }
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

    /**
     * Bounded historical scan of the issuer freeze events of {@code StablecoinFreezeSources} contracts
     * ({@code oci.chains.<network>.freeze-backfill.*}). Disabled unless {@code from-block} is set.
     */
    /**
     * On-demand address history (ADR-0020, {@code oci.chains.<network>.history.*}): when an analyst opens an address of a
     * real network, its past ERC-20 transfers and approvals ({@code eth_getLogs} by topic) or TRC-20/TRX transfers
     * (TronGrid account endpoints) are loaded in the background through the chain's own provider. Native and internal
     * EVM transfers are NOT covered by eth_getLogs.
     */
    public static class History {
        /** Load address history on demand for this real network (default true; uses the configured provider). */
        private boolean enabled = true;
        /** Lookback: blocks below the first live-indexed block (or the finalized head) that are searched. */
        private long maxBlocks = 500_000;
        /** Source pages per address (EVM: one chunk of blocks = two eth_getLogs calls). */
        private int maxPages = 200;
        /** Events stored per address; reaching it marks the history TRUNCATED (older events not loaded). */
        private long maxEvents = 5_000;
        /** EVM: blocks per eth_getLogs query at the start (halved on "range too large" down to min-chunk-size). */
        private int chunkSize = 10_000;
        private int minChunkSize = 50;
        /** Pause between two pages (rate-limit friendliness, on top of the transport rate limiter). */
        private Duration pause = Duration.ofMillis(100);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getMaxBlocks() { return maxBlocks; }
        public void setMaxBlocks(long maxBlocks) { this.maxBlocks = maxBlocks; }
        public int getMaxPages() { return maxPages; }
        public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
        public long getMaxEvents() { return maxEvents; }
        public void setMaxEvents(long maxEvents) { this.maxEvents = maxEvents; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getMinChunkSize() { return minChunkSize; }
        public void setMinChunkSize(int minChunkSize) { this.minChunkSize = minChunkSize; }
        public Duration getPause() { return pause; }
        public void setPause(Duration pause) { this.pause = pause; }
    }

    public static class FreezeBackfill {
        /** First block to scan; null (default) = backfill disabled for this network. */
        private Long fromBlock;
        /** Last block to scan; null = the provider's finalized head at run time. */
        private Long toBlock;
        /** Blocks per eth_getLogs / TronGrid events query at the start of a run (halved on "range too large"). */
        private int chunkSize = 2_000;
        /** Smallest chunk before a range error stops the run. */
        private int minChunkSize = 10;
        /** Pause between two chunks (rate-limit friendliness, on top of the transport rate limiter). */
        private Duration pause = Duration.ofMillis(250);
        /** Start (or resume) the backfill in the background at startup; otherwise only via POST /api/v1/admin/backfill/freezes. */
        private boolean runOnStartup = true;

        public boolean isEnabled() { return fromBlock != null; }
        public Long getFromBlock() { return fromBlock; }
        public void setFromBlock(Long fromBlock) { this.fromBlock = fromBlock; }
        public Long getToBlock() { return toBlock; }
        public void setToBlock(Long toBlock) { this.toBlock = toBlock; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getMinChunkSize() { return minChunkSize; }
        public void setMinChunkSize(int minChunkSize) { this.minChunkSize = minChunkSize; }
        public Duration getPause() { return pause; }
        public void setPause(Duration pause) { this.pause = pause; }
        public boolean isRunOnStartup() { return runOnStartup; }
        public void setRunOnStartup(boolean runOnStartup) { this.runOnStartup = runOnStartup; }
    }

    public static class Security {
        /**
         * OCI_SECURITY_API_KEY_PEPPER: HMAC key for API key hashes. Required with PostgreSQL persistence (outside demo
         * mode); in memory mode a random ephemeral pepper is used when empty.
         */
        private String apiKeyPepper;
        /**
         * OCI_CRYPTO_MASTER_KEY: base64 of 32 random bytes, AES-256-GCM key for connector secrets at rest. Required
         * with PostgreSQL persistence. Never logged.
         */
        private String cryptoMasterKey;
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
        public String getCryptoMasterKey() { return cryptoMasterKey; }
        public void setCryptoMasterKey(String cryptoMasterKey) { this.cryptoMasterKey = cryptoMasterKey; }
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

    /** Operator-state persistence (PostgreSQL when spring.datasource.url is set). */
    public static class Persistence {
        /**
         * How long a delivery claimed by an instance stays reserved for it. An instance that crashes mid-attempt
         * releases its claims when this lease expires; the delivery is then retried (at least once).
         */
        private Duration deliveryClaimLease = Duration.ofMinutes(2);
        /**
         * How long chain-derived intelligence (flows, signals, risk scores) is kept in PostgreSQL and reloaded at start-up
         * (OCI_PERSISTENCE_INTELLIGENCE_RETENTION, default 7 days).
         */
        private Duration intelligenceRetention = Duration.ofDays(7);
        /**
         * Journal flows, signals and scores (OCI_PERSISTENCE_INTELLIGENCE_JOURNAL, default false). Off by default: on a
         * chain-wide index it writes millions of rows per day (production 2026-11-09: ~4.8M flows/day) and the start-up
         * reload does not scale to the retention period yet (ADR-0024 step 2 follow-up: watchlist scope, profile snapshots).
         */
        private boolean intelligenceJournal = false;

        public boolean isIntelligenceJournal() { return intelligenceJournal; }
        public void setIntelligenceJournal(boolean v) { this.intelligenceJournal = v; }
        /** Records queued for the write-behind journal before new ones are dropped (and counted). */
        private int journalCapacity = 200_000;

        public Duration getIntelligenceRetention() { return intelligenceRetention; }
        public void setIntelligenceRetention(Duration v) { this.intelligenceRetention = v; }
        public int getJournalCapacity() { return journalCapacity; }
        public void setJournalCapacity(int v) { this.journalCapacity = v; }
        public Duration getDeliveryClaimLease() { return deliveryClaimLease; }
        public void setDeliveryClaimLease(Duration deliveryClaimLease) { this.deliveryClaimLease = deliveryClaimLease; }
    }

    /** Server-side credentials of notification channels (never returned by the API). */
    public static class Notifications {
        private final Telegram telegram = new Telegram();
        /**
         * OCI_PUBLIC_URL: public address of the console (e.g. https://chainsentinel.example.com), used for the links in
         * notifications (Telegram "Console" line). Unset = no console link.
         */
        private String publicUrl;
        /**
         * OCI_DEFAULT_CONNECTOR: id of the connector that receives the alerts of rules routed to the pack's placeholder
         * {@code default-webhook}. Unset = those alerts are recorded as not notified (SKIPPED), never dead-lettered.
         */
        private String defaultConnector;

        public String getDefaultConnector() { return defaultConnector; }
        public void setDefaultConnector(String defaultConnector) { this.defaultConnector = defaultConnector; }
        public Telegram getTelegram() { return telegram; }
        public String getPublicUrl() { return publicUrl; }
        public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    }

    public static class Telegram {
        /** OCI_TELEGRAM_BOT_TOKEN; TELEGRAM connectors can be created only when set. */
        private String botToken;

        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
    }
}
