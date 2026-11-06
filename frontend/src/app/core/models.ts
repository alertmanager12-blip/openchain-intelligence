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
}

export interface RiskContribution {
  factor: string;
  signalId: string | null;
  source: string;
  weight: number;
  confidence: number;
  ageSeconds: number;
  points: number;
  explanation: string;
  evidenceLevel: EvidenceLevel;
}

export interface RiskScore {
  address: string;
  network: NetworkId;
  score: number;
  level: RiskLevel;
  contributions: RiskContribution[];
  method: string;
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

export interface Alert {
  id: string;
  ruleId: string;
  ruleName: string;
  severity: Severity;
  status: AlertStatus;
  network: NetworkId;
  address: string | null;
  txHash: string | null;
  summary: string;
  evidenceLevel: EvidenceLevel;
  assignee: string | null;
  createdAt: string;
  updatedAt: string;
  matchedEvent: Record<string, unknown>;
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
