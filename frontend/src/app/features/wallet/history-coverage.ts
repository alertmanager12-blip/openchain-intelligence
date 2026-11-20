import { ChangeDetectionStrategy, Component, InjectionToken, computed, inject, input } from '@angular/core';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { fmtNumber } from '../../core/i18n/format';
import { I18nService, hasKey } from '../../core/i18n/i18n.service';
import { EngineText } from '../../shared/engine-text';
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

/** One line saying how much of the wallet's history the figures below are based on (ADR-0020). */
@Component({
  selector: 'oci-history-coverage',
  imports: [MatProgressSpinner, TechDetail, EngineText],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let h = history();
    <div class="cov tone-{{ tone() }}" [attr.role]="tone() === 'error' ? 'alert' : null">
      <span class="chip cov-chip">{{ i18n.t('wallet.hist.chip', { status: label() }) }}</span>
      @if (loading()) {
        <span class="cov-loading" aria-live="polite" role="status">
          <mat-progress-spinner mode="indeterminate" diameter="14" strokeWidth="2" aria-hidden="true" />
          <span class="strong">{{ i18n.t('wallet.hist.loading') }}</span>@if (h.eventsFound > 0) { <span class="muted">&#32;{{ i18n.t('wallet.hist.soFar', { count: h.eventsFound }) }}</span> }
        </span>
      }
      <span class="cov-msg"><oci-engine-text [text]="h.message" [plainInEnglish]="true" /></span>
    </div>
    @if (h.sources.length || h.coveredFromBlock !== null) {
      <oci-tech [label]="i18n.t('wallet.hist.detail')">
        @if (h.sources.length) { <span>{{ i18n.t('wallet.hist.sources') }} <span class="mono">{{ h.sources.join(', ') }}</span></span> }
        @if (h.coveredFromBlock !== null) { <span>{{ i18n.t('wallet.hist.blocks') }} <span class="mono">{{ num(h.coveredFromBlock) }}</span> {{ i18n.t('wallet.hist.to') }} <span class="mono">{{ h.coveredToBlock !== null ? num(h.coveredToBlock) : i18n.t('wallet.hist.head') }}</span>@if (h.coveredFromTime) { {{ i18n.t('wallet.hist.since', { time: h.coveredFromTime }) }} } </span> }
        <span>{{ i18n.t('wallet.hist.covers', { tokens: yn(h.tokenTransfersCovered), approvals: yn(h.approvalsCovered), native: yn(h.nativeCovered), internal: yn(h.internalCovered) }) }}</span>
        <span>{{ i18n.t('wallet.hist.found', { count: h.eventsFound }) }}@if (h.truncated) { {{ i18n.t('wallet.hist.capped') }} }</span>
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
  protected readonly i18n = inject(I18nService);
  readonly num = (n: number) => fmtNumber(n, this.i18n.lang());
  readonly label = computed(() => { const k = `wallet.hist.${this.history().status}`; return hasKey(k) ? this.i18n.t(k) : this.history().status; });
  readonly loading = computed(() => historyLoading(this.history()));
  readonly yn = (v: boolean) => this.i18n.t(v ? 'wallet.hist.covered' : 'wallet.hist.notCovered');
}
