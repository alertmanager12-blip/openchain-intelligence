package io.openchain.app.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire types of API v1. Field names follow the SPA models (frontend/src/app/core/models.ts), which are the most
 * specific contract; a few extra fields from docs/api/README.md are added (clients ignore unknown fields).
 * Monetary amounts are strings ({@code rawAmount} base units, {@code amount} exact decimal); times are RFC 3339 UTC.
 */
public final class Dto {

    private Dto() {}

    public record TokenDto(String network, String contract, String symbol, int decimals) {}

    public record EvidenceDto(String kind, String description, String reference, String source, Instant observedAt) {}

    public record SignalDto(String id, String type, String address, String network, String txHash, String severity, double confidence,
                            String evidenceLevel, List<EvidenceDto> evidence, String detectorId, Instant detectedAt,
                            /** Plain-language name, e.g. "Rapid drain". */ String title,
                            /** One plain-language sentence of what was observed (technical trace in evidence). */ String summary) {}

    public record ContributionDto(String factor, String signalId, String source, double weight, double confidence, long ageSeconds,
                                  double points, String explanation, String evidenceLevel,
                                  /** Plain-language factor name ("Rapid drain", "Listed as sanctioned"); explanation stays technical. */
                                  String title, String summary) {}

    public record RiskHistoryDto(Instant at, int score, String level) {}

    public record RiskScoreDto(String address, String network, int score, String level, List<ContributionDto> contributions,
                               String method, String modelVersion, Instant computedAt, List<RiskHistoryDto> history,
                               /** Plain-language description of the scoring method ({@code method} keeps the formula). */
                               String methodSummary) {}

    public record LabelDto(String id, String address, String network, String category, String name, String source,
                           double reliability, String evidenceLevel, Instant createdAt, String reference, String status) {}

    public record BalanceDto(TokenDto token, String rawAmount, String amount, long atBlock, Instant observedAt) {}

    public record FlowDto(TokenDto token, String rawIn, String rawOut) {}

    public record CounterpartyDto(String address, String label, String direction, long txCount, TokenDto token, String rawIn,
                                  String rawOut, String riskLevel) {}

    public record ActivityDto(String day, int txIn, int txOut) {}

    public record ProfileDto(Instant firstSeen, Instant lastSeen, long txCount, boolean isContract, long inboundTransfers,
                             long outboundTransfers, double txPerDay) {}

    public record WalletAnalysisDto(String address, String network, Instant firstSeen, Instant lastSeen, long txCount,
                                    boolean isContract, List<LabelDto> labels, List<BalanceDto> balances, List<FlowDto> flows,
                                    List<CounterpartyDto> counterparties, List<ActivityDto> activity, List<SignalDto> signals,
                                    RiskScoreDto risk, ProfileDto profile, RiskScoreDto riskScore, String dataFreshness,
                                    String disclaimer, HistoryCoverageDto history) {}

    /**
     * Coverage of the on-demand address history (ADR-0020) for one wallet.
     *
     * @param status           NOT_CONFIGURED (no history source: simulated chain or none configured), PENDING (queued),
     *                         RUNNING, COMPLETE, TRUNCATED (caps reached: older history not loaded), FAILED (resumable)
     * @param coveredFromBlock lowest block fully covered (null when unknown / not started)
     * @param coveredToBlock   highest block covered by the history (the live indexer covers the blocks after it)
     */
    public record HistoryCoverageDto(String status, List<String> sources, Long coveredFromBlock, Long coveredToBlock,
                                     Instant coveredFromTime, boolean tokenTransfersCovered, boolean approvalsCovered,
                                     boolean nativeCovered, boolean internalCovered, boolean truncated, long eventsFound,
                                     String message, Instant updatedAt) {}

    public record TxDto(String network, String hash, long blockNumber, Instant timestamp, String from, String to, String value,
                        String fee, boolean success, String methodSelector, boolean contractInteraction, String nativeSymbol,
                        int nativeDecimals) {}

    public record TransferDto(String network, String txHash, int logIndex, long blockNumber, Instant timestamp, String from,
                              String to, TokenDto token, String rawAmount, String amount) {}

    public record CounterpartyRiskDto(String address, int score, String riskLevel, List<String> labels) {}

    public record TxAnalysisDto(TxDto transaction, List<TransferDto> transfers, List<SignalDto> signals, long confirmations,
                                boolean finalized, List<CounterpartyRiskDto> counterparties) {}

    public record NetworkDto(String network, boolean enabled, long headBlock, long finalizedBlock, boolean healthy,
                             String providerId, long latencyMillis, Instant checkedAt, String detail, long lastIndexedBlock,
                             long indexerLag, boolean simulated) {}

    public record GraphNodeDto(String id, String address, String network, String label, List<String> labels, String riskLevel,
                               int riskScore, boolean isContract, int depth) {}

    public record GraphEdgeDto(String id, String source, String target, String txHash, TokenDto token, String rawAmount,
                               String amount, Instant timestamp, int count) {}

    public record GraphDto(String root, String network, List<GraphNodeDto> nodes, List<GraphEdgeDto> edges, boolean truncated) {}

    public record ExposureEntryDto(String category, int hops, double share, String counterparty, String evidenceLevel,
                                   String source) {}

    public record ExposureDto(String address, String network, int depth, List<ExposureEntryDto> direct,
                              List<ExposureEntryDto> indirect) {}

    public record CommentDto(String id, String author, String body, Instant createdAt) {}

    public record HistoryDto(Instant at, String actor, String action, String from, String to, String detail) {}

    public record AlertDto(String id, String ruleId, String ruleVersion, String ruleName, String severity, String priority,
                           String status, String lifecycleStatus, List<String> allowedTransitions, String network,
                           String address, String txHash, String summary, String evidenceLevel, String assignee,
                           long occurrenceCount, Instant firstSeen, Instant lastSeen, Instant createdAt, Instant updatedAt,
                           List<String> matchedEventIds, Map<String, Object> matchedEvent, Map<String, Object> evidence,
                           List<CommentDto> comments, List<HistoryDto> history,
                           /** Demo scenario id (A-E) when the alert comes from fictitious demo data, else null. */
                           String demoScenario,
                           /** Other side of the triggering transfer (counterparty.address, else transaction.to). */
                           String counterparty,
                           /** Block of the (latest) triggering event. */
                           Long blockNumber,
                           /** One plain-language line: why the rule fired (technical detail in evidence). */
                           String evidenceSummary) {}

    public record RuleDto(String id, String name, String description, boolean enabled, String severity, String evidenceLevel,
                          String yaml, String format, int version, String ruleVersion, String compiledHash, List<String> tags,
                          List<String> networks, String origin, Instant createdAt, Instant updatedAt) {}

    public record WebhookDto(String id, String type, String name, String target, String url, List<String> events, boolean enabled,
                             Instant createdAt, Instant lastDeliveryAt, String lastDeliveryStatus,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String secret) {}

    /** {@code available=false}: valid in rules but not populated by the current ingestion pipeline (never matches yet). */
    public record FieldDefDto(String path, String type, String description, boolean sourceBacked,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<String> options, boolean available) {}

    public record LabelReportDto(String id, String network, String address, String category, String description,
                                 List<String> evidenceUrls, String reference, String status, Instant createdAt) {}
}
