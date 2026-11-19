import { fmtNumber, LOCALE } from './format';
import type { Lang, Params } from './i18n.service';

class Malformed extends Error {}

/** Index of the "}" closing the "{" at {@code open}; throws when braces are unbalanced. */
function closing(s: string, open: number): number {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    if (s[i] === '{') depth++;
    else if (s[i] === '}' && --depth === 0) return i;
  }
  throw new Malformed();
}

/** Branches "selector {message}" of a plural or select body. */
function branches(body: string): Map<string, string> {
  const out = new Map<string, string>();
  let i = 0;
  while (i < body.length) {
    while (i < body.length && /\s/.test(body[i])) i++;
    if (i >= body.length) break;
    const start = i;
    while (i < body.length && !/[\s{]/.test(body[i])) i++;
    const selector = body.slice(start, i);
    while (i < body.length && /\s/.test(body[i])) i++;
    if (!selector || body[i] !== '{') throw new Malformed();
    const end = closing(body, i);
    out.set(selector, body.slice(i + 1, end));
    i = end + 1;
  }
  if (!out.has('other')) throw new Malformed();
  return out;
}

function render(msg: string, params: Params, lang: Lang, hash: number | null): string {
  let out = '';
  let i = 0;
  while (i < msg.length) {
    const c = msg[i];
    if (c === '}') throw new Malformed();
    if (c === '#' && hash !== null) { out += fmtNumber(hash, lang); i++; continue; }
    if (c !== '{') { out += c; i++; continue; }
    const end = closing(msg, i);
    const raw = msg.slice(i, end + 1);
    const inner = msg.slice(i + 1, end);
    i = end + 1;
    const c1 = inner.indexOf(',');
    if (c1 < 0) {
      const v = params[inner.trim()];
      out += v === undefined ? raw : typeof v === 'number' ? fmtNumber(v, lang) : v;
      continue;
    }
    const name = inner.slice(0, c1).trim();
    const rest = inner.slice(c1 + 1);
    const c2 = rest.indexOf(',');
    if (c2 < 0) throw new Malformed();
    const type = rest.slice(0, c2).trim();
    const table = branches(rest.slice(c2 + 1));
    const v = params[name];
    if (type === 'plural') {
      const n = typeof v === 'number' ? v : v === undefined || v.trim() === '' ? NaN : Number(v);
      if (Number.isNaN(n)) { out += raw; continue; }
      const body = table.get('=' + n) ?? table.get(new Intl.PluralRules(LOCALE[lang]).select(n)) ?? table.get('other')!;
      out += render(body, params, lang, n);
    } else if (type === 'select') {
      if (v === undefined) { out += raw; continue; }
      out += render(table.get(String(v)) ?? table.get('other')!, params, lang, hash);
    } else {
      throw new Malformed();
    }
  }
  return out;
}

/**
 * ICU message subset: {name}, {n, plural, =0 {..} one {..} other {..}} with # = the number, {k, select, a {..} other {..}}.
 * Branches may nest. An unknown parameter leaves its placeholder; a malformed message is returned unchanged. Never throws.
 */
export function formatIcu(message: string, params: Params, lang: Lang): string {
  try {
    return render(message, params, lang, null);
  } catch {
    return message;
  }
}

/** True when {@link formatIcu} can parse the message (balanced braces, known types, an "other" branch). */
export function isWellFormedIcu(message: string): boolean {
  try {
    render(message, {}, 'en', null);
    return true;
  } catch {
    return false;
  }
}
