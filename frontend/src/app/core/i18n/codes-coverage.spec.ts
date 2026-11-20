import { en } from './en';
import { fr } from './fr';

type Fs = { readFileSync(p: string, e: string): string };

/** docs/api/codes.json is the server's list of stable codes (cwd of the test run is frontend/). */
function codes(): { code: string; template: string }[] {
  const fs = (globalThis as unknown as { process: { getBuiltinModule(n: string): Fs } }).process.getBuiltinModule('node:fs');
  return JSON.parse(fs.readFileSync('../docs/api/codes.json', 'utf8'));
}

describe('server codes', () => {
  it('lists the codes this console must translate', () => {
    expect(codes().length).toBeGreaterThan(200);
  });

  it('has an error.<code> entry in English and in French for every code', () => {
    const missing: string[] = [];
    for (const { code } of codes()) {
      if (!(`error.${code}` in en)) missing.push(`en:${code}`);
      if (!(`error.${code}` in fr)) missing.push(`fr:${code}`);
    }
    expect(missing).toEqual([]);
  });

  it('keeps the placeholders of the English template in the French message', () => {
    const lost: string[] = [];
    for (const { code, template } of codes()) {
      const f = (fr as Record<string, unknown>)[`error.${code}`];
      if (typeof f !== 'string') continue;
      for (const p of template.match(/\{[a-zA-Z]+\}/g) ?? []) if (!f.includes(p)) lost.push(`${code} ${p}`);
    }
    expect(lost).toEqual([]);
  });
});
