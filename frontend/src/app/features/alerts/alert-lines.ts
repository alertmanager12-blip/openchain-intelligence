import { Alert } from '../../core/models';
import { evidenceDetail, levelOfSignal } from '../../shared/levels';
import { VerdictLine } from '../../shared/verdict-panel';

/**
 * An alert as a finding (spec 2026-11-18). A rule match is an inference about the wallet, even when the rule reads a
 * confirmed listing (ADR-0023 amendment 1, rule 4: only the listing itself is confirmed); the rule's evidence level stays
 * as detail.
 */
export function alertLines(a: Alert): VerdictLine[] {
  return [{ level: levelOfSignal(a.evidenceLevel, false), text: a.evidenceSummary || a.summary, detail: evidenceDetail(a.evidenceLevel) }];
}
