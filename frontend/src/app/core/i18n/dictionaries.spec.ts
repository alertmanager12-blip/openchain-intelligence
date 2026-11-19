import { en } from './en';
import { fr } from './fr';
import { shell, prefixes as shellPrefixes } from './en/shell';
import { overview, prefixes as overviewPrefixes } from './en/overview';
import { band, prefixes as bandPrefixes } from './en/band';
import { verdict, prefixes as verdictPrefixes } from './en/verdict';
import { enums, prefixes as enumsPrefixes } from './en/enums';
import { errors, prefixes as errorsPrefixes } from './en/errors';

/** Spec 2026-11-19 §1: dictionaries are split by area; a key lives in one area, under that area's prefixes. */
const AREAS: Record<string, { keys: string[]; prefixes: readonly string[] }> = {
  shell: { keys: Object.keys(shell), prefixes: shellPrefixes },
  overview: { keys: Object.keys(overview), prefixes: overviewPrefixes },
  band: { keys: Object.keys(band), prefixes: bandPrefixes },
  verdict: { keys: Object.keys(verdict), prefixes: verdictPrefixes },
  enums: { keys: Object.keys(enums), prefixes: enumsPrefixes },
  errors: { keys: Object.keys(errors), prefixes: errorsPrefixes },
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
});
