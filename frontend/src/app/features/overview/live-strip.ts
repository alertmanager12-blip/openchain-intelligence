import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Alert, NetworkStatus, Severity } from '../../core/models';
import { netLabel } from '../../shared/format';

/** Blocks behind the head still shown as live. */
const LIVE_LAG = 20;
/** Recent blocks a strip shows alerts on. */
const SPAN = 600;

export type StripState = { state: 'live' | 'behind' | 'unreachable'; label: string };

/** What the indexer of one network really reports: live, behind (by how much), or unreachable (head unknown). */
export function stripState(s: NetworkStatus): StripState {
  if (!s.healthy || s.indexerLag === null || s.indexerLag === undefined) return { state: 'unreachable', label: 'Unreachable' };
  if (s.indexerLag > LIVE_LAG) return { state: 'behind', label: `Behind (${s.indexerLag.toLocaleString('en-US')} blocks)` };
  return { state: 'live', label: 'Live' };
}

/** Alerts placed on the last {@code span} blocks before {@code head} (pos 0 = oldest, 1 = head); others are left out. */
export function ticks(head: number, alerts: Alert[], span: number): { pos: number; severity: Severity }[] {
  const out: { pos: number; severity: Severity }[] = [];
  for (const a of alerts) {
    if (a.blockNumber === null || a.blockNumber === undefined || a.blockNumber < head - span || a.blockNumber > head) continue;
    out.push({ pos: Math.round(((a.blockNumber - (head - span)) / span) * 1000) / 1000, severity: a.severity });
  }
  return out;
}

/**
 * The live strip of the overview (spec 2026-11-18): per network, the block reached, its state, and the recent alerts
 * placed on the last blocks. Nothing moves except the data when it is refreshed.
 */
@Component({
  selector: 'oci-live-strip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="strip" aria-label="Monitoring status">
      @for (r of rows(); track r.network) {
        <div class="net">
          <span class="dot {{ r.state.state }}" aria-hidden="true"></span>
          <span class="name">{{ r.name }}</span>
          <span class="state">{{ r.state.label }}</span>
          <span class="block">block {{ r.block }}</span>
          <span class="bar" [attr.aria-label]="r.ticks.length + ' recent alert(s) on the last ' + span + ' blocks'" role="img">
            @for (t of r.ticks; track $index) { <i class="tick sev-{{ t.severity.toLowerCase() }}" [style.left.%]="t.pos * 100"></i> }
          </span>
        </div>
      } @empty { <span class="none">No network is indexed on this instance.</span> }
    </section>`,
  styles: `
    .strip { display: flex; flex-wrap: wrap; gap: 8px 24px; padding: 10px 0; border-bottom: 1px solid var(--line); color: var(--ink-2); font-size: var(--fs-12); }
    .net { display: flex; align-items: center; gap: 8px; flex: 1 1 340px; min-width: 0; }
    .name { color: var(--ink); font-weight: 600; }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--risk-unknown); flex: none; }
    .dot.live { background: var(--risk-low); box-shadow: 0 0 0 3px color-mix(in srgb, var(--risk-low) 20%, transparent); }
    .dot.behind { background: var(--risk-medium); }
    .dot.unreachable { background: var(--risk-critical); }
    .block { font-variant-numeric: tabular-nums; }
    .bar { position: relative; flex: 1 1 120px; height: 12px; background: var(--band); border-radius: 3px; min-width: 80px; }
    .tick { position: absolute; top: 1px; bottom: 1px; width: 3px; margin-left: -1px; border-radius: 1px; background: var(--brand); }
    .tick.sev-high, .tick.sev-critical { background: var(--risk-high); }
  `,
})
export class LiveStrip {
  readonly networks = input.required<NetworkStatus[]>();
  readonly alerts = input<Alert[]>([]);
  readonly span = SPAN;
  readonly rows = computed(() => this.networks().filter((n) => n.enabled !== false).map((n) => ({
    network: n.network,
    name: netLabel(n.network),
    state: stripState(n),
    block: (n.lastIndexedBlock ?? n.headBlock).toLocaleString('en-US'),
    ticks: ticks(n.headBlock, this.alerts().filter((a) => a.network === n.network), SPAN),
  })));
}
