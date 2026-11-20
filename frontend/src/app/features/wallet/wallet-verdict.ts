import { RiskLevel, Severity, WalletAnalysis } from '../../core/models';
import { translate, type Lang } from '../../core/i18n/i18n.service';
import { categoryName, signalTypeName } from '../../shared/explain';
import { evidenceDetail, levelOfSignal } from '../../shared/levels';
import { VerdictLine } from '../../shared/verdict-panel';

const ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];
const words = (s: string) => s.toLowerCase().replace(/_/g, ' ');

export interface WalletVerdict { sentence: string; risk: RiskLevel | null; lines: VerdictLine[]; coverage: string }

/**
 * The verdict at the top of the wallet page (spec 2026-11-18): a listing by a source is confirmed, everything derived from
 * it and every signal is inferred (ADR-0023 amendment 1, rule 4); with nothing found, the sentence is about the data
 * covered, never that the wallet is clean. Written in the console's language; the engine's signal summaries stay English
 * (tagged textLang "en") and the coverage is the server's own sentence.
 */
export function walletVerdict(w: WalletAnalysis, lang: Lang = 'en'): WalletVerdict {
  const cat = (c: string) => (lang === 'en' ? words(c) : categoryName(c, lang));
  const listed = w.labels.find((l) => l.evidenceLevel === 'CONFIRMED_BY_SOURCE');
  const signals = [...w.signals].sort((a, b) => ORDER.indexOf(a.severity) - ORDER.indexOf(b.severity));
  const labelled = w.labels[0];
  const strongest = signals[0] ? (lang !== 'en' ? signalTypeName(signals[0].type, lang) : null) ?? (signals[0].title || words(signals[0].type)) : '';
  const sentence = listed ? translate(lang, 'wallet.verdict.listed', { category: cat(listed.category), source: listed.source })
    : signals.length ? translate(lang, 'wallet.verdict.signals', { count: signals.length, title: strongest.toLowerCase() })
    : labelled ? translate(lang, 'wallet.verdict.labelled', { category: cat(labelled.category), source: labelled.source })
    : translate(lang, 'wallet.verdict.none');
  const engine = lang === 'en' ? {} : { textLang: 'en' };
  const lines: VerdictLine[] = [
    ...w.labels.map((l) => ({ level: levelOfSignal(l.evidenceLevel, true), text: translate(lang, 'wallet.verdict.line', { category: cat(l.category), source: l.source }), detail: evidenceDetail(l.evidenceLevel, lang) })),
    ...signals.map((s) => ({ level: levelOfSignal(s.evidenceLevel, false), text: s.summary, detail: evidenceDetail(s.evidenceLevel, lang), ...engine })),
  ];
  return { sentence, risk: w.risk?.level ?? null, lines, coverage: w.history?.message ?? '' };
}
