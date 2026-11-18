import { RiskLevel, Severity, WalletAnalysis } from '../../core/models';
import { evidenceDetail, levelOfSignal } from '../../shared/levels';
import { VerdictLine } from '../../shared/verdict-panel';

const ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];
const words = (s: string) => s.toLowerCase().replace(/_/g, ' ');

export interface WalletVerdict { sentence: string; risk: RiskLevel | null; lines: VerdictLine[]; coverage: string }

/**
 * The verdict at the top of the wallet page (spec 2026-11-18): a listing by a source is confirmed, everything derived from
 * it and every signal is inferred (ADR-0023 amendment 1, rule 4); with nothing found, the sentence is about the data
 * covered, never that the wallet is clean.
 */
export function walletVerdict(w: WalletAnalysis): WalletVerdict {
  const listed = w.labels.find((l) => l.evidenceLevel === 'CONFIRMED_BY_SOURCE');
  const signals = [...w.signals].sort((a, b) => ORDER.indexOf(a.severity) - ORDER.indexOf(b.severity));
  const sentence = listed ? `This wallet is listed as ${words(listed.category)} by ${listed.source}.`
    : signals.length ? `${signals.length} risk signal${signals.length === 1 ? '' : 's'} on this wallet; the strongest: ${(signals[0].title || words(signals[0].type)).toLowerCase()}.`
    : 'No risk indicator in the data covered.';
  const lines: VerdictLine[] = [
    ...w.labels.map((l) => ({ level: levelOfSignal(l.evidenceLevel, true), text: `Listed as ${words(l.category)} by ${l.source}`, detail: evidenceDetail(l.evidenceLevel) })),
    ...signals.map((s) => ({ level: levelOfSignal(s.evidenceLevel, false), text: s.summary, detail: evidenceDetail(s.evidenceLevel) })),
  ];
  return { sentence, risk: w.risk?.level ?? null, lines, coverage: w.history?.message ?? '' };
}
