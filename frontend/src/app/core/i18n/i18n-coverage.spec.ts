import { COVERAGE_ALLOWLIST } from './coverage-allowlist';

type Fs = {
  readdirSync(p: string, o: { withFileTypes: true }): { name: string; isDirectory(): boolean }[];
  readFileSync(p: string, e: string): string;
};

const ROOT = 'src/app';
const ATTRS = ['aria-label', 'title', 'placeholder', 'alt', 'matTooltip'];
const BOUND = ['attr.aria-label', 'attr.title', 'attr.label', 'attr.placeholder', 'aria-label', 'title', 'placeholder', 'matTooltip'];

function fs(): Fs {
  return (globalThis as unknown as { process: { getBuiltinModule(n: string): Fs } }).process.getBuiltinModule('node:fs');
}

function walk(dir: string, out: string[] = []): string[] {
  for (const e of fs().readdirSync(dir, { withFileTypes: true })) {
    const p = `${dir}/${e.name}`;
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

/** Templates of a source: the html file itself, or every template: `...` of a component. */
function templatesOf(path: string, src: string): string[] {
  if (path.endsWith('.html')) return [src];
  const out: string[] = [];
  for (const m of src.matchAll(/template:\s*`((?:[^`\\]|\\.)*)`/g)) out.push(m[1]);
  return out;
}

function words(chunk: string, tokens: RegExp[]): string[] {
  return (chunk.match(/[A-Za-z][A-Za-z'’-]*/g) ?? []).filter((w) => w.replace(/[^A-Za-z]/g, '').length >= 3 && !tokens.some((t) => t.test(w)));
}

/** Literal user-visible English in a template: text between tags and literal values of the visible attributes. */
export function findings(template: string, tokens: RegExp[] = COVERAGE_ALLOWLIST.tokens): string[] {
  const found: string[] = [];
  const attrRe = new RegExp(`(?:^|[\\s])(${ATTRS.join('|')})="([^"]*)"`, 'g');
  for (const m of template.matchAll(attrRe)) if (words(m[2].replace(/\{\{[\s\S]*?\}\}/g, ' '), tokens).length) found.push(m[2]);
  // string literals inside bound visible attributes; the argument of i18n.t(...) / translate(...) is a key, not text
  const boundRe = new RegExp(`\\[(?:${BOUND.map((a) => a.replace('.', '\\.')).join('|')})\\]="([^"]*)"`, 'g');
  for (const m of template.matchAll(boundRe)) {
    const expr = m[1].replace(/\b(?:i18n\.t|t|translate)\(\s*(?:'[^']*'|[^,)]*)/g, ' ');
    const lits = [...expr.matchAll(/'((?:[^'\\]|\\.)*)'/g)].map((l) => l[1]);
    if (lits.some((l) => !/^[a-z]+(\.[A-Za-z]+)+$/.test(l) && words(l, tokens).length)) found.push(m[1]);
  }
  const text = template
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/\{\{[\s\S]*?\}\}/g, ' ')
    .replace(/<(script|style)[\s\S]*?<\/\1>/g, ' ')
    .replace(/<[^>]*>/g, '\u0000')
    .replace(/@(?:if|else if|else|for|switch|case|default|empty|placeholder|loading|error|defer)\b[^{}]*(?:\([^)]*\))?[^{}]*\{/g, ' ')
    .replace(/@let\b[^;]*;/g, ' ')
    .replace(/[{}]/g, ' ')
    .replace(/&(?:#\d+|#x[0-9a-f]+|[a-z]+);/gi, ' ');
  for (const chunk of text.split('\u0000')) if (words(chunk, tokens).length) found.push(chunk.trim().replace(/\s+/g, ' '));
  return found;
}

describe('hard-coded English scanner (spec 2026-11-19 §6)', () => {
  it('finds literal English text and visible attributes, ignores bindings, entities and control flow', () => {
    expect(findings('<p>Nothing to show</p>')).toEqual(['Nothing to show']);
    expect(findings('<button aria-label="Close dialog" [attr.title]="x">{{ t }}</button>')).toEqual(['Close dialog']);
    expect(findings('@if (a) { <b>{{ i18n.t(k) }}</b> } @else { <i>&nbsp;·&nbsp;</i> } @for (x of xs; track x) { <kbd>G</kbd> }')).toEqual([]);
    expect(findings(`<b [attr.aria-label]="'Revoke ' + k.name" [title]="x ? 'Copy link' : i18n.t('k')">{{ t }}</b>`)).toEqual(["'Revoke ' + k.name", "x ? 'Copy link' : i18n.t('k')"]);
    expect(findings(`<b [attr.aria-label]="i18n.t('shell.x', { id: a })" [matTooltip]="'USDT'"></b>`)).toEqual([]);
    expect(findings('<span>USDT ETH TRON 0x12</span>')).toEqual([]);
  });

  const sources = walk(ROOT)
    .filter((p) => /\.(ts|html)$/.test(p) && !p.endsWith('.spec.ts') && !p.includes('/core/i18n/') && !p.includes('/core/mock/'))
    .sort();
  const found = new Map<string, string[]>();
  for (const p of sources) {
    const rel = p.slice(ROOT.length + 1);
    const f = templatesOf(p, fs().readFileSync(p, 'utf8')).flatMap((t) => findings(t));
    if (f.length) found.set(rel, f);
  }

  it('scans the console sources', () => expect(sources.length).toBeGreaterThan(20));

  it('finds no hard-coded English outside the allowlist', () => {
    const unlisted = [...found].filter(([f]) => !COVERAGE_ALLOWLIST.files.includes(f)).map(([f, w]) => `${f}: ${w.slice(0, 3).join(' | ')}`);
    expect(unlisted, 'translate these (t(key)) or, only for a file still to do, list it').toEqual([]);
  });

  it('allowlists only files that still have a finding (the list must shrink)', () => {
    const stale = COVERAGE_ALLOWLIST.files.filter((f) => !found.has(f));
    expect(stale, 'remove these from coverage-allowlist.ts').toEqual([]);
  });

  it('keeps the allowlist sorted and free of duplicates', () => {
    expect(COVERAGE_ALLOWLIST.files).toEqual([...new Set(COVERAGE_ALLOWLIST.files)].sort());
  });
});
