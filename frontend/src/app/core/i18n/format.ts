import type { Lang } from './i18n.service';

/** BCP 47 locale per console language (English dates read "19 Nov 2026", as the console wrote them before). */
export const LOCALE: Record<Lang, string> = { en: 'en-GB', fr: 'fr-FR' };

export function fmtNumber(n: number, lang: Lang, maxFraction = 2): string {
  return new Intl.NumberFormat(LOCALE[lang], { maximumFractionDigits: maxFraction }).format(n);
}

/** Day, short month, year, in UTC: "19 nov. 2026" / "19 Nov 2026". */
export function fmtDate(iso: string, lang: Lang): string {
  const d = new Date(iso.length === 10 ? iso + 'T00:00:00Z' : iso);
  return d.toLocaleDateString(LOCALE[lang], { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
}

/** "il y a 2 heures" / "2 hours ago"; under 45 s: "maintenant" / "now". */
export function fmtRelative(iso: string, lang: Lang, now = Date.now()): string {
  const rtf = new Intl.RelativeTimeFormat(LOCALE[lang], { numeric: 'auto' });
  const s = (Date.parse(iso) - now) / 1000;
  const a = Math.abs(s);
  if (a < 45) return rtf.format(0, 'second');
  if (a < 3600) return rtf.format(Math.round(s / 60), 'minute');
  if (a < 86_400) return rtf.format(Math.round(s / 3600), 'hour');
  return rtf.format(Math.round(s / 86_400), 'day');
}

/** "1 h 20 min", "27 min", "45 s": the same in English and French. */
export function fmtDuration(ms: number, _lang: Lang): string {
  if (ms < 60_000) return `${Math.max(0, Math.round(ms / 1000))} s`;
  const min = Math.round(ms / 60_000);
  const h = Math.floor(min / 60), m = min % 60;
  if (!h) return `${m} min`;
  return m ? `${h} h ${m} min` : `${h} h`;
}

/** Singular or plural by the language's rules (French: 0 and 1 are singular). */
export function plural(n: number, lang: Lang, forms: { one: string; other: string }): string {
  return new Intl.PluralRules(LOCALE[lang]).select(n) === 'one' ? forms.one : forms.other;
}

/** "a", "a and b", "a, b and c" / "a, b et c". */
export function fmtList(items: readonly string[], lang: Lang): string {
  return items.length <= 1 ? (items[0] ?? '') : new Intl.ListFormat(LOCALE[lang], { style: 'long', type: 'conjunction' }).format(items);
}
