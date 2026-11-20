/**
 * Wire types for the /api/v1 contract. They mirror the oci-core domain model
 * (io.openchain.core.*). Monetary amounts always travel as strings holding the raw
 * integer amount (base units) plus the token decimals: never as JS numbers.
 */

export type NetworkId = 'tron' | 'ethereum' | 'bsc' | 'polygon' | 'bitcoin' | 'solana';
/**
 * Networks that have a provider adapter (TRON: TronGrid, EVM: JSON-RPC). Bitcoin and Solana stay in NetworkId because the
 * rule schema accepts them, but no data source exists for them, so they are not offered anywhere in the console.
 */
export const NETWORKS: readonly NetworkId[] = ['ethereum', 'tron', 'bsc', 'polygon'];

export type Severity = 'INFO' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export const SEVERITIES: readonly Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];

export type RiskLevel = 'UNKNOWN' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

/** Kept strictly distinct in the UI: an anomaly is not a risk signal, and neither is a confirmation. */
export type EvidenceLevel = 'ANOMALY' | 'RISK_SIGNAL' | 'CONFIRMED_BY_SOURCE';

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface Token {
  network: NetworkId;
  contract: string | null;
  symbol: string;
  decimals: number;
}

/** A stable server code with the values of its placeholders; the console translates it (error.<code>). */
export interface Coded {
  code: string;
  params?: Record<string, unknown>;
}

/** A rule's texts in another language; an absent text means "use the rule's own". */
export interface RuleTranslation {
  name?: string;
  description?: string;
  documentation?: string;
}

export interface NetworkStatus {
  network: NetworkId;
  headBlock: number;
  finalizedBlock: number;
  healthy: boolean;
  providerId: string;
  latencyMillis: number;
  checkedAt: string;
  detail: string | null;
  /** Present on backend responses (NetworkDto); optional so older payloads still type-check. */
  enabled?: boolean;
  lastIndexedBlock?: number;
  /** Blocks behind the chain head; null when the head is unknown (provider failing), which is not "no lag". */
  indexerLag?: number | null;
  /** True when the provider is the simulated demo ledger, not a real chain. */
  simulated?: boolean;
  /** What this instance cannot see or detect on this network (data-source limits). */
  limitations?: string[];
  /** The same limits as stable codes, in the same order (translated by the console). */
  limitationCodes?: Coded[];
  /** Timestamp of the last indexed block; null until known (just after a start). Delay as time = now - this. */
  lastIndexedBlockTime?: string | null;
}

export interface ChainTransaction {
  network: NetworkId;
  hash: string;
  blockNumber: number;
  timestamp: string;
  from: string;
  to: string | null;
  /** Value in base units of `nativeSymbol` (the native coin for a TRANSACTION, the token for a TRANSFER). */
  value: string;
  fee: string | null;
  success: boolean;
  methodSelector: string | null;
  contractInteraction: boolean;
  nativeSymbol?: string;
  nativeDecimals?: number;
  /** TRANSACTION: listed by the provider. TRANSFER: a value flow known to the platform (the node cannot list transactions). */
  kind?: 'TRANSACTION' | 'TRANSFER';
  /** Unique per row: one transaction can carry several transfers. */
  id?: string;
}

export interface TokenTransfer {
  network: NetworkId;
  txHash: string;
  logIndex: number;
  blockNumber: number;
  timestamp: string;
  from: string;
  to: string;
  token: Token;
  rawAmount: string;
}

export interface Balance {
  token: Token;
  rawAmount: string;
  atBlock: number;
  observedAt: string;
}

export interface Evidence {
  kind: string;
  description: string;
  reference: string | null;
  source: string;
  observedAt: string;
}

export interface Signal {
  id: string;
  type: string;
  address: string;
  network: NetworkId;
  txHash: string | null;
  severity: Severity;
  confidence: number;
  evidenceLevel: EvidenceLevel;
  evidence: Evidence[];
  detectorId: string;
  detectedAt: string;
  /** Short plain-language name ("Rapid drain"). May be missing on older payloads: fall back to the type. */
  title: string;
  /** One plain-language sentence built from the evidence. May be missing on older payloads. */
  summary: string;
}

export interface RiskContribution {
  factor: string;
  signalId: string | null;
  source: string;
  weight: number;
  confidence: number;
  ageSeconds: number;
  points: number;
  /** Technical trace of the computation (q, decay, weights...). Shown under "Technical detail". */
  explanation: string;
  evidenceLevel: EvidenceLevel;
  /** Plain-language name ("Rapid drain", "Listed as sanctioned"). May be missing on older payloads. */
  title: string;
  /** Plain-language sentence explaining the contribution. May be missing on older payloads. */
  summary: string;
}

export interface RiskScore {
  address: string;
  network: NetworkId;
  score: number;
  level: RiskLevel;
  contributions: RiskContribution[];
  /** Formula of the scoring method (technical). */
  method: string;
  /** Plain-language description of the scoring method. */
  methodSummary: string;
  modelVersion: string;
  computedAt: string;
  history?: { at: string; score: number; level: RiskLevel }[];
}

export interface Counterparty {
  address: string;
  label: string | null;
  direction: 'IN' | 'OUT' | 'BOTH';
  txCount: number;
  token: Token;
  rawIn: string;
  rawOut: string;
  riskLevel: RiskLevel;
}

export interface WalletAnalysis {
  address: string;
  network: NetworkId;
  firstSeen: string | null;
  lastSeen: string | null;
  txCount: number;
  /** null = unknown (no real provider, lookup failed, or graph node not resolved) */
  isContract: boolean | null;
  labels: Label[];
  balances: Balance[];
  flows: { token: Token; rawIn: string; rawOut: string }[];
  counterparties: Counterparty[];
  activity: { day: string; txIn: number; txOut: number }[];
  signals: Signal[];
  risk: RiskScore | null;
  /** Coverage of the on-demand address history (ADR-0020); null when the server does not report it. */
  history: HistoryCoverage | null;
  /** What this instance cannot see or detect on this network (data-source limits). */
  limitations?: string[];
  limitationCodes?: Coded[];
}

export type HistoryStatus = 'NOT_CONFIGURED' | 'PENDING' | 'RUNNING' | 'COMPLETE' | 'TRUNCATED' | 'FAILED';

/** How much of an address's history the server has loaded, from which sources (ADR-0020). */
export interface HistoryCoverage {
  status: HistoryStatus;
  /** e.g. ["eth_getLogs"], ["trongrid"], [] when not configured. */
  sources: string[];
  /** Lowest block fully covered by the on-demand history (null if unknown / not started). */
  coveredFromBlock: number | null;
  coveredToBlock: number | null;
  /** RFC 3339, when known. */
  coveredFromTime: string | null;
  tokenTransfersCovered: boolean;
  approvalsCovered: boolean;
  nativeCovered: boolean;
  internalCovered: boolean;
  /** Caps reached: older history not loaded. */
  truncated: boolean;
  eventsFound: number;
  /** Plain-language coverage sentence produced by the server. */
  message: string;
  updatedAt: string | null;
}

export interface Exposure {
  address: string;
  network: NetworkId;
  direct: ExposureEntry[];
  indirect: ExposureEntry[];
  /** Transfers from listed addresses that create no exposure (unverified token, dust). */
  ignored?: IgnoredTransfer[];
  /** Labelled counterparties outside the risky categories (exchange, protocol...): information, not exposure. */
  counterparties?: LabelledCounterparty[];
  coverage?: ExposureCoverage;
}

export interface ExposureEntry {
  category: string;
  hops: number;
  /** @deprecated share of volume summed over all tokens (no unit); use `amounts` or `traced`. */
  share: number;
  counterparty: string;
  evidenceLevel: EvidenceLevel;
  source?: string;
  /** Direct: exact amounts per token (decimal strings), never added across tokens. */
  amounts?: { token: string; received: string; sent: string }[] | null;
  transfers?: number | null;
  txHashes?: string[] | null;
  /** Indirect: hops from the listed address to this wallet. */
  path?: { from: string; to: string; txHash: string; token: string; amount: string; timestamp: string }[] | null;
  /** Indirect: at most this amount (smallest hop), in this token. */
  traced?: { token: string; amount: string } | null;
  confidence?: number | null;
}

/** A relation between wallets: an inference, never identity (phase 2). */
export interface WalletRelation {
  other: string;
  type: 'COMMON_FUNDER' | 'COMMON_SINK' | 'SHARED_COUNTERPARTIES';
  level: 'INFERRED';
  confidence: number;
  via: string | null;
  eventIds: string[];
  explanation: string;
}

export interface WalletRelations {
  address: string;
  network: NetworkId;
  relations: WalletRelation[];
  limits: string[];
}

export interface IgnoredTransfer {
  counterparty: string;
  category: string;
  source: string;
  reason: 'unverified_token' | 'below_dust_threshold';
  token: string;
  amount: string;
  txHash: string;
  timestamp: string;
}

export interface LabelledCounterparty {
  counterparty: string;
  category: string;
  source: string;
  evidenceLevel: EvidenceLevel;
  transfers: number;
}

export interface ExposureCoverage {
  /** PARTIAL until the coverage model exists: "none found" means none in the data covered. */
  state: 'COMPLETE' | 'PARTIAL' | 'TRUNCATED' | 'UNAVAILABLE' | 'NOT_YET_INDEXED';
  dataFrom: string | null;
  dataTo: string | null;
  limits: string[];
  /** Block range the state applies to. */
  fromBlock?: number | null;
  toBlock?: number | null;
  segments?: { source: 'HISTORY' | 'LIVE'; fromBlock: number | null; toBlock: number | null; fromTime: string | null; state: string; detail: string | null }[];
  knownGaps?: { fromBlock: number; toBlock: number; cause: string; detectedAt: string }[];
}

export interface TransactionAnalysis {
  transaction: ChainTransaction;
  transfers: TokenTransfer[];
  signals: Signal[];
  confirmations: number;
  finalized: boolean;
}

export interface GraphNode {
  id: string;
  address: string;
  network: NetworkId;
  label: string | null;
  riskLevel: RiskLevel;
  /** null = unknown (no real provider, lookup failed, or graph node not resolved) */
  isContract: boolean | null;
  depth: number;
  /** Directed graph: root, out (value went there), in (value came from there), both. */
  side?: 'root' | 'out' | 'in' | 'both' | null;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  /** Latest transfer. */
  txHash: string;
  token: Token;
  rawAmount: string;
  /** Latest transfer. */
  timestamp: string;
  count?: number;
  /** Directed graph: first transfer, every transfer id (at most 50), value coming back to an address already on the way. */
  firstTimestamp?: string | null;
  eventIds?: string[] | null;
  cycle?: boolean | null;
}

export type GraphDirection = 'out' | 'in' | 'both';

export interface GraphStop { address: string; token: string | null; reason: string; detail: string }

export interface Graph {
  root: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
  direction?: GraphDirection | null;
  status?: 'COMPLETE' | 'PARTIAL' | null;
  stops?: GraphStop[] | null;
  asOfBlock?: number | null;
}

export type TraceStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';

export interface TraceSummary {
  id: string; lineageId: string; version: number; previousId: string | null; network: NetworkId; seed: string;
  direction: 'FORWARD' | 'BACKWARD'; status: TraceStatus; resultStatus: 'COMPLETE' | 'PARTIAL' | null; stale: boolean;
  createdBy: string | null; createdAt: string; completedAt: string | null;
}

/** Exact raw amount as a fraction (no rounding until display). */
export interface Fraction { num: number | string; den: number | string }

export interface TraceResultJson {
  status: 'COMPLETE' | 'PARTIAL';
  nodes: { address: { value: string }; depth: number; labels: string[] }[];
  edges: { eventId: string; aliases: string[]; txHash: string; timestamp: string; from: { value: string }; to: { value: string };
    tokenKey: string; rawAmount: number | string; token: Token }[];
  attributions: { address: { value: string }; tokenKey: string; depth: number; low: Fraction; high: Fraction; partial: boolean }[];
  stops: { address: { value: string }; tokenKey: string | null; reason: string; detail: string }[];
  findings: { type: string; level: string; addresses: { value: string }[]; eventIds: string[]; detail: string }[];
}

export interface TraceDetail extends Omit<TraceSummary, never> {
  staleEventIds: string[] | null; engineVersion: string; error: string | null; frozenFlows: number;
  request: unknown; result: TraceResultJson | null;
  diff: { evidence: { kind: 'ADDED' | 'REMOVED' | 'MOVED'; beforeEventId: string | null; afterEventId: string | null; txHash: string }[];
    attributions: { address: { value: string }; tokenKey: string }[] } | null;
}

export interface InvestigationAssertion {
  id: string; type: string; step: string; level: 'OBSERVED' | 'INFERRED' | 'CONFIRMED'; subject: string;
  params: Record<string, string>; evidence: string[];
}

export interface InvestigationReport {
  plan: string; templates: string; subject: string; assertions: number;
  sections: { title: string; sentences: { text: string; assertionId: string; level: string }[] }[];
}

export interface InvestigationDetail {
  id: string; lineageId: string; version: number; previousId: string | null; network: NetworkId; subject: string; plan: string;
  status: TraceStatus; resultStatus: 'COMPLETE' | 'PARTIAL' | null; stale: boolean; staleEventIds: string[] | null; error: string | null;
  createdBy: string | null; createdAt: string; completedAt: string | null; frozenFlows: number;
  assertions: InvestigationAssertion[] | null; diff: { added: string[]; removed: string[] } | null; report: InvestigationReport | null;
}

/** A human decision on one assertion (ADR-0022 amendment 3): it never changes the assertion, its level or the report. */
export type ReviewDecision = 'ACCEPTED' | 'REJECTED' | 'NEEDS_EVIDENCE';

export interface AssertionReview {
  id: string; investigationId: string; version: number; assertionId: string; decision: ReviewDecision; comment: string | null;
  actor: string; createdAt: string;
}

/** The latest decision on one assertion; inherited when taken on an earlier version (shown, never copied). */
export interface CurrentReview {
  decision: ReviewDecision; comment: string | null; actor: string; createdAt: string; version: number; inherited: boolean;
}

export interface InvestigationReviews {
  investigationId: string; version: number; stale: boolean; history: AssertionReview[]; current: Record<string, CurrentReview>;
  decided: number; total: number;
}

/** The impact of a rule change on the events held in memory (level SIMULATED, nothing alerted or stored). */
export interface RuleSimulationSide { eventsInScope: number; conditionMatches: number; triggers: number; estimatedAlerts: number; estimatedAlertsPerDay: number; warnings: string[]; }
export interface RuleSimulation {
  level: 'SIMULATED'; ruleId: string; currentVersion: string; proposedVersion: string; evaluatedEvents: number;
  window: { from: string | null; to: string | null; note: string }; before: RuleSimulationSide; after: RuleSimulationSide;
  onlyBefore: number; onlyAfter: number; onlyBeforeSample: string[]; onlyAfterSample: string[];
}

/** A hypothesis about a wallet's future activity (ADR-0023 amendment 2); confidence null = uncalibrated. */
export interface PredictionItem {
  id: string; level: 'PREDICTED'; type: string; network: NetworkId; subject: string; confidence: number | null; condition: string;
  limits: string[]; signalIds: string[]; issuedAt: string; expiresAt: string; model: string;
  outcome: 'OPEN' | 'REALISED' | 'NOT_REALISED' | 'UNDETERMINED'; outcomeEvidence: string[]; outcomeReason: string | null; evaluatedAt: string | null;
}

export interface PredictionRate {
  type: string; realised: number; notRealised: number; undetermined: number; rate: number | null; established: boolean; minEvaluated: number;
}

/** "Who would be exposed if this address were listed?" Level SIMULATED, never stored. */
export interface WhatIfResult {
  level: 'SIMULATED'; hypothesis: { network: NetworkId; address: string; category: string }; statement: string; coverage: string;
  coverageReasons: string[]; candidates: number; truncated: boolean;
  exposed: { address: string; hops: number; level: 'SIMULATED'; txHashes: string[]; confidence: number; alreadyExposedTo: string[] }[];
}

/** An AI provider offered for summaries (ADR-0022 amendment 5); dataLocation says where the assertions go. */
export interface AiProvider { id: string; label: string; connector: string; model: string; dataLocation: string | null; }

/** An AI summary request: sentences only when ACCEPTED (checked against the assertions); reasons otherwise. */
export interface InvestigationSummary {
  id: string; investigationId: string; provider: string; model: string | null; promptVersion: string; inputHash: string;
  status: 'ACCEPTED' | 'REFUSED' | 'FAILED'; sentences: { text: string; assertionIds: string[] }[] | null; reasons: string[];
  createdBy: string; createdAt: string;
}

/** Rule drafts proposed from an investigation (ADR-0022 amendment 4): always disabled, never stored. */
export type ProposalTemplate = 'NEXT_HOP' | 'COMMON_FUNDER' | 'SUBJECT_DISPERSION' | 'LISTED_COUNTERPARTY';

export interface ProposalDryRun {
  totalEvents: number; eventsInScope: number; conditionMatches: number; triggers: number; estimatedAlerts: number;
  from: string | null; to: string | null; estimatedAlertsPerDay: number; warnings: string[];
}

export interface RuleProposal {
  ruleId: string; template: ProposalTemplate; assertionIds: string[]; reviews: Record<string, ReviewDecision>; yaml: string; enabled: boolean;
  checks: { valid: boolean; examplesPassed: number; testsPassed: number }; dryRun: ProposalDryRun;
}

export interface RuleProposals {
  investigationId: string; version: number; proposalVersion: string; proposals: RuleProposal[];
  skipped: { assertionId: string; reason: string }[];
}

/** A path value could have taken (directed, chronological, one token), computed by the server. */
export interface GraphPath {
  source: string;
  target: string;
  network: NetworkId;
  outcome: 'FOUND' | 'NO_PATH' | 'LIMIT_REACHED';
  hops: { from: string; to: string; txHash: string; token: string; amount: string; timestamp: string }[];
  stops: GraphStop[];
  note: string;
  asOfBlock: number | null;
}

export type AlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'INVESTIGATING' | 'RESOLVED' | 'FALSE_POSITIVE';
export const ALERT_STATUSES: readonly AlertStatus[] = ['OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'RESOLVED', 'FALSE_POSITIVE'];

export interface AlertComment {
  id: string;
  author: string;
  body: string;
  createdAt: string;
}

export interface AlertHistoryEntry {
  at: string;
  actor: string;
  action: string;
  detail: string | null;
}

/**
 * Why an alert fired, as recorded by the rule engine (oci-alerting RuleEngine, AlertDto.evidence).
 * `conditions` entries read "field op expected (actual value) -> true|false".
 * Durations are ISO-8601 (e.g. "PT2H").
 */
export interface AlertEvidenceBase {
  evidenceLevel?: EvidenceLevel;
  conditions?: string[];
}
export interface StatelessEvidence extends AlertEvidenceBase {
  kind: 'STATELESS';
}
export interface AggregationEvidence extends AlertEvidenceBase {
  kind: 'AGGREGATION';
  /** COUNT, SUM, AVG, MIN, MAX, DISTINCT_COUNT... */
  function: string;
  field?: string;
  value: string;
  operator: string;
  threshold: string;
  window: string;
  windowStartExclusive: string;
  windowEnd: string;
  eventCount: number;
  groupBy: string[];
}
export interface SequenceEvidence extends AlertEvidenceBase {
  kind: 'SEQUENCE';
  key: string;
  within: string;
  /** "stepName=eventId@instant" */
  steps: string[];
  span: string;
}
export type AlertEvidence = StatelessEvidence | AggregationEvidence | SequenceEvidence;

export interface Alert {
  id: string;
  ruleId: string;
  /** Version of the rule that matched (AlertDto.ruleVersion). */
  ruleVersion?: string | null;
  ruleName: string;
  /** The rule's texts by language (AlertDto.ruleTranslations); empty or absent = English only. */
  ruleTranslations?: Record<string, RuleTranslation>;
  severity: Severity;
  status: AlertStatus;
  network: NetworkId;
  address: string | null;
  txHash: string | null;
  /** Other side of the matched transfer, when there is one. */
  counterparty: string | null;
  blockNumber: number | null;
  summary: string;
  /** One plain-language line explaining why the rule fired. */
  evidenceSummary: string | null;
  /** Evidence after chain reorganisations: VALID, PARTIALLY_WITHDRAWN or WITHDRAWN (absent from older servers: VALID). */
  evidenceState?: 'VALID' | 'PARTIALLY_WITHDRAWN' | 'WITHDRAWN';
  /** Matched events that left the canonical chain and were not re-included. */
  withdrawnEventIds?: string[];
  evidenceLevel: EvidenceLevel;
  assignee: string | null;
  createdAt: string;
  updatedAt: string;
  matchedEvent: Record<string, unknown>;
  /** Event ids "network:txHash:logIndex" that made the rule fire (several for windows / sequences). */
  matchedEventIds?: string[];
  /** Engine evidence: aggregate value vs threshold, window, sequence steps, condition outcomes. */
  evidence?: AlertEvidence | null;
  occurrenceCount?: number;
  firstSeen?: string | null;
  lastSeen?: string | null;
  comments: AlertComment[];
  history: AlertHistoryEntry[];
}

/** One of the organisation's own wallets (GET /watchlist). */
export interface WatchlistEntry {
  id: string;
  network: NetworkId;
  address: string;
  name: string | null;
  createdBy: string | null;
  createdAt: string;
}

/** Noise report of one rule (GET /rules/noise): alert volume and false-positive rate among closed alerts. */
export interface RuleNoise {
  ruleId: string;
  total: number;
  open: number;
  falsePositive: number;
  resolved: number;
  /** Alerts created during the last `windowHours`. */
  recent: number;
  windowHours: number;
  /** null until an alert of the rule has been closed. */
  falsePositiveRate: number | null;
}

export interface Rule {
  id: string;
  name: string;
  enabled: boolean;
  severity: Severity;
  yaml: string;
  version: number;
  updatedAt: string;
  /** Why the rule can never fire on this instance (fields or event types never produced); absent/empty = it may fire. */
  unreachableReasons?: string[];
  /** The same reasons as stable codes, in the same order. */
  unreachableCodes?: Coded[];
  description?: string | null;
  documentation?: string | null;
  /** The rule's texts by language, from its source; absent texts are left out. */
  translations?: Record<string, RuleTranslation>;
}

export interface RuleValidationError {
  path: string;
  message: string;
  line?: number;
}

export interface RuleValidationResult {
  valid: boolean;
  errors: RuleValidationError[];
}

export interface RuleTestRequest {
  ruleId?: string;
  yaml?: string;
  from: string;
  to: string;
  mode: 'DRY_RUN';
}

export interface RuleTestResult {
  evaluatedEvents: number;
  matchCount: number;
  estimatedTriggersPerDay: number;
  permissive: boolean;
  warnings: string[];
  matches: { eventId: string; network: NetworkId; eventTime: string; summary: string; fields: Record<string, unknown> }[];
}

export type ConnectorType = 'WEBHOOK' | 'SLACK' | 'DISCORD' | 'TELEGRAM' | 'EMAIL';

export interface Webhook {
  id: string;
  type: ConnectorType;
  name: string;
  /** Endpoint URL, channel webhook, chat id or email address depending on type. */
  target: string;
  enabled: boolean;
  createdAt: string;
  lastDeliveryAt: string | null;
  lastDeliveryStatus: 'OK' | 'FAILED' | null;
  /** Only present in the creation response: shown once, never retrievable again. */
  secret?: string;
}

export interface Label {
  id: string;
  address: string;
  network: NetworkId;
  category: string;
  name: string;
  source: string;
  /** Source reliability, 0..1. */
  reliability: number;
  evidenceLevel: EvidenceLevel;
  createdAt: string;
}

export interface LabelReport {
  id?: string;
  address: string;
  network: NetworkId;
  category: string;
  description: string;
  reference: string;
  status?: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  createdAt?: string;
}

export type FieldType = 'string' | 'address' | 'amount' | 'number' | 'boolean' | 'enum' | 'timestamp';

/** GET /rules/event-types entry. */
export interface EventTypeDef {
  type: string;
  /** false = valid in a rule scope but not produced by the pipeline yet. */
  available: boolean;
}

export interface FieldDef {
  path: string;
  type: FieldType;
  description: string;
  options?: string[];
  /** false = accepted in rules but not populated by the backend pipeline yet (a condition on it never matches). */
  available?: boolean;
}

export type Role = 'ADMIN' | 'ANALYST' | 'VIEWER';

export interface User {
  id: string;
  email: string;
  name: string;
  role: Role;
  lastLoginAt: string | null;
}

export interface ApiKey {
  id: string;
  name: string;
  prefix: string;
  scopes: string[];
  createdAt: string;
  lastUsedAt: string | null;
  revoked: boolean;
  /** Only present in the creation response. */
  secret?: string;
}

/** GET /dashboard. Sections the caller has no permission for are null and named in omittedSections. */
export interface Dashboard {
  generatedAt: string;
  totals: {
    walletsTracked: number;
    eventsIndexed: number;
    transfersIndexed: number;
    eventsRetracted: number;
    rules: number;
    rulesEnabled: number;
    alerts: number | null;
    openAlerts: number | null;
    signals24h: number | null;
  };
  riskDistribution: Record<RiskLevel, number>;
  networks: NetworkStatus[];
  activity: { start: string; events: number; alerts: number | null; signals: number | null }[];
  alerts: {
    byStatus: Record<AlertStatus, number>;
    bySeverity: Record<Severity, number>;
    openBySeverity: Record<Severity, number>;
    recent: Alert[];
    /** The 5 most severe open alerts, newest first within a severity (spec 2026-11-19 §3). */
    attention?: Alert[];
    /** Alerts created in the last 24 hours, split by rule, largest first. */
    last24h?: { total: number; byRule: { ruleId: string; ruleName: string; translations?: Record<string, RuleTranslation>; count: number }[] };
  } | null;
  recentSignals: Signal[] | null;
  omittedSections: string[];
  /** Blocks never analysed (indexer_gaps), newest detection first. Absent from older servers. */
  coverage?: { gaps: IndexingGap[] };
  /** Enabled rules whose alerts reach nobody; null when the role cannot read rules and connectors. */
  notifications?: { unroutedRules: string[]; openAlertsUnrouted: number } | null;
}

export interface IndexingGap {
  network: NetworkId;
  fromBlock: number;
  toBlock: number;
  blocks: number;
  cause: string;
  detectedAt: string | null;
}

/** An official sanctions list in force (ADR-0027): GET /labels/sanctions-lists. */
export interface SanctionsList {
  source: string;
  /** NONE: no list ever accepted; STALE: the list in force was not confirmed for longer than the allowed age. */
  state: 'NONE' | 'CURRENT' | 'STALE';
  fetchEnabled: boolean;
  issued: string | null;
  fetchedAt: string | null;
  checkedAt: string | null;
  tron: number;
  ethereum: number;
  lastError: string | null;
}
