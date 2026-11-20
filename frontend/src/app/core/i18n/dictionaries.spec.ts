import { en } from './en';
import { fr } from './fr';
import { shell, prefixes as shellPrefixes } from './en/shell';
import { overview, prefixes as overviewPrefixes } from './en/overview';
import { band, prefixes as bandPrefixes } from './en/band';
import { verdict, prefixes as verdictPrefixes } from './en/verdict';
import { enums, prefixes as enumsPrefixes } from './en/enums';
import { errors, prefixes as errorsPrefixes } from './en/errors';
import { shared, prefixes as sharedPrefixes } from './en/shared';
import { ALERT_STATUSES, SEVERITIES } from '../models';

/** Spec 2026-11-19 §1: dictionaries are split by area; a key lives in one area, under that area's prefixes. */
const AREAS: Record<string, { keys: string[]; prefixes: readonly string[] }> = {
  shell: { keys: Object.keys(shell), prefixes: shellPrefixes },
  overview: { keys: Object.keys(overview), prefixes: overviewPrefixes },
  band: { keys: Object.keys(band), prefixes: bandPrefixes },
  verdict: { keys: Object.keys(verdict), prefixes: verdictPrefixes },
  enums: { keys: Object.keys(enums), prefixes: enumsPrefixes },
  errors: { keys: Object.keys(errors), prefixes: errorsPrefixes },
  shared: { keys: Object.keys(shared), prefixes: sharedPrefixes },
};

describe('dictionaries by area', () => {
  it('defines no key in two areas', () => {
    const total = Object.values(AREAS).reduce((n, a) => n + a.keys.length, 0);
    expect(total).toBe(Object.keys(en).length);
  });

  for (const [name, a] of Object.entries(AREAS)) {
    it(`keeps every ${name} key under its prefixes`, () => {
      for (const k of a.keys) expect(a.prefixes.some((p) => k.startsWith(p)), `${k} in ${name}`).toBe(true);
    });
  }

  it('writes French typography: U+202F before ? ! ; :, « » instead of straight quotes', () => {
    for (const [k, e] of Object.entries(fr)) {
      if (typeof e !== 'string') continue;
      expect(/ [?!;:]/.test(e), `${k}: ASCII space before punctuation`).toBe(false);
      expect(e.includes('"'), `${k}: straight double quote`).toBe(false);
    }
  });

  describe('enum names', () => {
    const wanted = [
      ...['OBSERVED', 'INFERRED', 'CONFIRMED', 'PREDICTED', 'SIMULATED'].map((v) => `enum.level.${v}`),
      ...['ANOMALY', 'RISK_SIGNAL', 'CONFIRMED_BY_SOURCE'].map((v) => `enum.evidence.${v}`),
      ...SEVERITIES.map((v) => `enum.severity.${v}`),
      ...ALERT_STATUSES.map((v) => `enum.status.${v}`),
      ...['UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((v) => `enum.risk.${v}`),
      ...['UNUSUAL_AMOUNT', 'STRUCTURING', 'FAST_FORWARDING', 'DORMANT_REACTIVATION', 'CIRCULAR_FLOW', 'FLOW_CONCENTRATION',
        'RAPID_DRAIN', 'SANCTIONED_EXPOSURE', 'FLAGGED_EXPOSURE', 'INDIRECT_EXPOSURE', 'MIXER_EXPOSURE', 'SCAM_EXPOSURE',
        'DUSTING', 'OWNERSHIP_CHANGE', 'UNUSUAL_APPROVAL'].map((v) => `enum.signal.${v}`),
      ...['SANCTIONED', 'MIXER', 'SCAM', 'PHISHING', 'RANSOMWARE', 'STOLEN_FUNDS', 'DARKNET_MARKET', 'FLAGGED_COMMUNITY',
        'ISSUER_FROZEN', 'EXCHANGE', 'BRIDGE', 'DEFI_PROTOCOL'].map((v) => `enum.category.${v}`),
      'enum.factor.listedAs', 'enum.factor.ISSUER_FROZEN',
    ];
    for (const k of wanted) {
      it(`has ${k} in English and French`, () => {
        expect(Object.keys(en)).toContain(k);
        expect(Object.keys(fr)).toContain(k);
      });
    }

    for (const name of ['levels.ts', 'explain.ts', 'ui.ts']) {
      it(`${name} holds no French literal`, () => {
        const fs = (globalThis as unknown as { process: { getBuiltinModule(n: string): { readFileSync(p: string, e: string): string } } }).process.getBuiltinModule('node:fs');
        const src = fs.readFileSync(`src/app/shared/${name}`, 'utf8');
        expect(/[éèàù]/.test(src)).toBe(false);
      });
    }
  });
});
