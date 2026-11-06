import { FieldDef, NETWORKS, NetworkId, RuleValidationError, SEVERITIES, Severity } from '../models';
import {
  ACTION_TYPES, AGGREGATES, ActionType, Aggregate, Condition, ConditionGroup, ConditionValue, DECIMAL, DEFAULT_FIELDS, DURATION,
  EVENT_TYPES, OPERATORS_BY_TYPE, Operator, RuleDraft, SLUG, WindowSpec, newCondition, newGroup,
} from './rule-model';
import { YamlError, YamlValue, parse, stringify } from './yaml';

export const RULE_API_VERSION = 'openchain.io/v1';
const MAX_DEPTH = 4;
const MAX_CONDITIONS = 50;

type Obj = { [k: string]: YamlValue };

// ---------------------------------------------------------------- draft -> YAML

function conditionToYaml(c: Condition): Obj {
  const o: Obj = { field: c.field, op: c.op };
  if (c.op !== 'exists') o['value'] = c.value as YamlValue;
  return o;
}

function groupToYaml(g: ConditionGroup): Obj {
  return { [g.match]: g.children.map((ch) => (ch.kind === 'group' ? groupToYaml(ch) : conditionToYaml(ch))) };
}

function windowToYaml(w: WindowSpec): Obj {
  const o: Obj = { duration: w.duration, aggregate: w.aggregate };
  if (w.field) o['field'] = w.field;
  if (w.groupBy) o['group_by'] = w.groupBy;
  o['op'] = w.op;
  o['value'] = w.value;
  return o;
}

/** Serialises a builder draft to the canonical rule YAML (stable key order, amounts kept as exact strings). */
export function ruleToYaml(d: RuleDraft): string {
  const doc: Obj = {
    apiVersion: RULE_API_VERSION,
    kind: 'Rule',
    id: d.id,
    name: d.name,
  };
  if (d.description) doc['description'] = d.description;
  doc['enabled'] = d.enabled;
  doc['severity'] = d.severity;
  doc['networks'] = [...d.networks];
  doc['event_types'] = [...d.eventTypes];
  doc['when'] = groupToYaml(d.when);
  if (d.window) doc['window'] = windowToYaml(d.window);
  doc['cooldown'] = d.cooldown;
  doc['actions'] = d.actions.map((a): Obj => (a.target ? { type: a.type, target: a.target } : { type: a.type }));
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
  if (c.op === 'has_label') {
    if (typeof v !== 'string' || !v.trim()) err('expected a label category, e.g. "mixer"');
    return;
  }
  if (Array.isArray(v) || !scalarOk(v)) err(`expected ${want}`);
}

function validateGroup(g: ConditionGroup, path: string, depth: number, catalog: FieldDef[], errors: RuleValidationError[], count: { n: number }): void {
  if (depth > MAX_DEPTH) errors.push({ path, message: `groups can be nested at most ${MAX_DEPTH} levels deep` });
  if (g.children.length === 0) errors.push({ path, message: 'a group needs at least one condition' });
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
    validateValue(ch, def, p, errors);
  });
}

/** Structural + semantic validation. Pure data checks: nothing in a rule is ever evaluated as code. */
export function validateDraft(d: RuleDraft, catalog: FieldDef[] = DEFAULT_FIELDS): RuleValidationError[] {
  const errors: RuleValidationError[] = [];
  if (!SLUG.test(d.id)) errors.push({ path: 'id', message: 'id must be 2-63 chars: lowercase letters, digits and dashes' });
  if (!d.name.trim()) errors.push({ path: 'name', message: 'name is required' });
  else if (d.name.length > 120) errors.push({ path: 'name', message: 'name is limited to 120 characters' });
  if (!SEVERITIES.includes(d.severity)) errors.push({ path: 'severity', message: `severity must be one of ${SEVERITIES.join(', ')}` });
  if (d.networks.length === 0) errors.push({ path: 'networks', message: 'select at least one network' });
  d.networks.forEach((n, i) => !NETWORKS.includes(n) && errors.push({ path: `networks[${i}]`, message: `unknown network "${n}"` }));
  if (d.eventTypes.length === 0) errors.push({ path: 'event_types', message: 'select at least one event type' });
  d.eventTypes.forEach((t, i) => !(EVENT_TYPES as readonly string[]).includes(t) && errors.push({ path: `event_types[${i}]`, message: `unknown event type "${t}"` }));
  const count = { n: 0 };
  validateGroup(d.when, 'when', 1, catalog, errors, count);
  if (count.n > MAX_CONDITIONS) errors.push({ path: 'when', message: `rules are limited to ${MAX_CONDITIONS} conditions` });
  if (d.window) {
    const w = d.window;
    if (!DURATION.test(w.duration)) errors.push({ path: 'window.duration', message: 'duration must look like 30s, 15m, 1h or 7d' });
    if (!AGGREGATES.includes(w.aggregate)) errors.push({ path: 'window.aggregate', message: `aggregate must be one of ${AGGREGATES.join(', ')}` });
    if (w.aggregate !== 'count') {
      const def = catalog.find((f) => f.path === w.field);
      if (!w.field || !def) errors.push({ path: 'window.field', message: `${w.aggregate} needs a known field` });
      else if (w.aggregate !== 'distinct_count' && def.type !== 'amount' && def.type !== 'number')
        errors.push({ path: 'window.field', message: `${w.aggregate} needs a numeric field` });
    }
    if (w.groupBy && !catalog.some((f) => f.path === w.groupBy)) errors.push({ path: 'window.group_by', message: `unknown field "${w.groupBy}"` });
    if (!['gt', 'gte', 'lt', 'lte', 'eq'].includes(w.op)) errors.push({ path: 'window.op', message: 'window operator must be gt, gte, lt, lte or eq' });
    if (!DECIMAL.test(w.value)) errors.push({ path: 'window.value', message: 'threshold must be an exact decimal' });
  }
  if (!DURATION.test(d.cooldown)) errors.push({ path: 'cooldown', message: 'cooldown must look like 30s, 15m, 1h or 7d' });
  if (d.actions.length === 0) errors.push({ path: 'actions', message: 'add at least one action' });
  d.actions.forEach((a, i) => {
    if (!ACTION_TYPES.includes(a.type)) errors.push({ path: `actions[${i}].type`, message: `unknown action "${a.type}"` });
    else if (a.type !== 'alert' && !a.target.trim()) errors.push({ path: `actions[${i}].target`, message: `${a.type} action needs a target` });
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
    s.errors.push({ path, message: 'expected a group: { all: [...] } or { any: [...] }' });
    return newGroup();
  }
  const keys = Object.keys(v);
  if (keys.length !== 1 || (keys[0] !== 'all' && keys[0] !== 'any')) {
    s.errors.push({ path, message: 'a group has exactly one key: all (AND) or any (OR)' });
    return newGroup();
  }
  const match = keys[0] as 'all' | 'any';
  const items = v[match];
  if (!Array.isArray(items)) {
    s.errors.push({ path: `${path}.${match}`, message: 'expected a list of conditions' });
    return newGroup(match);
  }
  if (depth > MAX_DEPTH + 1) {
    s.errors.push({ path, message: 'nesting too deep' });
    return newGroup(match);
  }
  const children = items.map((it, i) => {
    const p = `${path}.${match}[${i}]`;
    if (s.isObj(it) && ('all' in it || 'any' in it)) return readGroup(it, p, s, catalog, depth + 1);
    if (!s.isObj(it)) {
      s.errors.push({ path: p, message: 'expected a condition { field, op, value } or a nested group' });
      return newCondition();
    }
    s.unknownKeys(it, ['field', 'op', 'value'], p);
    const field = s.str(it, 'field', `${p}.field`);
    const op = s.str(it, 'op', `${p}.op`) as Operator;
    return newCondition(field, op, normaliseValue(it['value'], catalog.find((f) => f.path === field)));
  });
  return newGroup(match, children);
}

/** Parses rule YAML into a draft and validates it. Syntax errors carry their line number. */
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
  s.unknownKeys(doc, ['apiVersion', 'kind', 'id', 'name', 'description', 'enabled', 'severity', 'networks', 'event_types', 'when', 'window', 'cooldown', 'actions'], '');
  if (doc['apiVersion'] !== RULE_API_VERSION) s.errors.push({ path: 'apiVersion', message: `apiVersion must be ${RULE_API_VERSION}` });
  if (doc['kind'] !== 'Rule') s.errors.push({ path: 'kind', message: 'kind must be Rule' });
  const enabled = doc['enabled'] ?? true;
  if (typeof enabled !== 'boolean') s.errors.push({ path: 'enabled', message: 'enabled must be true or false' });

  let window: WindowSpec | null = null;
  const w = doc['window'];
  if (w !== undefined && w !== null) {
    if (!s.isObj(w)) s.errors.push({ path: 'window', message: 'window must be a mapping' });
    else {
      s.unknownKeys(w, ['duration', 'aggregate', 'field', 'group_by', 'op', 'value'], 'window');
      window = {
        duration: s.str(w, 'duration', 'window.duration'),
        aggregate: s.str(w, 'aggregate', 'window.aggregate') as Aggregate,
        field: s.str(w, 'field', 'window.field', false) || null,
        groupBy: s.str(w, 'group_by', 'window.group_by', false) || null,
        op: (s.str(w, 'op', 'window.op') || 'gt') as WindowSpec['op'],
        value: s.str(w, 'value', 'window.value'),
      };
    }
  }

  const rawActions = doc['actions'];
  const actions = Array.isArray(rawActions)
    ? rawActions.map((a, i) => {
        if (!s.isObj(a)) {
          s.errors.push({ path: `actions[${i}]`, message: 'expected { type, target }' });
          return { type: 'alert' as ActionType, target: '' };
        }
        s.unknownKeys(a, ['type', 'target'], `actions[${i}]`);
        return { type: s.str(a, 'type', `actions[${i}].type`) as ActionType, target: s.str(a, 'target', `actions[${i}].target`, false) };
      })
    : (s.errors.push({ path: 'actions', message: 'actions must be a list' }), []);

  const draft: RuleDraft = {
    id: s.str(doc, 'id', 'id'),
    name: s.str(doc, 'name', 'name'),
    description: s.str(doc, 'description', 'description', false),
    enabled: enabled === true,
    severity: s.str(doc, 'severity', 'severity') as Severity,
    networks: s.strList(doc, 'networks', 'networks') as NetworkId[],
    eventTypes: s.strList(doc, 'event_types', 'event_types'),
    when: readGroup(doc['when'], 'when', s, catalog, 1),
    window,
    actions,
    cooldown: s.str(doc, 'cooldown', 'cooldown'),
  };
  const errors = [...s.errors, ...validateDraft(draft, catalog).filter((e) => !s.errors.some((x) => x.path === e.path))];
  return { draft, errors };
}
