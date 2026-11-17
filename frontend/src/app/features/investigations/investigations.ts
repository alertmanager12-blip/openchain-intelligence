import { DatePipe, LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { InvestigationAssertion, InvestigationDetail, TraceSummary } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { MAT_BUTTONS, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';

/**
 * Investigations (ADR-0022 amendment 2): the deterministic plan run on one address, with a report made only of its
 * assertions. Every sentence shows its level and the assertion it comes from; the assertion's evidence is one click away.
 */
@Component({
  selector: 'oci-investigations',
  imports: [DatePipe, LowerCasePipe, PageHeader, ...MAT_BUTTONS, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .grid { display: grid; grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.6fr); gap: 16px; align-items: start; }
    @media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } }
    tr.sel { background: color-mix(in srgb, var(--accent) 8%, transparent); }
    tr[mat-row] { cursor: pointer; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 8px 0; }
    .sentence { margin: 4px 0; }
    .lvl { display: inline-block; min-width: 72px; font-size: 11px; text-transform: lowercase; color: var(--ink-2); }
    .lvl.CONFIRMED { color: var(--risk-critical); }
    .cite { font-size: 11px; color: var(--muted); margin-left: 6px; background: none; border: 0; cursor: pointer; padding: 0; text-decoration: underline dotted; }
    pre { white-space: pre-wrap; word-break: break-all; font-size: 11.5px; }
  `,
  template: `
    <div class="page">
      <oci-page-header heading="Investigations" lede="A fixed plan on one address: coverage, labels, exposure, traces both ways, patterns, relations and signals. The report says only what its assertions support, and cites them." />
      <div class="grid">
        <section class="card card--flush" aria-labelledby="h-inv">
          <div class="card-head"><h2 id="h-inv">Your investigations</h2></div>
          <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.value()" [empty]="(list.value() ?? []).length === 0" skeleton="table"
            emptyText="No investigation yet. Start one from a wallet page." (retry)="list.reload()">
            <div class="table-wrap"><table mat-table [dataSource]="list.value() ?? []">
              <ng-container matColumnDef="subject">
                <th mat-header-cell *matHeaderCellDef scope="col">Address</th>
                <td mat-cell *matCellDef="let t"><span class="mono">{{ short(t.seed) }}</span> <span class="small muted">v{{ t.version }}</span></td>
              </ng-container>
              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef scope="col">Status</th>
                <td mat-cell *matCellDef="let t">{{ t.status | lowercase }}@if (t.stale) { <strong class="warn">, stale</strong> }</td>
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

        <section class="card" aria-labelledby="h-report">
          <div class="card-head"><h2 id="h-report">Report</h2></div>
          @if (!selected()) {
            <p class="small muted">Select an investigation.</p>
          } @else if (detail(); as d) {
            <p class="small"><span class="mono">{{ d.subject }}</span> ({{ d.network }}), version {{ d.version }}, {{ d.plan }}, {{ d.frozenFlows }} frozen transfer(s), {{ d.status | lowercase }}.</p>
            @if (d.stale) { <p class="note note--warn">Stale: {{ d.staleEventIds?.length }} transfer(s) it relied on left the canonical chain. Run it again for the current chain.</p> }
            @if (d.error) { <p class="note note--warn">{{ d.error }}</p> }
            @if (d.diff; as df) { <p class="small">Since the previous version: {{ df.added.length }} assertion(s) added, {{ df.removed.length }} removed.</p> }
            <div class="actions">
              <button matButton="outlined" class="sm" type="button" (click)="rerun(d)" [disabled]="d.status === 'PENDING' || d.status === 'RUNNING'">Run again (new version)</button>
            </div>
            @if (d.report; as r) {
              @for (sec of r.sections; track sec.title) {
                <h3 class="small strong">{{ sec.title }}</h3>
                @if (sec.sentences.length === 0) { <p class="small muted">Nothing.</p> }
                @for (t of sec.sentences; track t.assertionId + $index) {
                  <p class="small sentence"><span class="lvl {{ t.level }}">{{ t.level }}</span>{{ t.text }}<button type="button" class="cite" (click)="cite(d, t.assertionId)" [attr.aria-label]="'Show assertion ' + t.assertionId">{{ t.assertionId }}</button></p>
                }
              }
              <p class="small muted">Report {{ r.templates }}: every sentence comes from one assertion; nothing else is said.</p>
            }
            @if (shown(); as a) {
              <h3 class="small strong">Assertion {{ a.id }}</h3>
              <pre>{{ json(a) }}</pre>
            }
          } @else {
            <p class="small muted">Loading…</p>
          }
        </section>
      </div>
    </div>
  `,
})
export class InvestigationsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  /** Query param: the investigation to open. */
  readonly id = input<string>();

  readonly cols = ['subject', 'status', 'created'];
  readonly list = apiResource({ params: () => ({}), stream: () => this.api.investigations() });
  readonly selected = signal<string | null>(null);
  readonly detail = signal<InvestigationDetail | null>(null);
  readonly shown = signal<InvestigationAssertion | null>(null);
  private timer?: ReturnType<typeof setTimeout>;

  readonly short = (a: string) => (a.length > 14 ? `${a.slice(0, 8)}…${a.slice(-4)}` : a);
  readonly json = (a: unknown) => JSON.stringify(a, null, 2);

  constructor() {
    effect(() => { const id = this.id(); if (id) this.load(id); });
  }

  open(t: TraceSummary): void { this.load(t.id); }

  load(id: string): void {
    this.selected.set(id);
    this.shown.set(null);
    clearTimeout(this.timer);
    this.api.investigation(id).subscribe({
      next: (d) => {
        if (this.selected() !== id) return;
        this.detail.set(d);
        if (d.status === 'PENDING' || d.status === 'RUNNING') this.timer = setTimeout(() => this.load(id), 2000);
        else this.list.reload();
      },
      error: (e: ApiError) => { this.detail.set(null); this.toasts.error(e.title, e.detail ?? undefined); },
    });
  }

  cite(d: InvestigationDetail, id: string): void { this.shown.set(d.assertions?.find((a) => a.id === id) ?? null); }

  rerun(d: InvestigationDetail): void {
    this.api.rerunInvestigation(d.id).subscribe({
      next: (n) => { this.list.reload(); this.load(n.id); this.toasts.success('New version queued', `version ${n.version}`); },
      error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined),
    });
  }
}
