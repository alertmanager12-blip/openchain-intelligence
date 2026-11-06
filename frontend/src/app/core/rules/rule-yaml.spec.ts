import { RuleDraft, emptyRule, newCondition, newGroup } from './rule-model';
import { ruleToYaml, validateDraft, yamlToRule } from './rule-yaml';
import { parse, stringify } from './yaml';

function sample(): RuleDraft {
  return {
    ...emptyRule(),
    id: 'large-usdt-outflow',
    name: 'Large USDT outflow: exchange hot wallet',
    description: 'Flags transfers above 100k USDT # not a comment',
    severity: 'HIGH',
    networks: ['tron', 'ethereum'],
    eventTypes: ['TOKEN_TRANSFER'],
    when: newGroup('all', [
      newCondition('token.symbol', 'eq', 'USDT'),
      newCondition('amount', 'gte', '100000.000001'),
      newGroup('any', [newCondition('to.risk_score', 'gt', 70), newCondition('to', 'has_label', 'mixer'), newCondition('network', 'in', ['tron', 'bsc'])]),
    ]),
    window: { duration: '1h', aggregate: 'sum', field: 'amount', groupBy: 'from', op: 'gt', value: '123456789012345678901234.5' },
    actions: [{ type: 'alert', target: '' }, { type: 'webhook', target: 'wh_ops' }],
    cooldown: '30m',
  };
}

describe('rule YAML serializer', () => {
  it('emits the canonical document with nested AND/OR groups', () => {
    const y = ruleToYaml(sample());
    expect(y).toContain('apiVersion: openchain.io/v1\nkind: Rule\nid: large-usdt-outflow\n');
    expect(y).toContain('name: "Large USDT outflow: exchange hot wallet"');
    expect(y).toContain(['when:', '  all:', '    - field: token.symbol', '      op: eq', '      value: USDT'].join('\n'));
    expect(y).toContain(['    - any:', '        - field: to.risk_score', '          op: gt', '          value: 70'].join('\n'));
    expect(y).toContain('value: [tron, bsc]');
    expect(y).toContain('networks: [tron, ethereum]');
    expect(y).toContain('  - type: webhook\n    target: wh_ops');
  });

  it('keeps amounts exact by quoting them as strings', () => {
    const y = ruleToYaml(sample());
    expect(y).toContain('value: "100000.000001"');
    expect(y).toContain('value: "123456789012345678901234.5"');
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
    const d = { ...sample(), window: null, when: newGroup('all', [newCondition('tx.method', 'exists', null)]) };
    const y = ruleToYaml(d);
    expect(y).not.toContain('window:');
    expect(y).toContain('    - field: tx.method\n      op: exists\n');
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
      newCondition('amount', 'contains', 'x'),
      newCondition('amount', 'gte', '1e5'),
      newCondition('nope', 'eq', 'x'),
      newCondition('amount', 'between', ['10', '1']),
      newGroup('any', []),
    ]);
    const paths = validateDraft(d).map((e) => e.path);
    expect(paths).toEqual(['when.all[0].op', 'when.all[1].value', 'when.all[2].field', 'when.all[3].value', 'when.all[4]']);
  });

  it('checks window, cooldown and action targets', () => {
    const d = { ...sample(), cooldown: '5 minutes', actions: [{ type: 'slack' as const, target: '' }] };
    d.window = { duration: '1w', aggregate: 'sum', field: 'token.symbol', groupBy: null, op: 'gt', value: 'abc' };
    expect(validateDraft(d).map((e) => e.path)).toEqual(['window.duration', 'window.field', 'window.value', 'cooldown', 'actions[0].target']);
  });
});

describe('rule YAML parser', () => {
  it('reports syntax errors with line numbers', () => {
    const r = yamlToRule('apiVersion: openchain.io/v1\nkind: Rule\nwhen:\n  all:\n    - field: "amount\n');
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
    const bad = yamlToRule('apiVersion: openchain.io/v1\nkind: Rule\nid: x1\nname: n\nseverity: HIGH\nnetworks: [ethereum]\nevent_types: [TOKEN_TRANSFER]\nwhen:\n  both: []\ncooldown: 1m\nactions:\n  - type: alert\n');
    expect(bad.errors.map((e) => e.path)).toContain('when');
  });

  it('keeps unsafe big numbers as strings and handles comments and same-indent lists', () => {
    expect(parse('a: 12345678901234567890123 # big\nb: 1.5\nl:\n- x\n- "y # z"\n')).toEqual({ a: '12345678901234567890123', b: 1.5, l: ['x', 'y # z'] });
  });
});
