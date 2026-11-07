/**
 * Wire types for the /api/v1 contract. They mirror the oci-core domain model
 * (io.openchain.core.*). Monetary amounts always travel as strings holding the raw
 * integer amount (base units) plus the token decimals: never as JS numbers.
 */

export type NetworkId = 'tron' | 'ethereum' | 'bsc' | 'polygon' | 'bitcoin' | 'solana';
export const NETWORKS: readonly NetworkId[] = ['ethereum', 'tron', 'bsc', 'polygon', 'bitcoin', 'solana'];

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
  indexerLag?: number;
  /** True when the provider is the simulated demo ledger, not a real chain. */
  simulated?: boolean;
}

export interface ChainTransaction {
  network: NetworkId;
  hash: string;
  blockNumber: number;
  timestamp: string;
  from: string;
  to: string | null;
  /** Native value in base units. */
  value: string;
  fee: string;
  success: boolean;
  methodSelector: string | null;
  contractInteraction: boolean;
  nativeSymbol?: string;
  nativeDecimals?: number;
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
  isContract: boolean;
  labels: Label[];
  balances: Balance[];
  flows: { token: Token; rawIn: string; rawOut: string }[];
  counterparties: Counterparty[];
  activity: { day: string; txIn: number; txOut: number }[];
  signals: Signal[];
  risk: RiskScore | null;
  /** Coverage of the on-demand address history (ADR-0020); null when the server does not report it. */
  history: HistoryCoverage | null;
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
}

export interface ExposureEntry {
  category: string;
  hops: number;
  /** Share of volume, 0..1. */
  share: number;
  counterparty: string;
  evidenceLevel: EvidenceLevel;
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
  isContract: boolean;
  depth: number;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  txHash: string;
  token: Token;
  rawAmount: string;
  timestamp: string;
}

export interface Graph {
  root: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
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

export interface Rule {
  id: string;
  name: string;
  enabled: boolean;
  severity: Severity;
  yaml: string;
  version: number;
  updatedAt: string;
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
  } | null;
  recentSignals: Signal[] | null;
  omittedSections: string[];
}
