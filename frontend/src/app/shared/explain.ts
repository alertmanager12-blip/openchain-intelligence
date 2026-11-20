import { RiskContribution, Signal } from '../core/models';
import { sentence } from './format';
import { hasKey, translate, type Lang } from '../core/i18n/i18n.service';

/** Plain-language name of a signal type ("RAPID_DRAIN" -> "Rapid drain"). */
export function signalTypeTitle(type: string, lang: Lang = 'en'): string {
  return signalTypeName(type, lang) ?? sentence(type);
}

/** Plain-language name of a label category factor ("SANCTIONED" -> "Listed as sanctioned"). */
export function labelTitle(category: string, lang: Lang = 'en'): string {
  const key = category.toUpperCase();
  if (key === 'ISSUER_FROZEN') return translate(lang, 'enum.factor.ISSUER_FROZEN');
  return translate(lang, 'enum.factor.listedAs', { name: categoryName(key, lang) });
}

/** Name of a risk factor key: "signal:RAPID_DRAIN", "label:SANCTIONED" or a bare key. */
export function factorTitle(factor: string, lang: Lang = 'en'): string {
  const i = factor.indexOf(':');
  const kind = i >= 0 ? factor.slice(0, i) : '';
  const key = i >= 0 ? factor.slice(i + 1) : factor;
  if (kind === 'label') return labelTitle(key, lang);
  return signalTypeTitle(key, lang);
}

export function contributionTitle(c: Pick<RiskContribution, 'factor' | 'title'>, lang: Lang = 'en'): string {
  return c.title?.trim() || factorTitle(c.factor, lang);
}

/** The plain sentence of a contribution, or null when the server sent none (the explanation then stays technical). */
export function contributionSummary(c: Pick<RiskContribution, 'summary'>): string | null {
  return c.summary?.trim() || null;
}

export function signalTitle(s: Pick<Signal, 'type' | 'title'>, lang: Lang = 'en'): string {
  return s.title?.trim() || signalTypeTitle(s.type, lang);
}

/** Server summary, else the first evidence description (older payloads). */
export function signalSummary(s: Pick<Signal, 'summary' | 'evidence'>): string | null {
  return s.summary?.trim() || s.evidence?.[0]?.description || null;
}

/**
 * Name of a signal type in the console's language (spec 2026-11-19 §4); null for a type this console does not know, so the
 * caller shows the engine's own title instead of a guess.
 */
export function signalTypeName(type: string, lang: Lang): string | null {
  const k = `enum.signal.${type}`;
  return hasKey(k) ? translate(lang, k) : null;
}

/** What a listed address is said to be ("sanctioned", "frozen by the stablecoin issuer"); accepts SANCTIONED or issuer-frozen. */
export function categoryName(category: string, lang: Lang): string {
  const k = category.toUpperCase().replace(/-/g, '_');
  const key = `enum.category.${k}`;
  return hasKey(key) ? translate(lang, key) : category.toLowerCase().replace(/[_-]/g, ' ');
}
