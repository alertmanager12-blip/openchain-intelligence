import { ChangeDetectionStrategy, Component, InjectionToken, computed, input } from '@angular/core';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { HistoryCoverage, HistoryStatus } from '../../core/models';
import { TechDetail } from '../../shared/ui';

/** Delay between two re-fetches of the wallet analysis while its history is loading. */
export const HISTORY_POLL_MS = new InjectionToken<number>('HISTORY_POLL_MS', { factory: () => 3000 });

/** True while the server is still loading the address history: the analysis should be fetched again. */
export function historyLoading(h: HistoryCoverage | null | undefined): boolean {
  return h?.status === 'PENDING' || h?.status === 'RUNNING';
}

export type CoverageTone = 'neutral' | 'progress' | 'ok' | 'warn' | 'error';

export function coverageTone(s: HistoryStatus): CoverageTone {
  switch (s) {
    case 'PENDING':
    case 'RUNNING': return 'progress';
    case 'COMPLETE': return 'ok';
    case 'TRUNCATED': return 'warn';
    case 'FAILED': return 'error';
    default: return 'neutral';
  }
}

const STATUS_LABEL: Record<HistoryStatus, string> = {
  NOT_CONFIGURED: 'Indexed data only', PENDING: 'Queued', RUNNING: 'Loading', COMPLETE: 'Complete', TRUNCATED: 'Partial', FAILED: 'Failed',
};

/** One line saying how much of the wallet's history the figures below are based on (ADR-0020). */
@Component({
  selector: 'oci-history-coverage',
  imports: [MatProgressSpinner, TechDetail],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let h = history();
    <div class="cov tone-{{ tone() }}" [attr.role]="tone() === 'error' ? 'alert' : null">
      <span class="chip cov-chip">History: {{ label() }}</span>
      @if (loading()) {
        <span class="cov-loading" aria-live="polite" role="status">
          <mat-progress-spinner mode="indeterminate" diameter="14" strokeWidth="2" aria-hidden="true" />
          <span class="strong">Loading history…</span>@if (h.eventsFound > 0) { <span class="muted">&#32;{{ h.eventsFound.toLocaleString('en-US') }} events so far.</span> }
        </span>
      }
      <span class="cov-msg">{{ h.message }}</span>
    </div>
    @if (h.sources.length || h.coveredFromBlock !== null) {
      <oci-tech label="Coverage detail">
        @if (h.sources.length) { <span>Sources <span class="mono">{{ h.sources.join(', ') }}</span></span> }
        @if (h.coveredFromBlock !== null) { <span>Blocks <span class="mono">{{ h.coveredFromBlock.toLocaleString('en-US') }}</span> to <span class="mono">{{ h.coveredToBlock?.toLocaleString('en-US') ?? 'head' }}</span>@if (h.coveredFromTime) { , since {{ h.coveredFromTime }} } </span> }
        <span>Token transfers {{ yn(h.tokenTransfersCovered) }}, approvals {{ yn(h.approvalsCovered) }}, native {{ yn(h.nativeCovered) }}, internal {{ yn(h.internalCovered) }}</span>
        <span>{{ h.eventsFound.toLocaleString('en-US') }} events found@if (h.truncated) { , caps reached: older history not loaded }</span>
      </oci-tech>
    }
  `,
  styles: `
    :host { display: grid; gap: 4px; min-width: 0; }
    .cov { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; font-size: 12.5px; color: var(--ink-2); padding: 8px 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface, transparent); }
    .cov-msg { min-width: 0; overflow-wrap: anywhere; }
    .cov-loading { display: inline-flex; gap: 6px; align-items: center; }
    .tone-ok .cov-chip { color: var(--ok); }
    .tone-progress .cov-chip { color: var(--accent); }
    .tone-warn { border-color: color-mix(in srgb, var(--risk-medium) 50%, var(--line)); }
    .tone-warn .cov-chip { color: var(--warn); }
    .tone-error { border-color: color-mix(in srgb, var(--risk-critical) 55%, var(--line)); }
    .tone-error .cov-chip, .tone-error .cov-msg { color: var(--danger); }
  `,
})
export class HistoryCoverageLine {
  readonly history = input.required<HistoryCoverage>();
  readonly tone = computed(() => coverageTone(this.history().status));
  readonly label = computed(() => STATUS_LABEL[this.history().status] ?? this.history().status);
  readonly loading = computed(() => historyLoading(this.history()));
  readonly yn = (v: boolean) => (v ? 'covered' : 'not covered');
}
