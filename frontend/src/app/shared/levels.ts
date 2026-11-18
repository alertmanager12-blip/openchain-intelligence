import { EvidenceLevel } from '../core/models';

/** The five levels of proof (ADR-0023 amendments 1 and 2), as every finding shows them. */
export type Level = 'OBSERVED' | 'INFERRED' | 'CONFIRMED' | 'PREDICTED' | 'SIMULATED';

const WORDS: Record<Level, string> = { OBSERVED: 'Observed', INFERRED: 'Inferred', CONFIRMED: 'Confirmed', PREDICTED: 'Predicted', SIMULATED: 'Simulated' };

export function levelWord(l: Level): string { return WORDS[l]; }

/**
 * Rule 4 of ADR-0023 amendment 1: ANOMALY and RISK_SIGNAL are inferences; CONFIRMED_BY_SOURCE is confirmed only for the
 * listing itself (a label); anything derived from a confirmed label, such as an exposure, stays an inference.
 */
export function levelOfSignal(e: EvidenceLevel, isLabel: boolean): Level {
  return e === 'CONFIRMED_BY_SOURCE' && isLabel ? 'CONFIRMED' : 'INFERRED';
}

/** The original evidence level, in words, kept next to the level (it says why). */
export function evidenceDetail(e: EvidenceLevel): string {
  return e === 'ANOMALY' ? 'anomaly' : e === 'RISK_SIGNAL' ? 'risk signal' : 'confirmed by source';
}
