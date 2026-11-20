import { EvidenceLevel } from '../core/models';
import { translate, type Lang } from '../core/i18n/i18n.service';

/** The five levels of proof (ADR-0023 amendments 1 and 2), as every finding shows them. */
export type Level = 'OBSERVED' | 'INFERRED' | 'CONFIRMED' | 'PREDICTED' | 'SIMULATED';

export function levelWord(l: Level, lang: Lang = 'en'): string { return translate(lang, `enum.level.${l}`); }

/**
 * Rule 4 of ADR-0023 amendment 1: ANOMALY and RISK_SIGNAL are inferences; CONFIRMED_BY_SOURCE is confirmed only for the
 * listing itself (a label); anything derived from a confirmed label, such as an exposure, stays an inference.
 */
export function levelOfSignal(e: EvidenceLevel, isLabel: boolean): Level {
  return e === 'CONFIRMED_BY_SOURCE' && isLabel ? 'CONFIRMED' : 'INFERRED';
}

/** The original evidence level, in words, kept next to the level (it says why). */
export function evidenceDetail(e: EvidenceLevel, lang: Lang = 'en'): string {
  return translate(lang, `enum.evidence.${e === 'ANOMALY' || e === 'RISK_SIGNAL' ? e : 'CONFIRMED_BY_SOURCE'}`);
}
