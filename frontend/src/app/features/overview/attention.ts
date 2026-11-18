import { Alert, Dashboard, NetworkId, RiskLevel, Severity } from '../../core/models';
import { evidenceDetail, levelOfSignal } from '../../shared/levels';
import { VerdictLine } from '../../shared/verdict-panel';

const ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];
const OPEN = new Set(['OPEN', 'ACKNOWLEDGED', 'INVESTIGATING']);

export interface Attention { id: string; sentence: string; risk: RiskLevel; lines: VerdictLine[]; network: NetworkId; address: string | null }

function risk(s: Severity): RiskLevel { return s === 'CRITICAL' ? 'CRITICAL' : s === 'HIGH' ? 'HIGH' : s === 'MEDIUM' ? 'MEDIUM' : 'LOW'; }

/**
 * What needs attention on the overview (spec 2026-11-18): the most severe open alerts, each with the recent signals of its
 * wallet as findings. A signal is never more than an inference here (ADR-0023 amendment 1, rule 4); its own evidence
 * level stays as detail.
 */
export function attention(d: Dashboard, max = 3): Attention[] {
  const recent: Alert[] = d.alerts?.recent ?? [];
  const signals = d.recentSignals ?? [];
  return recent.filter((a) => OPEN.has(a.status))
    .sort((x, y) => ORDER.indexOf(x.severity) - ORDER.indexOf(y.severity))
    .slice(0, max)
    .map((a) => ({
      id: a.id,
      sentence: a.summary || a.ruleName,
      risk: risk(a.severity),
      network: a.network,
      address: a.address,
      lines: signals.filter((s) => a.address && s.address === a.address && s.network === a.network)
        .map((s) => ({ level: levelOfSignal(s.evidenceLevel, false), text: s.summary, detail: evidenceDetail(s.evidenceLevel) })),
    }));
}

export interface EmptyState { kind: 'hidden-open' | 'no-permission' | 'none-open'; text: string }

/**
 * What to say when no verdict is shown (final review, 2026-11-18). The dashboard lists only the latest alerts of any status,
 * so open alerts may exist outside them: they are counted (openBySeverity) and announced, never hidden behind "nothing".
 */
export function emptyState(d: Dashboard): EmptyState {
  if (!d.alerts) return { kind: 'no-permission', text: 'Alerts are not visible with this key: it lacks the alerts:read permission.' };
  const o = d.alerts.openBySeverity ?? {};
  const total = Object.values(o).reduce((s: number, n) => s + (n ?? 0), 0);
  if (total > 0) {
    const parts = [o['CRITICAL'] ? `${o['CRITICAL']} critical` : '', o['HIGH'] ? `${o['HIGH']} high` : ''].filter(Boolean);
    const noun = total === 1 ? 'alert' : 'alerts', verb = total === 1 ? 'is' : 'are';
    return { kind: 'hidden-open', text: `${total} open ${noun}${parts.length ? ` (${parts.join(', ')})` : ''} ${verb} not among the latest alerts shown here.` };
  }
  return { kind: 'none-open', text: 'No open alert right now.' };
}
