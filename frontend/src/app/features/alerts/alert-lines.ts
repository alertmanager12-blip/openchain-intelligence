import { Alert } from '../../core/models';
import type { Lang } from '../../core/i18n/i18n.service';
import { alertSentence } from '../../shared/alert-sentence';
import { evidenceDetail, levelOfSignal } from '../../shared/levels';
import { VerdictLine } from '../../shared/verdict-panel';

/**
 * An alert as a finding (spec 2026-11-18). A rule match is an inference about the wallet, even when the rule reads a
 * confirmed listing (ADR-0023 amendment 1, rule 4: only the listing itself is confirmed); the rule's evidence level stays
 * as detail. In French the sentence is built from the alert's fields and the engine's English wording stays one click away;
 * a rule without a builder shows the engine's wording itself, marked as English.
 */
export function alertLines(a: Alert, lang: Lang = 'en'): VerdictLine[] {
  const engine = a.evidenceSummary || a.summary;
  const text = alertSentence(a, lang);
  const line: VerdictLine = { level: levelOfSignal(a.evidenceLevel, false), text, detail: evidenceDetail(a.evidenceLevel, lang) };
  if (lang === 'fr') {
    if (text === engine) line.textLang = 'en';
    else line.original = engine;
  }
  return [line];
}
