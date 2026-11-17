import { DatePipe, LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { AssertionReview, CurrentReview, InvestigationAssertion, InvestigationDetail, InvestigationReviews, ReviewDecision, TraceSummary } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { MAT_BUTTONS, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';

/**
 * Investigations (ADR-0022 amendment 2): the deterministic plan run on one address, with a report made only of its
 * assertions. Every sentence shows its level and the assertion it comes from; the assertion's evidence is one click away.
 * Reviews (ADR-0022 amendment 3) sit beside a sentence and never change it: the report text stays what the data support.
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
    .review { display: inline-flex; gap: 4px; margin-left: 8px; vertical-align: middle; }
    .review button { font-size: 11px; padding: 0 6px; border: 1px solid var(--line, #ccc); background: none; border-radius: 4px; cursor: pointer; color: var(--ink-2); }
    .decision { display: block; font-size: 11px; margin: 2px 0 0 72px; color: var(--ink-2); }
    .decision.ACCEPTED { color: var(--ok, #2e7d32); }
    .decision.REJECTED { color: var(--risk-critical); }
    .decision.NEEDS_EVIDENCE { color: var(--warn, #b26a00); }
    .human { display: block; font-style: italic; margin-left: 72px; font-size: 11px; color: var(--ink-2); white-space: pre-wrap; }
    .editor { margin: 4px 0 8px 72px; display: grid; gap: 4px; max-width: 520px; }
    .editor textarea { font: inherit; font-size: 12px; min-height: 48px; }
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
              @if (reviews(); as rv) {
                <button matButton="outlined" class="sm" type="button" (click)="undecidedOnly.set(!undecidedOnly())" [attr.aria-pressed]="undecidedOnly()">
                  {{ undecidedOnly() ? 'Show all sentences' : 'Undecided only' }}</button>
                <span class="small muted">{{ rv.decided }} of {{ rv.total }} assertion(s) reviewed</span>
              }
            </div>
            @if (d.stale && (reviews()?.decided ?? 0) > 0) { <p class="note note--warn">Some reviews were taken on evidence that has since left the canonical chain: check them again.</p> }
            @if (d.report; as r) {
              @for (sec of r.sections; track sec.title) {
                <h3 class="small strong">{{ sec.title }}</h3>
                @if (sec.sentences.length === 0) { <p class="small muted">Nothing.</p> }
                @for (t of sec.sentences; track t.assertionId + $index) {
                  @if (visible(t.assertionId, reviews(), undecidedOnly())) {
                    <p class="small sentence"><span class="lvl {{ t.level }}">{{ t.level }}</span>{{ t.text }}<button type="button" class="cite" (click)="cite(d, t.assertionId)" [attr.aria-label]="'Show assertion ' + t.assertionId">{{ t.assertionId }}</button>
                      <span class="review">
                        <button type="button" (click)="edit(t.assertionId, 'ACCEPTED')" [attr.aria-label]="'Accept assertion ' + t.assertionId">Accept</button>
                        <button type="button" (click)="edit(t.assertionId, 'REJECTED')" [attr.aria-label]="'Reject assertion ' + t.assertionId">Reject</button>
                        <button type="button" (click)="edit(t.assertionId, 'NEEDS_EVIDENCE')" [attr.aria-label]="'Ask evidence for assertion ' + t.assertionId">Needs evidence</button>
                      </span>
                      @if (reviews()?.current?.[t.assertionId]; as c) {
                        <span class="decision {{ c.decision }}">{{ reviewNote(c) }}</span>
                        @if (c.comment) { <span class="human">Comment (human): {{ c.comment }}</span> }
                      }
                    </p>
                    @if (editing()?.assertionId === t.assertionId) {
                      <div class="editor">
                        <label class="small" [attr.for]="'c-' + t.assertionId">{{ decisionLabel(editing()!.decision) }}: comment (optional, at most 2,000 characters)</label>
                        <textarea [id]="'c-' + t.assertionId" maxlength="2000" [value]="comment()" (input)="comment.set($any($event.target).value)"></textarea>
                        <div class="actions">
                          <button matButton="filled" class="sm" type="button" (click)="save(d)" [disabled]="saving()">Save decision</button>
                          <button matButton="outlined" class="sm" type="button" (click)="editing.set(null)">Cancel</button>
                        </div>
                      </div>
                    }
                  }
                }
              }
              <p class="small muted">Report {{ r.templates }}: every sentence comes from one assertion; nothing else is said. Reviews are human decisions shown beside it: they change no sentence and no level.</p>
            }
            @if (shown(); as a) {
              <h3 class="small strong">Assertion {{ a.id }}</h3>
              <pre>{{ json(a) }}</pre>
              @if (historyOf(a.id, reviews()); as h) {
                @if (h.length > 0) {
                  <h3 class="small strong">Reviews of this assertion</h3>
                  @for (x of h; track x.id) { <p class="small">{{ reviewLine(x) }}@if (x.comment) { <span class="human">Comment (human): {{ x.comment }}</span> }</p> }
                }
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
  readonly reviews = signal<InvestigationReviews | null>(null);
  readonly undecidedOnly = signal(false);
  readonly editing = signal<{ assertionId: string; decision: ReviewDecision } | null>(null);
  readonly comment = signal('');
  readonly saving = signal(false);
  readonly visible = visibleSentence;
  readonly reviewNote = reviewNote;
  readonly reviewLine = reviewLine;
  readonly historyOf = historyOf;
  readonly decisionLabel = decisionLabel;
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
    this.editing.set(null);
    if (this.detail()?.id !== id) this.reviews.set(null);
    clearTimeout(this.timer);
    this.api.investigation(id).subscribe({
      next: (d) => {
        if (this.selected() !== id) return;
        this.detail.set(d);
        if (d.status === 'PENDING' || d.status === 'RUNNING') this.timer = setTimeout(() => this.load(id), 2000);
        else { this.list.reload(); if (d.status === 'DONE') this.loadReviews(id); }
      },
      error: (e: ApiError) => { this.detail.set(null); this.toasts.error(e.title, e.detail ?? undefined); },
    });
  }

  loadReviews(id: string): void {
    this.api.investigationReviews(id).subscribe({
      next: (r) => { if (this.selected() === id) this.reviews.set(r); },
      error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined),
    });
  }

  edit(assertionId: string, decision: ReviewDecision): void {
    this.comment.set('');
    this.editing.set({ assertionId, decision });
  }

  save(d: InvestigationDetail): void {
    const e = this.editing();
    if (!e) return;
    this.saving.set(true);
    const c = this.comment().trim();
    this.api.reviewAssertion(d.id, e.assertionId, e.decision, c === '' ? null : c).subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.loadReviews(d.id); this.toasts.success('Decision recorded', decisionLabel(e.decision)); },
      error: (err: ApiError) => { this.saving.set(false); this.toasts.error(err.title, err.detail ?? undefined); },
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

export function decisionLabel(d: ReviewDecision): string {
  return d === 'ACCEPTED' ? 'Accepted' : d === 'REJECTED' ? 'Rejected' : 'Needs evidence';
}

/** "Accepted by apikey:analyst on 2026-11-17 10:00 UTC (decided on version 1)": who, when and on which version. */
export function reviewNote(c: CurrentReview): string {
  const when = c.createdAt.replace('T', ' ').replace(/(\.\d+)?Z$/, ' UTC');
  return `${decisionLabel(c.decision)} by ${c.actor} on ${when}` + (c.inherited ? ` (decided on version ${c.version})` : '');
}

export function reviewLine(r: AssertionReview): string {
  return reviewNote({ ...r, inherited: false }) + ` (version ${r.version})`;
}

/** With "undecided only", sentences whose assertion already has a decision are hidden; otherwise everything shows. */
export function visibleSentence(assertionId: string, reviews: InvestigationReviews | null, undecidedOnly: boolean): boolean {
  return !undecidedOnly || !reviews || !reviews.current[assertionId];
}

export function historyOf(assertionId: string, reviews: InvestigationReviews | null): AssertionReview[] {
  return reviews ? reviews.history.filter((r) => r.assertionId === assertionId) : [];
}
