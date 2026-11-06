import { EvidenceLevel, FieldDef, NETWORKS, NetworkId, RuleValidationError, SEVERITIES, Severity } from '../models';
import {
  ACTION_TYPES, AGGREGATES, ActionType, Aggregate, Condition, ConditionGroup, ConditionValue, DECIMAL, DEFAULT_FIELDS, DSL_OPERATORS,
  DURATION, EVENT_TYPES, EVIDENCE_LEVELS, OPERATORS_BY_TYPE, Operator, RuleAction, RuleDraft, SEMVER, SLUG, WindowSpec, newCondition,
  newGroup,
} from './rule-model';
import { YamlError, YamlValue, parse, stringify } from './yaml';

/**
 * Builder draft <-> canonical rule document `openchain.io/rule/v1` (the backend DSL, see rules/README.md and
 * oci-alerting RuleValidator). The YAML produced here is accepted as-is by POST /api/v1/rules and /rules/validate.
 */
export const RULE_API_VERSION = 'openchain.io/rule/v1';
const MAX_DEPTH = 4;
const MAX_CONDITIONS = 50;
/** Top-level DSL keys the builder does not edit; preserved verbatim, emitted in this order. */
const EXTRA_KEYS = ['tags', 'dedupKey', 'expectedConfidence', 'documentation', 'sequence', 'examples', 'tests'] as const;
const TOP_KEYS = ['apiVersion', 'id', 'version', 'name', 'description', 'author', 'enabled', 'scope', 'conditions', 'aggregation',
  'severity', 'evidenceLevel', 'cooldown', 'actions', ...EXTRA_KEYS];

const FROM_DSL_OP: Record<string, Operator> = Object.fromEntries(Object.entries(DSL_OPERATORS).map(([k, v]) => [v, k as Operator]));
const LOGIC: Record<ConditionGroup['match'], string> = { all: 'AND', any: 'OR', not: 'NOT' };
const FROM_LOGIC: Record<string, ConditionGroup['match']> = { AND: 'all', OR: 'any', NOT: 'not' };
const WINDOW_OPS: Record<WindowSpec['op'], string> = { gt: 'GREATER_THAN', gte: 'GREATER_THAN_OR_EQUAL' };
const FROM_WINDOW_OP: Record<string, WindowSpec['op']> = { GREATER_THAN: 'gt', GREATER_THAN_OR_EQUAL: 'gte' };

type Obj = { [k: string]: YamlValue };

// ---------------------------------------------------------------- draft -> YAML

function conditionToYaml(c: Condition): Obj {
  const o: Obj = { field: c.field, operator: DSL_OPERATORS[c.op] ?? String(c.op).toUpperCase() };
  if (c.op !== 'exists') o['value'] = c.value as YamlValue;
  if (c.ignoreCase) o['ignoreCase'] = true;
  return o;
}

/** DSL groups list leaves (`rules`) before nested `groups`. */
function groupToYaml(g: ConditionGroup): Obj {
  const o: Obj = { logic: LOGIC[g.match] };
  const leaves = g.children.filter((c): c is Condition => c.kind === 'condition');
  const groups = g.children.filter((c): c is ConditionGroup => c.kind === 'group');
  if (leaves.length) o['rules'] = leaves.map(conditionToYaml);
  if (groups.length) o['groups'] = groups.map(groupToYaml);
  return o;
}

function windowToYaml(w: WindowSpec): Obj {
  const o: Obj = { window: w.duration };
  if (w.groupBy.length) o['groupBy'] = [...w.groupBy];
  o['function'] = w.aggregate.toUpperCase();
  if (w.aggregate !== 'count' && w.field) o['field'] = w.field;
  if (w.aggregate === 'ratio_of_balance' && w.balanceField) o['balanceField'] = w.balanceField;
  o['operator'] = WINDOW_OPS[w.op] ?? w.op;
  o['threshold'] = w.value;
  return o;
}

function actionToYaml(a: RuleAction): Obj {
  return { type: a.type.toUpperCase(), connectorId: a.target };
}

/** Serialises a builder draft to the canonical rule YAML (stable key order, amounts kept as exact strings). */
export function ruleToYaml(d: RuleDraft): string {
  const doc: Obj = { apiVersion: RULE_API_VERSION, id: d.id, version: d.version, name: d.name };
  if (d.description) doc['description'] = d.description;
  doc['author'] = d.author;
  doc['enabled'] = d.enabled;
  doc['scope'] = { networks: [...d.networks], eventTypes: [...d.eventTypes] };
  const sequenceOnly = d.extra['sequence'] !== undefined && d.when.children.length === 0;
  if (!sequenceOnly) doc['conditions'] = groupToYaml(d.when);
  if (d.window) doc['aggregation'] = windowToYaml(d.window);
  doc['severity'] = d.severity;
  doc['evidenceLevel'] = d.evidenceLevel;
  doc['cooldown'] = d.cooldown;
  const actions = d.actions.filter((a) => a.type !== 'alert');
  if (actions.length) doc['actions'] = actions.map(actionToYaml);
  for (const k of EXTRA_KEYS) if (d.extra[k] !== undefined) doc[k] = d.extra[k];
  return stringify(doc);
}

// ---------------------------------------------------------------- validation

function compareDecimal(a: string, b: string): number {
  const norm = (s: string): [bigint, number] => {
    const [i, f = ''] = s.split('.');
    return [BigInt(i + f), f.length];
  };
  const [ai, as] = norm(a);
  const [bi, bs] = norm(b);
  const scale = Math.max(as, bs);
  const x = ai * 10n ** BigInt(scale - as);
  const y = bi * 10n ** BigInt(scale - bs);
  return x === y ? 0 : x < y ? -1 : 1;
}

function validateValue(c: Condition, def: FieldDef, path: string, errors: RuleValidationError[]): void {
  const err = (message: string): void => void errors.push({ path: `${path}.value`, message });
  const v = c.value;
  if (c.op === 'exists') return;
  const scalarOk = (x: unknown): boolean => {
    switch (def.type) {
      case 'amount':
        return typeof x === 'string' && DECIMAL.test(x);
      case 'number':
        return typeof x === 'number' && Number.isFinite(x);
      case 'boolean':
        return typeof x === 'boolean';
      case 'enum':
        return typeof x === 'string' && (def.options ?? []).includes(x);
      case 'timestamp':
        return typeof x === 'string' && !Number.isNaN(Date.parse(x));
      default:
        return typeof x === 'string' && x.trim().length > 0 && x.length <= 256;
    }
  };
  const expected: Record<string, string> = {
    amount: 'an exact decimal such as "1500.25"',
    number: 'a number',
    boolean: 'true or false',
    enum: `one of ${(def.options ?? []).join(', ')}`,
    timestamp: 'an ISO-8601 date-time',
  };
  const want = expected[def.type] ?? 'a non-empty text';
  if (c.op === 'in' || c.op === 'not_in') {
    if (!Array.isArray(v) || v.length === 0) return err('expected a non-empty list');
    if (v.length > 500) return err('lists are limited to 500 values');
    v.forEach((x, i) => !scalarOk(x) && errors.push({ path: `${path}.value[${i}]`, message: `expected ${want}` }));
    return;
  }
  if (c.op === 'between') {
    if (!Array.isArray(v) || v.length !== 2) return err('between expects [min, max]');
    if (!v.every(scalarOk)) return err(`bounds must be ${want}`);
    const [lo, hi] = v as unknown[];
    const inverted =
      def.type === 'amount' ? compareDecimal(lo as string, hi as string) > 0
      : def.type === 'number' ? (lo as number) > (hi as number)
      : Date.parse(lo as string) > Date.parse(hi as string);
    if (inverted) err('min must not exceed max');
    return;
  }
  if (c.op === 'contains' || c.op === 'starts_with' || c.op === 'matches') {
    if (typeof v !== 'string' || !v.trim()) err('expected a non-empty text');
    else if (v.length > 256) err('text is limited to 256 characters');
    return;
  }
  if (Array.isArray(v) || !scalarOk(v)) err(`expected ${want}`);
}

function validateGroup(g: ConditionGroup, path: string, depth: number, catalog: FieldDef[], errors: RuleValidationError[], count: { n: number }): void {
  if (depth > MAX_DEPTH) errors.push({ path, message: `groups can be nested at most ${MAX_DEPTH} levels deep` });
  if (g.children.length === 0) errors.push({ path, message: 'a group needs at least one condition' });
  else if (g.match === 'not' && g.children.length !== 1) errors.push({ path, message: 'a NOT group takes exactly one condition or group' });
  g.children.forEach((ch, i) => {
    const p = `${path}.${g.match}[${i}]`;
    if (ch.kind === 'group') {
      validateGroup(ch, p, depth + 1, catalog, errors, count);
      return;
    }
    count.n++;
    const def = catalog.find((f) => f.path === ch.field);
    if (!def) {
      errors.push({ path: `${p}.field`, message: `unknown field "${ch.field}"` });
      return;
    }
    if (!OPERATORS_BY_TYPE[def.type].includes(ch.op)) {
      errors.push({ path: `${p}.op`, message: `operator "${ch.op}" is not allowed on ${def.type} field "${def.path}"` });
      return;
    }
    if (ch.ignoreCase && (def.type === 'amount' || def.type === 'number' || def.type === 'boolean' || def.type === 'timestamp' || ch.op === 'matches' || ch.op === 'exists'))
      errors.push({ path: `${p}.ignoreCase`, message: 'ignoreCase applies to text equality, lists, contains and starts with only' });
    validateValue(ch, def, p, errors);
  });
}

/** Structural + semantic validation (same limits as the backend). Pure data checks: nothing is evaluated as code. */
export function validateDraft(d: RuleDraft, catalog: FieldDef[] = DEFAULT_FIELDS): RuleValidationError[] {
  const errors: RuleValidationError[] = [];
  if (!SLUG.test(d.id)) errors.push({ path: 'id', message: 'id must be 2-63 chars: lowercase letters, digits and dashes' });
  if (!SEMVER.test(d.version)) errors.push({ path: 'version', message: 'version must look like 1.0.0' });
  if (!d.name.trim()) errors.push({ path: 'name', message: 'name is required' });
  else if (d.name.length > 120) errors.push({ path: 'name', message: 'name is limited to 120 characters' });
  if (!d.author.trim()) errors.push({ path: 'author', message: 'author is required' });
  if (!SEVERITIES.includes(d.severity)) errors.push({ path: 'severity', message: `severity must be one of ${SEVERITIES.join(', ')}` });
  if (!EVIDENCE_LEVELS.includes(d.evidenceLevel)) errors.push({ path: 'evidenceLevel', message: `evidence level must be one of ${EVIDENCE_LEVELS.join(', ')}` });
  // empty networks / event types = any (DSL scope semantics)
  d.networks.forEach((n, i) => !NETWORKS.includes(n) && errors.push({ path: `networks[${i}]`, message: `unknown network "${n}"` }));
  d.eventTypes.forEach((t, i) => !(EVENT_TYPES as readonly string[]).includes(t) && errors.push({ path: `event_types[${i}]`, message: `unknown event type "${t}"` }));
  const count = { n: 0 };
  const sequenceOnly = d.extra['sequence'] !== undefined && d.when.children.length === 0;
  if (!sequenceOnly) validateGroup(d.when, 'when', 1, catalog, errors, count);
  if (count.n > MAX_CONDITIONS) errors.push({ path: 'when', message: `rules are limited to ${MAX_CONDITIONS} conditions` });
  if (d.window) {
    const w = d.window;
    if (d.extra['sequence'] !== undefined) errors.push({ path: 'window', message: 'a rule cannot have both a window and a sequence' });
    if (!DURATION.test(w.duration)) errors.push({ path: 'window.duration', message: 'duration must look like 30s, 15m, 1h or 7d' });
    if (!AGGREGATES.includes(w.aggregate)) errors.push({ path: 'window.aggregate', message: `aggregate must be one of ${AGGREGATES.join(', ')}` });
    else if (w.aggregate !== 'count') {
      const def = catalog.find((f) => f.path === w.field);
      if (!w.field || !def) errors.push({ path: 'window.field', message: `${w.aggregate} needs a known field` });
      else if (w.aggregate !== 'distinct_count' && def.type !== 'amount' && def.type !== 'number')
        errors.push({ path: 'window.field', message: `${w.aggregate} needs a numeric field` });
    }
    if (w.groupBy.length > 3) errors.push({ path: 'window.group_by', message: 'at most 3 group-by fields' });
    w.groupBy.forEach((g) => !catalog.some((f) => f.path === g) && errors.push({ path: 'window.group_by', message: `unknown field "${g}"` }));
    if (!['gt', 'gte'].includes(w.op)) errors.push({ path: 'window.op', message: 'window thresholds support gt and gte only' });
    if (!DECIMAL.test(w.value) || w.value.startsWith('-')) errors.push({ path: 'window.value', message: 'threshold must be an exact decimal >= 0' });
  }
  if (!DURATION.test(d.cooldown)) errors.push({ path: 'cooldown', message: 'cooldown must look like 30s, 15m, 1h or 7d' });
  if (d.actions.length === 0) errors.push({ path: 'actions', message: 'add at least one action' });
  d.actions.forEach((a, i) => {
    if (!ACTION_TYPES.includes(a.type)) errors.push({ path: `actions[${i}].type`, message: `unknown action "${a.type}"` });
    else if (a.type !== 'alert' && !a.target.trim()) errors.push({ path: `actions[${i}].target`, message: `${a.type} action needs a connector id` });
    else if (a.type !== 'alert' && !SLUG.test(a.target)) errors.push({ path: `actions[${i}].target`, message: 'connector ids are lowercase slugs (a-z, 0-9, -)' });
  });
  return errors;
}

// ---------------------------------------------------------------- YAML -> draft

export interface ParsedRule {
  draft: RuleDraft | null;
  errors: RuleValidationError[];
}

class Shape {
  readonly errors: RuleValidationError[] = [];
  isObj(v: YamlValue | undefined): v is Obj {
    return !!v && typeof v === 'object' && !Array.isArray(v);
  }
  str(o: Obj, key: string, path: string, required = true): string {
    const v = o[key];
    if (v === undefined || v === null) {
      if (required) this.errors.push({ path, message: `${key} is required` });
      return '';
    }
    if (typeof v !== 'string' && typeof v !== 'number') {
      this.errors.push({ path, message: `${key} must be a text value` });
      return '';
    }
    return String(v);
  }
  strList(o: Obj, key: string, path: string): string[] {
    const v = o[key];
    if (v === undefined) return [];
    if (typeof v === 'string') return [v];
    if (!Array.isArray(v) || !v.every((x) => typeof x === 'string')) {
      this.errors.push({ path, message: `${key} must be a list of text values` });
      return [];
    }
    return v as string[];
  }
  unknownKeys(o: Obj, allowed: string[], path: string): void {
    for (const k of Object.keys(o)) if (!allowed.includes(k)) this.errors.push({ path: path ? `${path}.${k}` : k, message: `unknown key "${k}"` });
  }
}

function normaliseValue(v: YamlValue | undefined, def: FieldDef | undefined): ConditionValue {
  const conv = (x: YamlValue): ConditionValue => {
    if (def?.type === 'amount' && typeof x === 'number') return String(x);
    if (def?.type === 'number' && typeof x === 'string' && DECIMAL.test(x)) return Number(x);
    return x as ConditionValue;
  };
  if (v === undefined) return null;
  if (Array.isArray(v)) return v.map((x) => conv(x)) as string[];
  return conv(v);
}

function readGroup(v: YamlValue | undefined, path: string, s: Shape, catalog: FieldDef[], depth: number): ConditionGroup {
  if (!s.isObj(v)) {
    s.errors.push({ path, message: 'expected a group: { logic: AND|OR|NOT, rules: [...], groups: [...] }' });
    return newGroup();
  }
  s.unknownKeys(v, ['logic', 'rules', 'groups'], path);
  const match = FROM_LOGIC[String(v['logic'] ?? '').toUpperCase()];
  if (!match) {
    s.errors.push({ path: `${path}.logic`, message: 'logic must be AND, OR or NOT' });
    return newGroup();
  }
  if (depth > MAX_DEPTH + 1) {
    s.errors.push({ path, message: 'nesting too deep' });
    return newGroup(match);
  }
  const children: (Condition | ConditionGroup)[] = [];
  const rules = v['rules'];
  if (rules !== undefined && !Array.isArray(rules)) s.errors.push({ path: `${path}.rules`, message: 'rules must be a list' });
  (Array.isArray(rules) ? rules : []).forEach((it, i) => {
    const p = `${path}.rules[${i}]`;
    if (!s.isObj(it)) {
      s.errors.push({ path: p, message: 'expected a condition { field, operator, value }' });
      return;
    }
    s.unknownKeys(it, ['field', 'operator', 'value', 'ignoreCase'], p);
    const field = s.str(it, 'field', `${p}.field`);
    const dsl = s.str(it, 'operator', `${p}.operator`).toUpperCase();
    const op = FROM_DSL_OP[dsl];
    if (!op) s.errors.push({ path: `${p}.operator`, message: `unknown operator "${dsl}"` });
    const c = newCondition(field, op ?? 'eq', op === 'exists' ? null : normaliseValue(it['value'], catalog.find((f) => f.path === field)));
    if (it['ignoreCase'] === true) c.ignoreCase = true;
    children.push(c);
  });
  const groups = v['groups'];
  if (groups !== undefined && !Array.isArray(groups)) s.errors.push({ path: `${path}.groups`, message: 'groups must be a list' });
  (Array.isArray(groups) ? groups : []).forEach((g, i) => children.push(readGroup(g, `${path}.groups[${i}]`, s, catalog, depth + 1)));
  return newGroup(match, children);
}

function readWindow(w: YamlValue, s: Shape): WindowSpec | null {
  if (!s.isObj(w)) {
    s.errors.push({ path: 'aggregation', message: 'aggregation must be a mapping' });
    return null;
  }
  s.unknownKeys(w, ['window', 'groupBy', 'function', 'field', 'balanceField', 'operator', 'threshold'], 'aggregation');
  const fn = s.str(w, 'function', 'aggregation.function').toLowerCase() as Aggregate;
  const op = FROM_WINDOW_OP[s.str(w, 'operator', 'aggregation.operator').toUpperCase()];
  if (!op) s.errors.push({ path: 'aggregation.operator', message: 'operator must be GREATER_THAN or GREATER_THAN_OR_EQUAL' });
  if (s.isObj(w['window'])) s.errors.push({ path: 'aggregation.window', message: 'use the short duration form, e.g. 3m, in the builder' });
  return {
    duration: s.isObj(w['window']) ? '' : s.str(w, 'window', 'aggregation.window'),
    aggregate: fn,
    field: s.str(w, 'field', 'aggregation.field', false) || null,
    groupBy: s.strList(w, 'groupBy', 'aggregation.groupBy'),
    op: op ?? 'gt',
    value: s.str(w, 'threshold', 'aggregation.threshold'),
    balanceField: s.str(w, 'balanceField', 'aggregation.balanceField', false) || null,
  };
}

/** Parses canonical rule YAML into a draft and validates it. Syntax errors carry their line number. */
export function yamlToRule(text: string, catalog: FieldDef[] = DEFAULT_FIELDS): ParsedRule {
  let doc: YamlValue;
  try {
    doc = parse(text);
  } catch (e) {
    if (e instanceof YamlError) return { draft: null, errors: [{ path: '', line: e.line, message: e.message.replace(/^line \d+: /, '') }] };
    throw e;
  }
  const s = new Shape();
  if (!s.isObj(doc)) return { draft: null, errors: [{ path: '', message: 'the document must be a mapping' }] };
  s.unknownKeys(doc, TOP_KEYS, '');
  if (doc['apiVersion'] !== RULE_API_VERSION) s.errors.push({ path: 'apiVersion', message: `apiVersion must be ${RULE_API_VERSION}` });
  const enabled = doc['enabled'] ?? true;
  if (typeof enabled !== 'boolean') s.errors.push({ path: 'enabled', message: 'enabled must be true or false' });
  const scope = doc['scope'] ?? {};
  if (!s.isObj(scope)) s.errors.push({ path: 'scope', message: 'scope must be a mapping' });
  else s.unknownKeys(scope, ['networks', 'eventTypes'], 'scope');
  const sc: Obj = s.isObj(scope) ? scope : {};

  const extra: Record<string, YamlValue> = {};
  for (const k of EXTRA_KEYS) if (doc[k] !== undefined) extra[k] = doc[k];

  const rawActions = doc['actions'] ?? [];
  const actions: RuleAction[] = [{ type: 'alert', target: '' }];
  if (!Array.isArray(rawActions)) s.errors.push({ path: 'actions', message: 'actions must be a list' });
  else rawActions.forEach((a, i) => {
    if (!s.isObj(a)) {
      s.errors.push({ path: `actions[${i}]`, message: 'expected { type, connectorId }' });
      return;
    }
    s.unknownKeys(a, ['type', 'connectorId'], `actions[${i}]`);
    actions.push({ type: s.str(a, 'type', `actions[${i}].type`).toLowerCase() as ActionType, target: s.str(a, 'connectorId', `actions[${i}].connectorId`) });
  });

  const hasConditions = doc['conditions'] !== undefined;
  if (!hasConditions && extra['sequence'] === undefined) s.errors.push({ path: 'conditions', message: 'conditions are required' });
  const draft: RuleDraft = {
    id: s.str(doc, 'id', 'id'),
    version: s.str(doc, 'version', 'version'),
    name: s.str(doc, 'name', 'name'),
    description: s.str(doc, 'description', 'description', false),
    author: s.str(doc, 'author', 'author'),
    enabled: enabled === true,
    severity: s.str(doc, 'severity', 'severity') as Severity,
    evidenceLevel: (s.str(doc, 'evidenceLevel', 'evidenceLevel', false) || 'ANOMALY') as EvidenceLevel,
    networks: s.strList(sc, 'networks', 'scope.networks') as NetworkId[],
    eventTypes: s.strList(sc, 'eventTypes', 'scope.eventTypes'),
    when: hasConditions ? readGroup(doc['conditions'], 'conditions', s, catalog, 1) : newGroup('all'),
    window: doc['aggregation'] !== undefined ? readWindow(doc['aggregation'], s) : null,
    actions,
    cooldown: s.str(doc, 'cooldown', 'cooldown', false) || '15m',
    extra,
  };
  const errors = [...s.errors, ...validateDraft(draft, catalog).filter((e) => !s.errors.some((x) => x.path === e.path))];
  return { draft, errors };
}
