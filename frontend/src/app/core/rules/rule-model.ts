import { EvidenceLevel, FieldDef, FieldType, NetworkId, Severity } from '../models';
import { YamlValue } from './yaml';

/**
 * Declarative rule model edited by the no-code builder. It is pure data and maps 1:1 onto the backend rule DSL
 * `openchain.io/rule/v1` (oci-alerting RuleValidator, rules/README.md), which is the contract: nothing here is ever
 * executed as code (no expressions, no scripting).
 */
export type Operator =
  | 'eq' | 'neq' | 'gt' | 'gte' | 'lt' | 'lte' | 'between'
  | 'in' | 'not_in' | 'contains' | 'starts_with' | 'matches' | 'exists';

/** Builder operator -> DSL operator (oci-alerting Operator). */
export const DSL_OPERATORS: Record<Operator, string> = {
  eq: 'EQUALS', neq: 'NOT_EQUALS', gt: 'GREATER_THAN', gte: 'GREATER_THAN_OR_EQUAL', lt: 'LESS_THAN', lte: 'LESS_THAN_OR_EQUAL',
  between: 'BETWEEN', in: 'IN', not_in: 'NOT_IN', contains: 'CONTAINS', starts_with: 'STARTS_WITH', matches: 'MATCHES', exists: 'EXISTS',
};

export type ConditionValue = string | number | boolean | string[] | null;

export interface Condition {
  kind: 'condition';
  id: string;
  field: string;
  op: Operator;
  value: ConditionValue;
  /** STRING equality / IN / CONTAINS / STARTS_WITH only. */
  ignoreCase?: boolean;
}

export interface ConditionGroup {
  kind: 'group';
  id: string;
  /** all = AND, any = OR, not = NOT (exactly one child). */
  match: 'all' | 'any' | 'not';
  children: (Condition | ConditionGroup)[];
}

/** Window functions supported by the engine (oci-alerting AggregationFunction). */
export type Aggregate = 'count' | 'sum' | 'distinct_count' | 'max' | 'ratio_of_balance';
export const AGGREGATES: readonly Aggregate[] = ['count', 'sum', 'distinct_count', 'max', 'ratio_of_balance'];

export interface WindowSpec {
  duration: string;
  aggregate: Aggregate;
  /** Field aggregated; not used by count. */
  field: string | null;
  /** 0..3 group-by fields. */
  groupBy: string[];
  /** Aggregation thresholds support > and >= only. */
  op: 'gt' | 'gte';
  value: string;
  /** Reference balance for ratio_of_balance (default wallet.balance). */
  balanceField?: string | null;
}

export type ActionType = 'alert' | 'webhook' | 'slack' | 'discord' | 'telegram' | 'email';
export const ACTION_TYPES: readonly ActionType[] = ['alert', 'webhook', 'slack', 'discord', 'telegram', 'email'];

export interface RuleAction {
  type: ActionType;
  /** Connector id (webhook/slack/...); empty for a plain alert (every match creates an alert, no action needed). */
  target: string;
}

export interface RuleDraft {
  id: string;
  /** Semantic version of the rule (MAJOR.MINOR.PATCH). */
  version: string;
  name: string;
  description: string;
  author: string;
  enabled: boolean;
  severity: Severity;
  evidenceLevel: EvidenceLevel;
  networks: NetworkId[];
  eventTypes: string[];
  when: ConditionGroup;
  window: WindowSpec | null;
  actions: RuleAction[];
  cooldown: string;
  /** DSL keys the builder does not edit (tags, dedupKey, documentation, examples, tests, sequence...), kept verbatim. */
  extra: Record<string, YamlValue>;
}

/** Mirrors oci-core ChainEvent.EventType (every value the backend rule validator accepts in scope.eventTypes). */
export const EVENT_TYPES = ['TOKEN_TRANSFER', 'NATIVE_TRANSFER', 'CONTRACT_CALL', 'CONTRACT_ADMIN_CHANGE', 'APPROVAL', 'RISK_SCORE_CHANGED',
  'DETECTION_SIGNAL', 'STABLECOIN_FREEZE', 'STABLECOIN_UNFREEZE'] as const;
export const EVIDENCE_LEVELS: readonly EvidenceLevel[] = ['ANOMALY', 'RISK_SIGNAL', 'CONFIRMED_BY_SOURCE'];

/** Mirrors oci-alerting Operator.allowedFor(FieldType). */
export const OPERATORS_BY_TYPE: Record<FieldType, Operator[]> = {
  string: ['eq', 'neq', 'in', 'not_in', 'contains', 'starts_with', 'matches', 'exists'],
  address: ['eq', 'neq', 'in', 'not_in', 'starts_with', 'exists'],
  amount: ['gt', 'gte', 'lt', 'lte', 'eq', 'neq', 'between', 'in', 'not_in', 'exists'],
  number: ['gt', 'gte', 'lt', 'lte', 'eq', 'neq', 'between', 'in', 'not_in', 'exists'],
  boolean: ['eq', 'neq', 'exists'],
  enum: ['eq', 'neq', 'in', 'not_in', 'exists'],
  timestamp: ['gt', 'gte', 'lt', 'lte', 'eq', 'neq', 'between', 'exists'],
};

export const OPERATOR_LABELS: Record<Operator, string> = {
  eq: 'equals', neq: 'not equals', gt: '>', gte: '>=', lt: '<', lte: '<=', between: 'between',
  in: 'is one of', not_in: 'is not one of', contains: 'contains', starts_with: 'starts with',
  matches: 'matches (safe regex)', exists: 'is present',
};

export const DURATION = /^[1-9]\d*(s|m|h|d)$/;
export const DECIMAL = /^-?\d+(\.\d+)?$/;
export const SLUG = /^[a-z0-9][a-z0-9-]{1,62}$/;
export const SEMVER = /^\d+\.\d+\.\d+$/;

/**
 * Default field catalog = oci-alerting FieldCatalog.defaults() (served live at /rules/fields; this copy is used
 * when the backend is unavailable). STRING address-like fields are typed `address`, closed value sets `enum`.
 */
const CATALOG: FieldDef[] = [
  { path: 'network', type: 'enum', description: 'Network id (tron, ethereum, bsc, ...)', options: ['tron', 'ethereum', 'bsc', 'polygon', 'bitcoin', 'solana'] },
  { path: 'event.type', type: 'enum', description: 'ChainEvent type (TOKEN_TRANSFER, APPROVAL, ...)', options: ['TOKEN_TRANSFER', 'NATIVE_TRANSFER', 'CONTRACT_CALL', 'CONTRACT_ADMIN_CHANGE', 'APPROVAL', 'RISK_SCORE_CHANGED', 'DETECTION_SIGNAL'] },
  { path: 'event.block_number', type: 'number', description: 'Block number of the event' },
  { path: 'event.time', type: 'timestamp', description: 'On-chain event time' },
  { path: 'transaction.hash', type: 'string', description: 'Transaction hash' },
  { path: 'transaction.from', type: 'address', description: 'Sender address (canonical form; EVM lowercase)' },
  { path: 'transaction.to', type: 'address', description: 'Recipient address (canonical form; EVM lowercase)' },
  { path: 'transaction.amount', type: 'amount', description: 'Transferred amount in token units (raw / 10^decimals)' },
  { path: 'transaction.amount_usd', type: 'amount', description: 'Transferred amount valued in USD, when priced' },
  { path: 'transaction.fee', type: 'amount', description: 'Fee paid in native units' },
  { path: 'transaction.token.symbol', type: 'string', description: 'Token symbol (USDT, ETH, TRX...)' },
  { path: 'transaction.token.contract', type: 'address', description: 'Token contract address; absent for native coin' },
  { path: 'transaction.token.decimals', type: 'number', description: 'Token decimals' },
  { path: 'transaction.method', type: 'string', description: 'Method selector or decoded method name' },
  { path: 'transaction.success', type: 'boolean', description: 'Execution success' },
  { path: 'transaction.contract_interaction', type: 'boolean', description: 'Transaction calls a contract' },
  { path: 'wallet.address', type: 'address', description: 'Wallet the event is attributed to' },
  { path: 'wallet.balance', type: 'amount', description: 'Wallet balance of the transferred token BEFORE the event' },
  { path: 'wallet.risk_score', type: 'number', description: 'Explainable risk score 0..100 (not an accusation)' },
  { path: 'wallet.risk_level', type: 'enum', description: 'UNKNOWN|LOW|MEDIUM|HIGH|CRITICAL', options: ['UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] },
  { path: 'wallet.age_days', type: 'number', description: 'Days since first on-chain activity' },
  { path: 'wallet.tx_count', type: 'number', description: 'Known transaction count' },
  { path: 'wallet.labels', type: 'string', description: 'Comma-separated labels (exchange, bridge, ...)' },
  { path: 'wallet.watchlisted', type: 'boolean', description: 'Address is on an operator watchlist' },
  { path: 'wallet.sanctioned', type: 'boolean', description: 'Listed by an official sanctions source' },
  { path: 'counterparty.address', type: 'address', description: 'Other side of the transfer' },
  { path: 'counterparty.risk_score', type: 'number', description: 'Counterparty risk score 0..100' },
  { path: 'counterparty.labels', type: 'string', description: 'Comma-separated counterparty labels' },
  { path: 'counterparty.is_contract', type: 'boolean', description: 'Counterparty is a contract' },
  { path: 'counterparty.age_days', type: 'number', description: 'Counterparty age in days' },
  { path: 'counterparty.sanctioned', type: 'boolean', description: 'Counterparty listed by an official sanctions source' },
  { path: 'contract.address', type: 'address', description: 'Contract address' },
  { path: 'contract.event', type: 'string', description: 'Decoded contract event name (OwnershipTransferred, Upgraded...)' },
  { path: 'contract.method', type: 'string', description: 'Decoded method name' },
  { path: 'contract.new_owner', type: 'address', description: 'New owner/admin for admin-change events' },
  { path: 'contract.verified', type: 'boolean', description: 'Source code verified' },
  { path: 'contract.age_days', type: 'number', description: 'Contract age in days' },
  { path: 'contract.outflow_ratio', type: 'amount', description: 'Share of contract TVL moved out by the transaction (0..1)' },
  { path: 'approval.owner', type: 'address', description: 'Token owner granting the allowance' },
  { path: 'approval.spender', type: 'address', description: 'Spender receiving the allowance' },
  { path: 'approval.amount', type: 'amount', description: 'Allowance in token units' },
  { path: 'approval.unlimited', type: 'boolean', description: 'Allowance equals the max uint256' },
  { path: 'approval.spender_is_contract', type: 'boolean', description: 'Spender is a contract' },
  { path: 'approval.spender_age_days', type: 'number', description: 'Spender age in days' },
  { path: 'approval.spender_verified', type: 'boolean', description: 'Spender contract source verified' },
  { path: 'signal.type', type: 'enum', description: 'SignalType name', options: ['UNUSUAL_AMOUNT', 'REPETITIVE_TRANSFERS', 'STRUCTURING', 'FAST_FORWARDING', 'BASELINE_DEVIATION', 'DORMANT_REACTIVATION', 'ADDRESS_PROLIFERATION', 'CIRCULAR_FLOW', 'FLOW_CONCENTRATION', 'RAPID_DRAIN', 'SANCTIONED_EXPOSURE', 'FLAGGED_EXPOSURE', 'INDIRECT_EXPOSURE', 'MIXER_EXPOSURE', 'SCAM_EXPOSURE', 'OWNERSHIP_CHANGE', 'DANGEROUS_ADMIN_PERMISSION', 'MINT_CAPABILITY', 'UNUSUAL_APPROVAL', 'MALICIOUS_CONTRACT_INTERACTION'] },
  { path: 'signal.severity', type: 'enum', description: 'INFO|LOW|MEDIUM|HIGH|CRITICAL', options: ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] },
  { path: 'signal.confidence', type: 'amount', description: 'Signal confidence 0..1' },
  { path: 'signal.evidence_level', type: 'enum', description: 'ANOMALY|RISK_SIGNAL|CONFIRMED_BY_SOURCE', options: ['ANOMALY', 'RISK_SIGNAL', 'CONFIRMED_BY_SOURCE'] },
  { path: 'internal.hop', type: 'number', description: 'Number of alert->event hops that produced this event' },
  { path: 'internal.origin_chain', type: 'string', description: 'Comma-separated rule ids that produced this event' },
  { path: 'alert.id', type: 'string', description: 'Originating alert id' },
  { path: 'alert.rule_id', type: 'string', description: 'Originating rule id' },
  { path: 'alert.severity', type: 'enum', description: 'Originating alert severity', options: ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] },
  { path: 'alert.group_key', type: 'string', description: 'Originating alert group key' },
];

/** Fields the backend pipeline populates today (mirrors oci-app PopulatedFields; served live as `available`). */
const POPULATED = new Set([
  'network', 'event.type', 'event.block_number', 'event.time', 'transaction.hash', 'transaction.from', 'transaction.to',
  'transaction.amount', 'transaction.token.symbol', 'transaction.token.contract', 'transaction.token.decimals', 'wallet.address',
  'wallet.balance', 'wallet.risk_score', 'wallet.risk_level', 'wallet.age_days', 'wallet.tx_count', 'wallet.labels', 'wallet.sanctioned',
  'counterparty.address', 'counterparty.risk_score', 'counterparty.labels', 'counterparty.age_days', 'counterparty.sanctioned',
  'signal.type', 'signal.severity', 'signal.confidence', 'signal.evidence_level', 'internal.hop', 'internal.origin_chain', 'alert.id',
  'alert.rule_id', 'alert.severity', 'alert.group_key',
]);
export const DEFAULT_FIELDS: FieldDef[] = CATALOG.map((f) => ({ ...f, available: POPULATED.has(f.path) }));

/**
 * Fallback only (backend unreachable): the builder loads the live list from GET /rules/event-types, which reports which
 * types the pipeline really produces. Mirrors oci-app PopulatedFields.EVENT_TYPES.
 */
export const AVAILABLE_EVENT_TYPES: readonly string[] = ['TOKEN_TRANSFER', 'NATIVE_TRANSFER', 'APPROVAL', 'STABLECOIN_FREEZE', 'STABLECOIN_UNFREEZE', 'DETECTION_SIGNAL'];

/** UI suffix for catalog entries the backend does not populate yet. */
export function availabilityNote(available: boolean | undefined): string {
  return available === false ? ' - not yet available' : '';
}

let seq = 0;
export function newId(prefix = 'n'): string {
  seq += 1;
  return `${prefix}${seq}`;
}

export function newCondition(field = 'transaction.amount', op: Operator = 'gte', value: ConditionValue = ''): Condition {
  return { kind: 'condition', id: newId('c'), field, op, value };
}

export function newGroup(match: 'all' | 'any' | 'not' = 'all', children: (Condition | ConditionGroup)[] = []): ConditionGroup {
  return { kind: 'group', id: newId('g'), match, children };
}

export function emptyRule(): RuleDraft {
  return {
    id: 'new-rule',
    version: '1.0.0',
    name: 'New rule',
    description: '',
    author: 'console',
    enabled: true,
    severity: 'MEDIUM',
    evidenceLevel: 'ANOMALY',
    networks: ['ethereum'],
    eventTypes: ['TOKEN_TRANSFER'],
    when: newGroup('all', [newCondition('transaction.token.symbol', 'eq', 'USDT'), newCondition('transaction.amount', 'gte', '100000')]),
    window: null,
    actions: [{ type: 'alert', target: '' }],
    cooldown: '15m',
    extra: {},
  };
}

