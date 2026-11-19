import { EvidenceLevel } from '../core/models';
import type { Lang } from '../core/i18n/i18n.service';

/** The five levels of proof (ADR-0023 amendments 1 and 2), as every finding shows them. */
export type Level = 'OBSERVED' | 'INFERRED' | 'CONFIRMED' | 'PREDICTED' | 'SIMULATED';

const WORDS: Record<Lang, Record<Level, string>> = {
  en: { OBSERVED: 'Observed', INFERRED: 'Inferred', CONFIRMED: 'Confirmed', PREDICTED: 'Predicted', SIMULATED: 'Simulated' },
  fr: { OBSERVED: 'Observé', INFERRED: 'Déduit', CONFIRMED: 'Confirmé', PREDICTED: 'Prédit', SIMULATED: 'Simulé' },
};

export function levelWord(l: Level, lang: Lang = 'en'): string { return WORDS[lang][l]; }

/**
 * Rule 4 of ADR-0023 amendment 1: ANOMALY and RISK_SIGNAL are inferences; CONFIRMED_BY_SOURCE is confirmed only for the
 * listing itself (a label); anything derived from a confirmed label, such as an exposure, stays an inference.
 */
export function levelOfSignal(e: EvidenceLevel, isLabel: boolean): Level {
  return e === 'CONFIRMED_BY_SOURCE' && isLabel ? 'CONFIRMED' : 'INFERRED';
}

/** The original evidence level, in words, kept next to the level (it says why). */
export function evidenceDetail(e: EvidenceLevel, lang: Lang = 'en'): string {
  if (lang === 'fr') return e === 'ANOMALY' ? 'anomalie' : e === 'RISK_SIGNAL' ? 'signal de risque' : 'confirmé par la source';
  return e === 'ANOMALY' ? 'anomaly' : e === 'RISK_SIGNAL' ? 'risk signal' : 'confirmed by source';
}
