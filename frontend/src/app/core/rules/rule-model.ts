import { FieldDef, FieldType, NetworkId, Severity } from '../models';

/**
 * Declarative rule model edited by the no-code builder. It is pure data: the backend evaluates it with a
 * fixed set of operators. Nothing here is ever executed as code (no expressions, no scripting).
 */
export type Operator =
  | 'eq' | 'neq' | 'gt' | 'gte' | 'lt' | 'lte' | 'between'
  | 'in' | 'not_in' | 'contains' | 'starts_with' | 'has_label' | 'exists';

export type ConditionValue = string | number | boolean | string[] | null;

export interface Condition {
  kind: 'condition';
  id: string;
  field: string;
  op: Operator;
  value: ConditionValue;
}

export interface ConditionGroup {
  kind: 'group';
  id: string;
  /** all = AND, any = OR. */
  match: 'all' | 'any';
  children: (Condition | ConditionGroup)[];
}

export type Aggregate = 'count' | 'sum' | 'avg' | 'min' | 'max' | 'distinct_count';
export const AGGREGATES: readonly Aggregate[] = ['count', 'sum', 'avg', 'min', 'max', 'distinct_count'];

export interface WindowSpec {
  duration: string;
  aggregate: Aggregate;
  /** Field aggregated; not needed for count. */
  field: string | null;
  groupBy: string | null;
  op: 'gt' | 'gte' | 'lt' | 'lte' | 'eq';
  value: string;
}

export type ActionType = 'alert' | 'webhook' | 'slack' | 'discord' | 'telegram' | 'email';
export const ACTION_TYPES: readonly ActionType[] = ['alert', 'webhook', 'slack', 'discord', 'telegram', 'email'];

export interface RuleAction {
  type: ActionType;
  /** Connector id (webhook/slack/...) or recipient; empty for a plain alert. */
  target: string;
}

export interface RuleDraft {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  severity: Severity;
  networks: NetworkId[];
  eventTypes: string[];
  when: ConditionGroup;
  window: WindowSpec | null;
  actions: RuleAction[];
  cooldown: string;
}

export const EVENT_TYPES = ['TOKEN_TRANSFER', 'NATIVE_TRANSFER', 'CONTRACT_CALL', 'CONTRACT_ADMIN_CHANGE', 'APPROVAL', 'RISK_SCORE_CHANGED', 'DETECTION_SIGNAL'] as const;

export const OPERATORS_BY_TYPE: Record<FieldType, Operator[]> = {
  string: ['eq', 'neq', 'in', 'not_in', 'contains', 'starts_with', 'exists'],
  address: ['eq', 'neq', 'in', 'not_in', 'has_label', 'exists'],
  amount: ['gt', 'gte', 'lt', 'lte', 'eq', 'neq', 'between'],
  number: ['gt', 'gte', 'lt', 'lte', 'eq', 'neq', 'between'],
  boolean: ['eq'],
  enum: ['eq', 'neq', 'in', 'not_in'],
  timestamp: ['gt', 'gte', 'lt', 'lte', 'between'],
};

export const OPERATOR_LABELS: Record<Operator, string> = {
  eq: 'equals', neq: 'not equals', gt: '>', gte: '>=', lt: '<', lte: '<=', between: 'between',
  in: 'is one of', not_in: 'is not one of', contains: 'contains', starts_with: 'starts with',
  has_label: 'has label category', exists: 'is present',
};

export const DURATION = /^[1-9]\d*(s|m|h|d)$/;
export const DECIMAL = /^-?\d+(\.\d+)?$/;
export const SLUG = /^[a-z0-9][a-z0-9-]{1,62}$/;

/**
 * Default field catalog, aligned with io.openchain.core.event.ChainEvent fields. The backend may serve its
 * own catalog at /rules/fields; this one is used when it is unavailable.
 */
export const DEFAULT_FIELDS: FieldDef[] = [
  { path: 'network', type: 'enum', description: 'Network the event happened on', options: ['tron', 'ethereum', 'bsc', 'polygon', 'bitcoin', 'solana'] },
  { path: 'from', type: 'address', description: 'Sender address' },
  { path: 'to', type: 'address', description: 'Recipient address' },
  { path: 'token.symbol', type: 'string', description: 'Token symbol, e.g. USDT' },
  { path: 'token.contract', type: 'address', description: 'Token contract address (empty for native coin)' },
  { path: 'amount', type: 'amount', description: 'Transfer amount in token units (exact decimal)' },
  { path: 'amount_usd', type: 'amount', description: 'Indicative USD value at event time' },
  { path: 'tx.success', type: 'boolean', description: 'Whether the transaction succeeded' },
  { path: 'tx.method', type: 'string', description: 'Called method selector, e.g. 0xa9059cbb' },
  { path: 'contract.is_new', type: 'boolean', description: 'Counterparty contract deployed less than 24h ago' },
  { path: 'from.risk_score', type: 'number', description: 'Current risk score of the sender (0-100)' },
  { path: 'to.risk_score', type: 'number', description: 'Current risk score of the recipient (0-100)' },
  { path: 'from.age_days', type: 'number', description: 'Days since the sender was first seen' },
  { path: 'signal.type', type: 'enum', description: 'Detection signal type', options: ['UNUSUAL_AMOUNT', 'STRUCTURING', 'FAST_FORWARDING', 'DORMANT_REACTIVATION', 'CIRCULAR_FLOW', 'RAPID_DRAIN', 'SANCTIONED_EXPOSURE', 'MIXER_EXPOSURE', 'SCAM_EXPOSURE', 'OWNERSHIP_CHANGE', 'UNUSUAL_APPROVAL'] },
  { path: 'signal.evidence_level', type: 'enum', description: 'Evidence level of the signal', options: ['ANOMALY', 'RISK_SIGNAL', 'CONFIRMED_BY_SOURCE'] },
  { path: 'event_time', type: 'timestamp', description: 'Block timestamp (ISO-8601)' },
];

let seq = 0;
export function newId(prefix = 'n'): string {
  seq += 1;
  return `${prefix}${seq}`;
}

export function newCondition(field = 'amount', op: Operator = 'gte', value: ConditionValue = ''): Condition {
  return { kind: 'condition', id: newId('c'), field, op, value };
}

export function newGroup(match: 'all' | 'any' = 'all', children: (Condition | ConditionGroup)[] = []): ConditionGroup {
  return { kind: 'group', id: newId('g'), match, children };
}

export function emptyRule(): RuleDraft {
  return {
    id: 'new-rule',
    name: 'New rule',
    description: '',
    enabled: true,
    severity: 'MEDIUM',
    networks: ['ethereum'],
    eventTypes: ['TOKEN_TRANSFER'],
    when: newGroup('all', [newCondition('token.symbol', 'eq', 'USDT'), newCondition('amount', 'gte', '100000')]),
    window: null,
    actions: [{ type: 'alert', target: '' }],
    cooldown: '15m',
  };
}

export function fieldType(path: string, catalog: FieldDef[]): FieldType | undefined {
  return catalog.find((f) => f.path === path)?.type;
}
