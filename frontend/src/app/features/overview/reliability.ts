import { Dashboard, IndexingGap, NetworkStatus, SanctionsList } from '../../core/models';
import type { Lang } from '../../core/i18n/i18n.service';
import { fmtDate, fmtDuration, fmtNumber, plural } from '../../core/i18n/format';
import { netLabel } from '../../shared/format';
import { sanctionsSentence } from '../../shared/sanctions-text';

/** "Is monitoring reliable?" (spec 2026-11-19 §4). */
export type Check = 'gaps' | 'delay' | 'notifications' | 'sanctions' | 'health';
export type CheckState = 'fail' | 'warn' | 'pass' | 'unknown';
export interface BandLine { check: Check; state: CheckState; text: string; link?: { path: string; query?: Record<string, string> } }
export interface Reliability { verdict: 'yes' | 'partly' | 'no'; lines: BandLine[]; olderGaps: IndexingGap[] }

/** Delay above which a network is behind (spec: 15 min, about 50 Ethereum blocks). */
export const DELAY_WARN_MS = 15 * 60_000;
/** Gaps detected within this window hold the band; older ones stay reachable but do not (indexer_gaps has no "resolved"). */
export const RECENT_GAP_MS = 7 * 86_400_000;
/** Nominal block times, only to say roughly how long a gap lasted. */
const BLOCK_SECONDS: Partial<Record<string, number>> = { tron: 3, ethereum: 12, bsc: 3, polygon: 2 };

const STATE_ORDER: CheckState[] = ['fail', 'unknown', 'warn', 'pass'];
const CHECK_ORDER: Check[] = ['gaps', 'notifications', 'delay', 'health', 'sanctions'];

/**
 * The reliability band. A fact the caller cannot see (section omitted, request failed, head unknown) is "unknown", never a
 * pass; the verdict is "yes" only when every line positively passed, "no" when a network is unhealthy or its head unknown or
 * no sanctions list is loaded, "partly" otherwise.
 */
export function reliability(d: Dashboard, sanctions: SanctionsList | null | undefined, lang: Lang, now = Date.now()): Reliability {
  const fr = lang === 'fr';
  const nets = d.networks.filter((n) => n.enabled !== false);
  const lines: BandLine[] = [];
  let blocking = false;

  // gaps
  let olderGaps: IndexingGap[] = [];
  if (!d.coverage) {
    lines.push({ check: 'gaps', state: 'unknown', text: fr ? "Trous d'indexation : inconnus (serveur trop ancien)" : 'Indexing gaps: unknown (older server)' });
  } else {
    const recent = d.coverage.gaps.filter((g) => g.detectedAt && now - Date.parse(g.detectedAt) <= RECENT_GAP_MS);
    olderGaps = d.coverage.gaps.filter((g) => !recent.includes(g));
    const byNet = new Map<string, IndexingGap[]>();
    for (const g of recent) byNet.set(g.network, [...(byNet.get(g.network) ?? []), g]);
    for (const [n, gs] of byNet) {
      const blocks = gs.reduce((s, g) => s + g.blocks, 0);
      const last = gs.map((g) => g.detectedAt!).sort().at(-1)!;
      const secs = BLOCK_SECONDS[n];
      const about = secs ? `≈ ${fmtDuration(blocks * secs * 1000, lang)}, ` : '';
      lines.push({ check: 'gaps', state: 'warn', text: fr
        ? `${fmtNumber(blocks, lang)} ${plural(blocks, lang, { one: 'bloc', other: 'blocs' })} ${netLabel(n)} jamais ${plural(blocks, lang, { one: 'analysé', other: 'analysés' })} (${about}détecté le ${fmtDate(last, lang)})`
        : `${fmtNumber(blocks, lang)} ${netLabel(n)} ${plural(blocks, lang, { one: 'block', other: 'blocks' })} never analysed (${about}detected on ${fmtDate(last, lang)})` });
    }
    if (!byNet.size) lines.push({ check: 'gaps', state: 'pass', text: fr ? "Aucun trou d'indexation ces 7 derniers jours" : 'No indexing gap in the last 7 days' });
  }

  // notifications
  if (!d.notifications) {
    lines.push({ check: 'notifications', state: 'unknown', text: fr ? 'Notifications : non visibles avec votre rôle' : 'Notifications: not visible with your role' });
  } else if (d.notifications.unroutedRules.length) {
    const n = d.notifications.openAlertsUnrouted, k = d.notifications.unroutedRules.length;
    if (n === 0) {
      lines.push({ check: 'notifications', state: 'warn', link: { path: '/integrations' }, text: fr
        ? `${k} ${plural(k, lang, { one: "règle active n'a", other: "règles actives n'ont" })} pas de destination : ${plural(k, lang, { one: 'ses', other: 'leurs' })} alertes ne parviendraient à personne`
        : `${k} enabled ${plural(k, lang, { one: 'rule has', other: 'rules have' })} no destination: ${plural(k, lang, { one: 'its', other: 'their' })} alerts would reach nobody` });
    } else lines.push({ check: 'notifications', state: 'warn', link: { path: '/integrations' }, text: fr
      ? `${fmtNumber(n, lang)} ${plural(n, lang, { one: 'alerte ouverte ne parvient', other: 'alertes ouvertes ne parviennent' })} à personne (${k} ${plural(k, lang, { one: 'règle', other: 'règles' })} sans destination)`
      : `${fmtNumber(n, lang)} open ${plural(n, lang, { one: 'alert reaches', other: 'alerts reach' })} nobody (${k} ${plural(k, lang, { one: 'rule', other: 'rules' })} without a destination)` });
  } else {
    lines.push({ check: 'notifications', state: 'pass', text: fr ? 'Chaque règle active a une destination' : 'Every enabled rule has a destination' });
  }

  // delay, per enabled network
  for (const n of nets) {
    const name = netLabel(n.network);
    const sep = fr ? ' : ' : ': ';
    if (n.indexerLag === null || n.indexerLag === undefined || !n.healthy) {
      blocking = true;
      lines.push({ check: 'delay', state: 'unknown', text: `${name}${sep}${fr ? 'retard inconnu (tête de chaîne inconnue)' : 'delay unknown (chain head unknown)'}` });
    } else if (!n.lastIndexedBlockTime) {
      lines.push({ check: 'delay', state: 'unknown', text: `${name}${sep}${fmtNumber(n.indexerLag, lang)} ${fr
        ? plural(n.indexerLag, lang, { one: 'bloc', other: 'blocs' }) + ' de retard (heure du dernier bloc inconnue)'
        : plural(n.indexerLag, lang, { one: 'block', other: 'blocks' }) + ' behind (time of the last block unknown)'}` });
    } else {
      const ms = Math.max(0, now - Date.parse(n.lastIndexedBlockTime));
      lines.push({ check: 'delay', state: ms > DELAY_WARN_MS ? 'warn' : 'pass',
        text: `${name}${sep}${fmtDuration(ms, lang)} ${fr ? 'de retard' : 'behind'}` });
    }
  }

  // health
  const bad = nets.filter((n) => !n.healthy);
  if (bad.length) {
    blocking = true;
    for (const n of bad) lines.push({ check: 'health', state: 'fail', text: `${netLabel(n.network)}${fr ? ' : fournisseur en panne ou dégradé' : ': provider failing or degraded'}` });
  } else {
    lines.push({ check: 'health', state: 'pass', text: fr ? 'Fournisseurs de chaîne joignables' : 'Chain providers reachable' });
  }

  // sanctions list
  const st: CheckState = !sanctions ? 'unknown' : sanctions.state === 'NONE' || !sanctions.issued ? 'fail' : sanctions.state === 'STALE' ? 'warn' : 'pass';
  if (st === 'fail') blocking = true;
  lines.push({ check: 'sanctions', state: st, text: sanctionsSentence(sanctions ?? undefined, lang), link: st === 'pass' ? undefined : { path: '/settings' } });

  lines.sort((a, b) => STATE_ORDER.indexOf(a.state) - STATE_ORDER.indexOf(b.state) || CHECK_ORDER.indexOf(a.check) - CHECK_ORDER.indexOf(b.check));
  const verdict = blocking || lines.some((l) => l.state === 'fail') ? 'no' : lines.every((l) => l.state === 'pass') ? 'yes' : 'partly';
  return { verdict, lines, olderGaps };
}
