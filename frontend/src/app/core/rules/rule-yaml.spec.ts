import { BUILDER_RULE_YAML, builderFixtureDraft } from './fixtures/builder-rule.fixture';
import { DEFAULT_FIELDS, RuleDraft, availabilityNote, newCondition, newGroup } from './rule-model';
import { ruleToYaml, validateDraft, yamlToRule } from './rule-yaml';
import { parse, stringify } from './yaml';

function sample(): RuleDraft {
  return builderFixtureDraft();
}

describe('rule YAML serializer', () => {
  it('emits exactly the backend contract fixture (openchain.io/rule/v1)', () => {
    expect(ruleToYaml(sample())).toBe(BUILDER_RULE_YAML);
  });

  it('emits the canonical DSL structure with nested AND/OR/NOT groups', () => {
    const y = ruleToYaml(sample());
    expect(y).toContain('apiVersion: openchain.io/rule/v1\nid: builder-large-usdt-outflow\nversion: 1.0.0\n');
    expect(y).toContain('scope:\n  networks: [tron, ethereum]\n  eventTypes: [TOKEN_TRANSFER]');
    expect(y).toContain(['conditions:', '  logic: AND', '  rules:', '    - field: transaction.token.symbol', '      operator: EQUALS', '      value: USDT'].join('\n'));
    expect(y).toContain(['    - logic: OR', '      rules:', '        - field: counterparty.risk_score', '          operator: GREATER_THAN', '          value: 70'].join('\n'));
    expect(y).toContain('    - logic: NOT');
    expect(y).toContain('value: [tron, bsc]');
    expect(y).toContain('actions:\n  - type: WEBHOOK\n    connectorId: default-webhook');
    expect(y).not.toContain('type: alert');
  });

  it('keeps amounts exact by quoting them as strings', () => {
    const y = ruleToYaml(sample());
    expect(y).toContain('value: "100000.000001"');
    expect(y).toContain('threshold: "123456789012345678901234.5"');
  });

  it('quotes strings that would otherwise change type or meaning', () => {
    expect(stringify({ a: 'true', b: '', c: 'x: y', d: '# hi', e: '0x1f', f: 'null' })).toBe(
      'a: "true"\nb: ""\nc: "x: y"\nd: "# hi"\ne: "0x1f"\nf: "null"\n',
    );
  });

  it('round-trips draft -> YAML -> draft without loss', () => {
    const d = sample();
    const parsed = yamlToRule(ruleToYaml(d));
    expect(parsed.errors).toEqual([]);
    expect(ruleToYaml(parsed.draft!)).toBe(ruleToYaml(d));
    expect(parsed.draft!.window!.value).toBe('123456789012345678901234.5');
  });

  it('omits value for exists and window when absent', () => {
    const d = { ...sample(), window: null, when: newGroup('all', [newCondition('transaction.method', 'exists', null)]) };
    const y = ruleToYaml(d);
    expect(y).not.toContain('aggregation:');
    expect(y).toContain('    - field: transaction.method\n      operator: EXISTS\n');
    expect(yamlToRule(y).errors).toEqual([]);
  });
});

describe('rule validation', () => {
  it('accepts the sample rule', () => {
    expect(validateDraft(sample())).toEqual([]);
  });

  it('reports operator/type mismatches and bad values with precise paths', () => {
    const d = sample();
    d.when = newGroup('all', [
      newCondition('transaction.amount', 'contains', 'x'),
      newCondition('transaction.amount', 'gte', '1e5'),
      newCondition('nope', 'eq', 'x'),
      newCondition('transaction.amount', 'between', ['10', '1']),
      newGroup('any', []),
      newGroup('not', [newCondition('transaction.success', 'eq', true), newCondition('transaction.success', 'eq', false)]),
    ]);
    const paths = validateDraft(d).map((e) => e.path);
    expect(paths).toEqual(['when.all[0].op', 'when.all[1].value', 'when.all[2].field', 'when.all[3].value', 'when.all[4]', 'when.all[5]']);
  });

  it('checks window, cooldown and action targets', () => {
    const d = { ...sample(), cooldown: '5 minutes', actions: [{ type: 'slack' as const, target: '' }] };
    d.window = { duration: '1w', aggregate: 'sum', field: 'transaction.token.symbol', groupBy: [], op: 'gt', value: 'abc' };
    expect(validateDraft(d).map((e) => e.path)).toEqual(['window.duration', 'window.field', 'window.value', 'cooldown', 'actions[0].target']);
  });
});

describe('rule YAML parser', () => {
  it('reports syntax errors with line numbers', () => {
    const r = yamlToRule('apiVersion: openchain.io/rule/v1\nconditions:\n  logic: AND\n  rules:\n    - field: "transaction.amount\n');
    expect(r.draft).toBeNull();
    expect(r.errors[0]).toMatchObject({ line: 5 });
  });

  it('rejects unsupported YAML features instead of interpreting them', () => {
    expect(() => parse('a: &x 1')).toThrow(/unsupported/);
    expect(() => parse('a: !!js/function "f"')).toThrow(/unsupported/);
    expect(() => parse('__proto__: 1')).toThrow(/forbidden/);
    expect(() => parse('a:\n\tb: 1')).toThrow(/tabs/);
  });

  it('reports unknown keys and structural problems', () => {
    const y = ruleToYaml(sample()).replace('cooldown: 30m', 'cooldown: 30m\nscript: rm -rf');
    expect(yamlToRule(y).errors).toContainEqual({ path: 'script', message: 'unknown key "script"' });
    const bad = yamlToRule('apiVersion: openchain.io/rule/v1\nid: x1\nversion: 1.0.0\nname: n\nauthor: a\nseverity: HIGH\nscope:\n  networks: [ethereum]\nconditions:\n  logic: BOTH\ncooldown: 1m\n');
    expect(bad.errors.map((e) => e.path)).toContain('conditions.logic');
  });

  it('keeps unsafe big numbers as strings and handles comments and same-indent lists', () => {
    expect(parse('a: 12345678901234567890123 # big\nb: 1.5\nl:\n- x\n- "y # z"\n')).toEqual({ a: '12345678901234567890123', b: 1.5, l: ['x', 'y # z'] });
  });
});

describe('rule YAML parser (backend DSL)', () => {
  it('reads a repository-style rule and keeps builder-foreign keys verbatim', () => {
    const y = [
      'apiVersion: openchain.io/rule/v1', 'id: fund-split', 'version: 1.2.0', 'name: Fund splitting', 'author: community', 'enabled: true',
      'tags: [generic, splitting]', 'scope:', '  eventTypes: [TOKEN_TRANSFER]', 'conditions:', '  logic: AND', '  rules:',
      '    - field: transaction.to', '      operator: IN', '      ignoreCase: true', '      value: [TAbc, TDef]',
      'aggregation:', '  window: 3m', '  groupBy: [transaction.from]', '  function: DISTINCT_COUNT', '  field: transaction.to',
      '  operator: GREATER_THAN', '  threshold: 10', 'severity: MEDIUM', 'dedupKey: "{{rule.id}}:{{group.key}}"', '',
    ].join('\n');
    const r = yamlToRule(y);
    expect(r.errors).toEqual([]);
    const d = r.draft!;
    expect(d.version).toBe('1.2.0');
    expect(d.window).toMatchObject({ aggregate: 'distinct_count', groupBy: ['transaction.from'], op: 'gt', value: '10' });
    expect((d.when.children[0] as { ignoreCase?: boolean }).ignoreCase).toBe(true);
    expect(d.extra).toEqual({ tags: ['generic', 'splitting'], dedupKey: '{{rule.id}}:{{group.key}}' });
    const again = ruleToYaml({ ...d, networks: ['tron'] });
    expect(again).toContain('tags: [generic, splitting]');
    expect(again).toContain('dedupKey: "{{rule.id}}:{{group.key}}"');
    expect(again).toContain('ignoreCase: true');
  });

  it('rejects the legacy console format', () => {
    const r = yamlToRule('apiVersion: openchain.io/v1\nkind: Rule\nid: x1\nname: n\nwhen:\n  all: []\n');
    expect(r.errors.map((e) => e.path)).toEqual(expect.arrayContaining(['apiVersion', 'kind', 'when']));
  });
});

describe('field catalog availability', () => {
  it('flags catalog fields the backend pipeline does not populate yet', () => {
    const byPath = new Map(DEFAULT_FIELDS.map((f) => [f.path, f]));
    expect(byPath.get('transaction.amount')?.available).toBe(true);
    expect(byPath.get('counterparty.labels')?.available).toBe(true);
    expect(byPath.get('transaction.amount_usd')?.available).toBe(false);
    expect(byPath.get('approval.unlimited')?.available).toBe(false);
    expect(availabilityNote(false)).toContain('not yet available');
    expect(availabilityNote(true)).toBe('');
  });
});
