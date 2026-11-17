import { DatePipe, LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { Fraction, NetworkId, TraceDetail, TraceSummary } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { formatUnits } from '../../shared/format';
import { MAT_BUTTONS, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';

/** Exact fraction of raw units shown in token units (display only; the server keeps the exact fraction). */
export function fractionUnits(f: Fraction, decimals: number): string {
  const num = BigInt(String(f.num)), den = BigInt(String(f.den));
  const scaled = (num * 10n ** 6n) / den; // 6 extra digits before rounding to the token's decimals for display
  return formatUnits(scaled.toString(), decimals + 6, 6);
}

/**
 * Stored traces (ADR-0022): runs of the trace engine kept with their frozen evidence. A trace is computed once; running it
 * again makes a new version with the differences. STALE: a transfer it froze left the canonical chain.
 */
@Component({
  selector: 'oci-traces',
  imports: [DatePipe, LowerCasePipe, PageHeader, ...MAT_BUTTONS, ...MAT_TABLE, ...UI],
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
      <oci-page-header heading="Stored traces" lede="Traces kept with the evidence they read, so a result can be explained later exactly as it was computed. Run one from the fund flow graph (direction Out or In)." />
      <div class="grid">
        <section class="card card--flush" aria-labelledby="h-traces">
          <div class="card-head"><h2 id="h-traces">Your traces</h2></div>
          <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.value()" [empty]="(list.value() ?? []).length === 0" skeleton="table"
            emptyText="No stored trace yet." (retry)="list.reload()">
            <div class="table-wrap"><table mat-table [dataSource]="list.value() ?? []">
              <ng-container matColumnDef="seed">
                <th mat-header-cell *matHeaderCellDef scope="col">Seed</th>
                <td mat-cell *matCellDef="let t"><span class="mono">{{ short(t.seed) }}</span> <span class="small muted">{{ t.direction === 'FORWARD' ? 'out' : 'in' }}, v{{ t.version }}</span></td>
              </ng-container>
              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef scope="col">Status</th>
                <td mat-cell *matCellDef="let t">{{ t.status | lowercase }}@if (t.resultStatus) { , {{ t.resultStatus | lowercase }} }@if (t.stale) { <strong class="warn">, stale</strong> }</td>
              </ng-container>
              <ng-container matColumnDef="created">
                <th mat-header-cell *matHeaderCellDef scope="col">Created</th>
                <td mat-cell *matCellDef="let t" class="nowrap">{{ t.createdAt | date: 'short' }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="cols"></tr>
              <tr mat-row *matRowDef="let t; columns: cols" [class.sel]="t.id === selected()" (click)="open(t)" (keydown.enter)="open(t)" tabindex="0"></tr>
            </table></div>
          </oci-state>
        </section>

        <section class="card" aria-labelledby="h-detail">
          <div class="card-head"><h2 id="h-detail">Trace</h2></div>
          @if (!selected()) {
            <p class="small muted">Select a trace.</p>
          } @else if (detail(); as d) {
            <div class="kv">
              <span class="muted">Seed</span><span class="mono">{{ d.seed }} ({{ d.network }})</span>
              <span class="muted">Direction</span><span>{{ d.direction === 'FORWARD' ? 'out: where its value went' : 'in: where the value it received came from' }}</span>
              <span class="muted">Version</span><span>{{ d.version }}@if (d.previousId) { (follows {{ short(d.previousId) }}) }</span>
              <span class="muted">Status</span><span>{{ d.status | lowercase }}@if (d.resultStatus) { , {{ d.resultStatus | lowercase }} }</span>
              <span class="muted">Evidence</span><span>{{ d.frozenFlows }} frozen transfer(s), engine {{ d.engineVersion }}</span>
            </div>
            @if (d.stale) {
              <p class="note note--warn">Stale: {{ d.staleEventIds?.length }} transfer(s) it relied on left the canonical chain. The result below is as it was computed; run it again for the current chain.</p>
            }
            @if (d.error) { <p class="note note--warn">{{ d.error }}</p> }
            <div class="actions">
              <button matButton="outlined" class="sm" type="button" (click)="rerun(d)" [disabled]="d.status === 'PENDING' || d.status === 'RUNNING'">Run again (new version)</button>
              @if (d.status === 'PENDING') { <button matButton="outlined" class="sm" type="button" (click)="cancel(d)">Cancel</button> }
              <button matButton="outlined" class="sm" type="button" (click)="export(d)" [disabled]="d.status !== 'DONE'">Export JSON</button>
              <button matButton="text" class="sm" type="button" (click)="graph(d)">Open in graph</button>
            </div>
            @if (d.diff; as df) {
              <h3 class="small strong">Changes since the previous version</h3>
              @if (df.evidence.length === 0 && df.attributions.length === 0) { <p class="small">None.</p> }
              @else { <p class="small">{{ count(df, 'ADDED') }} added, {{ count(df, 'REMOVED') }} removed, {{ count(df, 'MOVED') }} moved (re-included in another block) transfer(s); {{ df.attributions.length }} attribution(s) changed.</p> }
            }
            @if (d.result; as r) {
              <h3 class="small strong">Attributed value (method {{ method(d) }}; a convention, not a fact)</h3>
              @if (r.attributions.length === 0) { <p class="small">None.</p> }
              <ul class="small">
                @for (a of r.attributions; track a.address.value + a.tokenKey) {
                  <li><span class="mono">{{ short(a.address.value) }}</span>, hop {{ a.depth }}: {{ amount(d, a.low, a.tokenKey) }}@if (amount(d, a.low, a.tokenKey) !== amount(d, a.high, a.tokenKey)) { to {{ amount(d, a.high, a.tokenKey) }} } {{ symbol(d, a.tokenKey) }}@if (a.partial) { <span class="warn"> (partial data)</span> }</li>
                }
              </ul>
              @if (r.findings.length) {
                <h3 class="small strong">Patterns (inferred)</h3>
                <ul class="small">@for (f of r.findings; track f.type + f.eventIds.join()) { <li>{{ f.type | lowercase }}: {{ f.detail }}</li> }</ul>
              }
              @if (structural(d).length) {
                <h3 class="small strong">Where it stopped</h3>
                <ul class="small">@for (s of structural(d); track s.reason + s.address.value) { <li><span class="mono">{{ short(s.address.value) }}</span>: {{ s.reason }}, {{ s.detail }}</li> }</ul>
              }
            }
          } @else {
            <p class="small muted">Loading…</p>
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
  /** Query param: the trace to open. */
  readonly id = input<string>();

  readonly cols = ['seed', 'status', 'created'];
  readonly list = apiResource({ params: () => ({}), stream: () => this.api.traces() });
  readonly selected = signal<string | null>(null);
  readonly detail = signal<TraceDetail | null>(null);
  private timer?: ReturnType<typeof setTimeout>;

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
      error: (e: ApiError) => { this.detail.set(null); this.toasts.error(e.title, e.detail ?? undefined); },
    });
  }

  rerun(d: TraceDetail): void {
    this.api.rerunTrace(d.id).subscribe({
      next: (n) => { this.list.reload(); this.load(n.id); this.toasts.success('New version queued', `version ${n.version}`); },
      error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined),
    });
  }

  cancel(d: TraceDetail): void {
    this.api.cancelTrace(d.id).subscribe({ next: () => this.load(d.id), error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined) });
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
      error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined),
    });
  }

  graph(d: TraceDetail): void {
    this.router.navigate(['/investigation'], { queryParams: { address: d.seed, network: d.network as NetworkId } });
  }

  count(df: NonNullable<TraceDetail['diff']>, k: string): number { return df.evidence.filter((e) => e.kind === k).length; }

  method(d: TraceDetail): string { return ((d.request as { method?: string }) ?? {}).method ?? 'PROPORTIONAL'; }

  private token(d: TraceDetail, key: string) { return d.result?.edges.find((e) => e.tokenKey === key)?.token; }
  symbol(d: TraceDetail, key: string): string { return this.token(d, key)?.symbol ?? key; }
  amount(d: TraceDetail, f: Fraction, key: string): string { return fractionUnits(f, this.token(d, key)?.decimals ?? 0); }

  structural(d: TraceDetail) {
    const quiet = new Set(['UNKNOWN_OPENING_BALANCE', 'SAME_TIME_ORDER', 'BELOW_MIN_SHARE', 'DUST', 'UNVERIFIED_TOKEN']);
    return (d.result?.stops ?? []).filter((s) => !quiet.has(s.reason));
  }
}
