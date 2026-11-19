import { Alert, Dashboard, NetworkId, RiskLevel, Severity } from '../../core/models';
import type { Lang } from '../../core/i18n/i18n.service';
import { fmtNumber, plural } from '../../core/i18n/format';
import { alertSentence } from '../../shared/alert-sentence';
import { signalTypeName } from '../../shared/explain';
import { severityName } from '../../shared/format';
import { evidenceDetail, levelOfSignal } from '../../shared/levels';
import { VerdictLine } from '../../shared/verdict-panel';

const ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];

export interface Attention { id: string; sentence: string; risk: RiskLevel; lines: VerdictLine[]; network: NetworkId; address: string | null }

function risk(s: Severity): RiskLevel { return s === 'CRITICAL' ? 'CRITICAL' : s === 'HIGH' ? 'HIGH' : s === 'MEDIUM' ? 'MEDIUM' : 'LOW'; }

/**
 * "Handle now" (spec 2026-11-19 §4): the most severe open alerts as verdicts, from the server's `alerts.attention` (an older
 * server: the open ones among the latest). The sentence is written in the console's language from the alert's fields; each
 * supporting signal is named by its type in that language, its level of proof and confidence, with the engine's own wording
 * kept as `original`. A signal is never more than an inference here (ADR-0023 amendment 1, rule 4).
 */
export function attention(d: Dashboard, lang: Lang, max = 5): Attention[] {
  const a = d.alerts;
  if (!a) return [];
  const top: Alert[] = a.attention ?? (a.recent ?? []).filter((x) => x.status === 'OPEN')
    .sort((x, y) => ORDER.indexOf(x.severity) - ORDER.indexOf(y.severity));
  const signals = d.recentSignals ?? [];
  return top.slice(0, max).map((x) => ({
    id: x.id,
    sentence: alertSentence(x, lang),
    risk: risk(x.severity),
    network: x.network,
    address: x.address,
    lines: signals.filter((s) => x.address && s.address === x.address && s.network === x.network).map((s) => ({
      level: levelOfSignal(s.evidenceLevel, false),
      text: signalTypeName(s.type, lang) ?? s.title ?? s.type,
      detail: `${evidenceDetail(s.evidenceLevel, lang)} · ${lang === 'fr' ? 'confiance' : 'confidence'} ${fmtNumber(Math.round(s.confidence * 100), lang)} %`,
      original: s.summary || null,
    })),
  }));
}

export interface EmptyState { kind: 'hidden-open' | 'no-permission' | 'none-open'; text: string }

/** What to say when no verdict is shown: open alerts are counted and announced, never hidden behind "nothing" (2026-11-18). */
export function emptyState(d: Dashboard, lang: Lang): EmptyState {
  const fr = lang === 'fr';
  if (!d.alerts) return { kind: 'no-permission', text: fr ? 'Les alertes ne sont pas visibles avec cette clé : il lui manque la permission alerts:read.'
    : 'Alerts are not visible with this key: it lacks the alerts:read permission.' };
  const o = d.alerts.openBySeverity ?? {};
  const total = Object.values(o).reduce((s: number, n) => s + (n ?? 0), 0);
  if (total > 0) {
    const part = (sev: Severity) => o[sev] ? `${fmtNumber(o[sev], lang)} ${fr ? plural(o[sev], lang, { one: severityName(sev, lang).toLowerCase(), other: severityName(sev, lang).toLowerCase() + 's' })
      : severityName(sev, lang).toLowerCase()}` : '';
    const parts = [part('CRITICAL'), part('HIGH')].filter(Boolean);
    const extra = parts.length ? ` (${parts.join(', ')})` : '';
    return { kind: 'hidden-open', text: fr
      ? `${fmtNumber(total, lang)} ${plural(total, lang, { one: 'alerte ouverte', other: 'alertes ouvertes' })}${extra} ${plural(total, lang, { one: "n'est pas affichée", other: 'ne sont pas affichées' })} ici.`
      : `${fmtNumber(total, lang)} open ${plural(total, lang, { one: 'alert', other: 'alerts' })}${extra} ${total === 1 ? 'is' : 'are'} not shown here.` };
  }
  return { kind: 'none-open', text: fr ? 'Aucune alerte ouverte pour le moment.' : 'No open alert right now.' };
}
