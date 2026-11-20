import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ApiError, errorText, statusTitle } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { LOCALE } from '../../core/i18n/format';
import { hasKey, I18nService, Lang } from '../../core/i18n/i18n.service';
import { Fraction, NetworkId, TraceDetail, TraceSummary } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { EngineText } from '../../shared/engine-text';
import { formatUnits, sentence } from '../../shared/format';
import { MAT_BUTTONS, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';

/** Exact fraction of raw units shown in token units (display only; the server keeps the exact fraction). */
export function fractionUnits(f: Fraction, decimals: number, lang: Lang = 'en'): string {
  const num = BigInt(String(f.num)), den = BigInt(String(f.den));
  const scaled = (num * 10n ** 6n) / den; // 6 extra digits before rounding to the token's decimals for display
  return formatUnits(scaled.toString(), decimals + 6, 6, lang);
}

/**
 * Stored traces (ADR-0022): runs of the trace engine kept with their frozen evidence. A trace is computed once; running it
 * again makes a new version with the differences. STALE: a transfer it froze left the canonical chain.
 */
@Component({
  selector: 'oci-traces',
  imports: [PageHeader, EngineText, ...MAT_BUTTONS, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); gap: 16px; align-items: start; }
    @media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } }
    tr.sel { background: color-mix(in srgb, var(--accent) 8%, transparent); }
    tr[mat-row] { cursor: pointer; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 8px 0; }
    .kv { display: grid; grid-template-columns: max-content 1fr; gap: 2px 12px; font-size: 13px; }
    ul { padding-left: 18px; margin: 4px 0; }
  `,
  template: `
    <div class="page">
      <oci-page-header [heading]="i18n.t('traces.title')" [lede]="i18n.t('traces.lede')" />
      <div class="grid">
        <section class="card card--flush" aria-labelledby="h-traces">
          <div class="card-head"><h2 id="h-traces">{{ i18n.t('traces.yours') }}</h2></div>
          <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.value()" [empty]="(list.value() ?? []).length === 0" skeleton="table"
            [emptyText]="i18n.t('traces.empty')" (retry)="list.reload()">
            <div class="table-wrap"><table mat-table [dataSource]="list.value() ?? []">
              <ng-container matColumnDef="seed">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('traces.seed') }}</th>
                <td mat-cell *matCellDef="let t"><span class="mono">{{ short(t.seed) }}</span> <span class="small muted">{{ i18n.t(t.direction === 'FORWARD' ? 'traces.out' : 'traces.in') }}, {{ i18n.t('traces.version', { version: t.version }) }}</span></td>
              </ng-container>
              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('traces.status') }}</th>
                <td mat-cell *matCellDef="let t">{{ statusText(t.status) }}@if (t.resultStatus) {, {{ resultText(t.resultStatus) }}}@if (t.stale) {<strong class="warn">, {{ i18n.t('traces.stale') }}</strong>}</td>
              </ng-container>
              <ng-container matColumnDef="created">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('traces.created') }}</th>
                <td mat-cell *matCellDef="let t" class="nowrap">{{ when(t.createdAt) }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="cols"></tr>
              <tr mat-row *matRowDef="let t; columns: cols" [class.sel]="t.id === selected()" (click)="open(t)" (keydown.enter)="open(t)" tabindex="0"></tr>
            </table></div>
          </oci-state>
        </section>

        <section class="card" aria-labelledby="h-detail">
          <div class="card-head"><h2 id="h-detail">{{ i18n.t('traces.detail') }}</h2></div>
          @if (!selected()) {
            <p class="small muted">{{ i18n.t('traces.select') }}</p>
          } @else if (detail(); as d) {
            <div class="kv">
              <span class="muted">{{ i18n.t('traces.seed') }}</span><span class="mono">{{ d.seed }} ({{ d.network }})</span>
              <span class="muted">{{ i18n.t('traces.direction') }}</span><span>{{ i18n.t(d.direction === 'FORWARD' ? 'traces.dirOut' : 'traces.dirIn') }}</span>
              <span class="muted">{{ i18n.t('traces.versionLabel') }}</span><span>{{ d.version }}@if (d.previousId) { {{ i18n.t('traces.follows', { id: short(d.previousId) }) }} }</span>
              <span class="muted">{{ i18n.t('traces.status') }}</span><span>{{ statusText(d.status) }}@if (d.resultStatus) {, {{ resultText(d.resultStatus) }}}</span>
              <span class="muted">{{ i18n.t('traces.evidence') }}</span><span>{{ i18n.t('traces.evidenceLine', { count: d.frozenFlows, version: d.engineVersion }) }}</span>
            </div>
            @if (d.stale) {
              <p class="note note--warn">{{ i18n.t('traces.staleNote', { count: d.staleEventIds?.length ?? 0 }) }}</p>
            }
            @if (d.error) { <p class="note note--warn"><oci-engine-text [text]="d.error" [plainInEnglish]="true" /></p> }
            <div class="actions">
              <button matButton="outlined" class="sm" type="button" (click)="rerun(d)" [disabled]="d.status === 'PENDING' || d.status === 'RUNNING'">{{ i18n.t('traces.rerun') }}</button>
              @if (d.status === 'PENDING') { <button matButton="outlined" class="sm" type="button" (click)="cancel(d)">{{ i18n.t('traces.cancel') }}</button> }
              <button matButton="outlined" class="sm" type="button" (click)="export(d)" [disabled]="d.status !== 'DONE'">{{ i18n.t('traces.export') }}</button>
              <button matButton="text" class="sm" type="button" (click)="graph(d)">{{ i18n.t('traces.openGraph') }}</button>
            </div>
            @if (d.diff; as df) {
              <h3 class="small strong">{{ i18n.t('traces.changes') }}</h3>
              @if (df.evidence.length === 0 && df.attributions.length === 0) { <p class="small">{{ i18n.t('traces.none') }}</p> }
              @else { <p class="small">{{ i18n.t('traces.diff', { added: count(df, 'ADDED'), removed: count(df, 'REMOVED'), moved: count(df, 'MOVED'), total: df.evidence.length, changed: df.attributions.length }) }}</p> }
            }
            @if (d.result; as r) {
              <h3 class="small strong">{{ i18n.t('traces.attributed', { method: method(d) }) }}</h3>
              @if (r.attributions.length === 0) { <p class="small">{{ i18n.t('traces.none') }}</p> }
              <ul class="small">
                @for (a of r.attributions; track a.address.value + a.tokenKey) {
                  <li><span class="mono">{{ short(a.address.value) }}</span>, {{ i18n.t('traces.hop', { depth: a.depth }) }}: {{ amount(d, a.low, a.tokenKey) }}@if (amount(d, a.low, a.tokenKey) !== amount(d, a.high, a.tokenKey)) { {{ i18n.t('traces.amountTo') }} {{ amount(d, a.high, a.tokenKey) }} } {{ symbol(d, a.tokenKey) }}@if (a.partial) { <span class="warn"> {{ i18n.t('traces.partialData') }}</span> }</li>
                }
              </ul>
              @if (r.findings.length) {
                <h3 class="small strong">{{ i18n.t('traces.patterns') }}</h3>
                <ul class="small">@for (f of r.findings; track f.type + f.eventIds.join()) { <li>{{ findingText(f.type) }}: <oci-engine-text [text]="f.detail" [plainInEnglish]="true" /></li> }</ul>
              }
              @if (structural(d).length) {
                <h3 class="small strong">{{ i18n.t('traces.stopped') }}</h3>
                <ul class="small">@for (s of structural(d); track s.reason + s.address.value) { <li><span class="mono">{{ short(s.address.value) }}</span>: {{ stopText(s.reason) }}, <oci-engine-text [text]="s.detail" [plainInEnglish]="true" /></li> }</ul>
              }
            }
          } @else {
            <p class="small muted">{{ i18n.t('traces.loading') }}</p>
          }
        </section>
      </div>
    </div>
  `,
})
export class TracesPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);
  /** Query param: the trace to open. */
  readonly id = input<string>();

  readonly cols = ['seed', 'status', 'created'];
  readonly list = apiResource({ params: () => ({}), stream: () => this.api.traces() });
  readonly selected = signal<string | null>(null);
  readonly detail = signal<TraceDetail | null>(null);
  private timer?: ReturnType<typeof setTimeout>;

  private named(prefix: string, v: string): string { const k = `${prefix}.${v}`; return hasKey(k) ? this.i18n.t(k) : v.toLowerCase(); }
  readonly statusText = (v: string) => this.named('traces.status', v);
  readonly resultText = (v: string) => this.named('traces.result', v);
  readonly findingText = (v: string) => this.named('traces.finding', v);
  readonly stopText = (v: string) => { const k = `graph.stop.${v}`; return hasKey(k) ? this.i18n.t(k) : sentence(v); };
  /** Local date and time in the console's language (English keeps the US short form the date pipe gave). */
  readonly when = (iso: string) => new Date(iso).toLocaleString(this.i18n.lang() === 'en' ? 'en-US' : LOCALE.fr, { dateStyle: 'short', timeStyle: 'short' });

  readonly short = (a: string) => (a.length > 14 ? `${a.slice(0, 8)}…${a.slice(-4)}` : a);

  constructor() {
    effect(() => { const id = this.id(); if (id) this.load(id); });
  }

  open(t: TraceSummary): void { this.load(t.id); }

  /** Loads a trace and, while it is pending or running, polls it every 2 s. */
  load(id: string): void {
    this.selected.set(id);
    clearTimeout(this.timer);
    this.api.trace(id).subscribe({
      next: (d) => {
        if (this.selected() !== id) return;
        this.detail.set(d);
        if (d.status === 'PENDING' || d.status === 'RUNNING') this.timer = setTimeout(() => this.load(id), 2000);
        else this.list.reload();
      },
      error: (e: ApiError) => { this.detail.set(null); this.toasts.error(statusTitle(e, this.i18n.lang()), errorText(e, this.i18n.lang())); },
    });
  }

  rerun(d: TraceDetail): void {
    this.api.rerunTrace(d.id).subscribe({
      next: (n) => { this.list.reload(); this.load(n.id); this.toasts.success(this.i18n.t('traces.toast.rerun'), this.i18n.t('traces.toast.rerunBody', { version: n.version })); },
      error: (e: ApiError) => this.toasts.error(statusTitle(e, this.i18n.lang()), errorText(e, this.i18n.lang())),
    });
  }

  cancel(d: TraceDetail): void {
    this.api.cancelTrace(d.id).subscribe({ next: () => this.load(d.id), error: (e: ApiError) => this.toasts.error(statusTitle(e, this.i18n.lang()), errorText(e, this.i18n.lang())) });
  }

  export(d: TraceDetail): void {
    this.api.exportTrace(d.id).subscribe({
      next: (j) => {
        const url = URL.createObjectURL(new Blob([JSON.stringify(j, null, 2)], { type: 'application/json' }));
        const a = document.createElement('a');
        a.href = url;
        a.download = `trace-${d.id}.json`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      },
      error: (e: ApiError) => this.toasts.error(statusTitle(e, this.i18n.lang()), errorText(e, this.i18n.lang())),
    });
  }

  graph(d: TraceDetail): void {
    this.router.navigate(['/investigation'], { queryParams: { address: d.seed, network: d.network as NetworkId } });
  }

  count(df: NonNullable<TraceDetail['diff']>, k: string): number { return df.evidence.filter((e) => e.kind === k).length; }

  method(d: TraceDetail): string { return ((d.request as { method?: string }) ?? {}).method ?? 'PROPORTIONAL'; }

  private token(d: TraceDetail, key: string) { return d.result?.edges.find((e) => e.tokenKey === key)?.token; }
  symbol(d: TraceDetail, key: string): string { return this.token(d, key)?.symbol ?? key; }
  amount(d: TraceDetail, f: Fraction, key: string): string { return fractionUnits(f, this.token(d, key)?.decimals ?? 0, this.i18n.lang()); }

  structural(d: TraceDetail) {
    const quiet = new Set(['UNKNOWN_OPENING_BALANCE', 'SAME_TIME_ORDER', 'BELOW_MIN_SHARE', 'DUST', 'UNVERIFIED_TOKEN']);
    return (d.result?.stops ?? []).filter((s) => !quiet.has(s.reason));
  }
}
