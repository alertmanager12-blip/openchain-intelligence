import { DatePipe, LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { RouterLink } from '@angular/router';
import { EvidenceLine } from '../../shared/evidence-line';
import { Level } from '../../shared/levels';
import { AiProvider, AssertionReview, CurrentReview, InvestigationSummary, InvestigationAssertion, InvestigationDetail, InvestigationReviews, ProposalDryRun, ProposalTemplate, ReviewDecision, RuleProposal, RuleProposals, TraceSummary } from '../../core/models';
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
  imports: [DatePipe, LowerCasePipe, RouterLink, PageHeader, EvidenceLine, ...MAT_BUTTONS, ...MAT_TABLE, ...UI],
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
    .proposal { border-top: 1px solid var(--line, #ddd); padding: 8px 0; }
    .ai { border: 1px dashed var(--line, #ccc); border-radius: 6px; padding: 8px 10px; margin: 10px 0; }
    .ai select { font: inherit; font-size: 12px; }
    .proposal pre { max-height: 320px; overflow: auto; }
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
              @if (d.status === 'DONE') {
                <button matButton="outlined" class="sm" type="button" (click)="propose(d)" [disabled]="proposing()">Propose rules</button>
              }
            </div>
            @if (d.stale && (reviews()?.decided ?? 0) > 0) { <p class="note note--warn">Some reviews were taken on evidence that has since left the canonical chain: check them again.</p> }
            @if (d.report; as r) {
              @for (sec of r.sections; track sec.title) {
                <h3 class="small strong">{{ sec.title }}</h3>
                @if (sec.sentences.length === 0) { <p class="small muted">Nothing.</p> }
                @for (t of sec.sentences; track t.assertionId + $index) {
                  @if (visible(t.assertionId, reviews(), undecidedOnly())) {
                    <div class="small sentence">@if (sentenceLine(t); as ln) {<oci-evidence-line [level]="ln.level" [text]="ln.text" [cite]="ln.cite" (citeClick)="cite(d, $event)" />}
                      <span class="review">
                        <button type="button" (click)="edit(t.assertionId, 'ACCEPTED')" [attr.aria-label]="'Accept assertion ' + t.assertionId">Accept</button>
                        <button type="button" (click)="edit(t.assertionId, 'REJECTED')" [attr.aria-label]="'Reject assertion ' + t.assertionId">Reject</button>
                        <button type="button" (click)="edit(t.assertionId, 'NEEDS_EVIDENCE')" [attr.aria-label]="'Ask evidence for assertion ' + t.assertionId">Needs evidence</button>
                      </span>
                      @if (reviews()?.current?.[t.assertionId]; as c) {
                        <span class="decision {{ c.decision }}">{{ reviewNote(c) }}</span>
                        @if (c.comment) { <span class="human">Comment (human): {{ c.comment }}</span> }
                      }
                    </div>
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
            @if (d.status === 'DONE' && providers().length > 0) {
              <div class="ai" aria-labelledby="h-ai">
                <h3 id="h-ai" class="small strong">AI summary (optional)</h3>
                <div class="actions">
                  <label class="small" for="ai-provider">Provider</label>
                  <select id="ai-provider" [value]="provider()" (change)="provider.set($any($event.target).value)">
                    @for (p of providers(); track p.id) { <option [value]="p.id">{{ p.label }} ({{ p.model }})</option> }
                  </select>
                  <button matButton="outlined" class="sm" type="button" (click)="summarize(d)" [disabled]="summarizing()">{{ summarizing() ? 'Writing…' : 'Write summary' }}</button>
                </div>
                <p class="small muted">{{ providerNote(chosen()) }}</p>
                @for (s of summaries(); track s.id) {
                  <div class="proposal">
                    <p class="small"><strong>{{ summaryHeader(s) }}</strong></p>
                    @if (s.status === 'ACCEPTED') {
                      @for (t of s.sentences; track $index) {
                        <p class="small sentence">{{ t.text }}@for (a of t.assertionIds; track a) {<button type="button" class="cite" (click)="cite(d, a)">{{ a }}</button>}</p>
                      }
                    } @else {
                      @for (r of s.reasons; track $index) { <p class="small muted">{{ r }}</p> }
                    }
                  </div>
                }
              </div>
            }
            @if (proposals(); as pr) {
              <h3 class="small strong">Proposed rules ({{ pr.proposalVersion }})</h3>
              <p class="small muted">Drafts built from the assertions by fixed templates. They are disabled and stored nowhere: creating one adds a disabled rule, which you review and enable yourself in Rules. An alert from such a rule is a lead to check, not a finding.</p>
              @if (pr.proposals.length === 0) { <p class="small muted">No draft for this investigation.</p> }
              @for (p of pr.proposals; track p.ruleId) {
                <div class="proposal">
                  <p class="small"><strong>{{ templateLabel(p.template) }}</strong> <span class="mono">{{ p.ruleId }}</span> — from
                    @for (a of p.assertionIds; track a) {
                      <button type="button" class="cite" (click)="cite(d, a)">{{ a }}</button>@if (p.reviews[a]) { <span class="small"> ({{ decisionLabel(p.reviews[a]) | lowercase }})</span> }
                    }
                  </p>
                  <p class="small">{{ checksLine(p) }}</p>
                  <p class="small">{{ dryRunLine(p.dryRun) }}</p>
                  @for (w of p.dryRun.warnings; track w) { <p class="small warn">{{ w }}</p> }
                  <details><summary class="small">YAML</summary><pre>{{ p.yaml }}</pre></details>
                  <div class="actions">
                    <button matButton="outlined" class="sm" type="button" (click)="adopt(p)" [disabled]="adopted().has(p.ruleId)">
                      {{ adopted().has(p.ruleId) ? 'Created (disabled)' : 'Create as disabled rule' }}</button>
                    @if (adopted().has(p.ruleId)) { <a class="small" routerLink="/rules/code">Open in Rules</a> }
                  </div>
                </div>
              }
              @if (pr.skipped.length > 0) {
                <details><summary class="small">{{ pr.skipped.length }} assertion(s) not proposed</summary>
                  @for (k of pr.skipped; track k.assertionId + k.reason) { <p class="small"><span class="mono">{{ k.assertionId }}</span>: {{ k.reason }}</p> }
                </details>
              }
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
  readonly sentenceLine = sentenceLine;
  readonly proposals = signal<RuleProposals | null>(null);
  readonly providers = signal<AiProvider[]>([]);
  readonly provider = signal('');
  readonly summaries = signal<InvestigationSummary[]>([]);
  readonly summarizing = signal(false);
  readonly providerNote = providerNote;
  readonly summaryHeader = summaryHeader;
  chosen(): AiProvider | null { return this.providers().find((p) => p.id === this.provider()) ?? this.providers()[0] ?? null; }
  readonly proposing = signal(false);
  readonly adopted = signal<ReadonlySet<string>>(new Set());
  readonly templateLabel = templateLabel;
  readonly checksLine = checksLine;
  readonly dryRunLine = dryRunLine;
  private timer?: ReturnType<typeof setTimeout>;

  readonly short = (a: string) => (a.length > 14 ? `${a.slice(0, 8)}…${a.slice(-4)}` : a);
  readonly json = (a: unknown) => JSON.stringify(a, null, 2);

  constructor() {
    effect(() => { const id = this.id(); if (id) this.load(id); });
    // the AI section appears only when the instance offers providers (off by default)
    this.api.aiProviders().subscribe({ next: (p) => { this.providers.set(p); if (p.length > 0) this.provider.set(p[0].id); }, error: () => this.providers.set([]) });
  }

  open(t: TraceSummary): void { this.load(t.id); }

  load(id: string): void {
    this.selected.set(id);
    this.shown.set(null);
    this.editing.set(null);
    if (this.detail()?.id !== id) { this.reviews.set(null); this.proposals.set(null); this.adopted.set(new Set()); this.summaries.set([]); }
    clearTimeout(this.timer);
    this.api.investigation(id).subscribe({
      next: (d) => {
        if (this.selected() !== id) return;
        this.detail.set(d);
        if (d.status === 'PENDING' || d.status === 'RUNNING') this.timer = setTimeout(() => this.load(id), 2000);
        else { this.list.reload(); if (d.status === 'DONE') { this.loadReviews(id); this.loadSummaries(id); } }
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

  loadSummaries(id: string): void {
    if (this.providers().length === 0) return;
    this.api.investigationSummaries(id).subscribe({ next: (s) => { if (this.selected() === id) this.summaries.set(s); }, error: () => undefined });
  }

  summarize(d: InvestigationDetail): void {
    const p = this.chosen();
    if (!p) return;
    this.summarizing.set(true);
    this.api.summarize(d.id, p.id).subscribe({
      next: (s) => {
        this.summarizing.set(false);
        this.loadSummaries(d.id);
        if (s.status === 'ACCEPTED') this.toasts.success('Summary written', 'Checked against the assertions');
        else this.toasts.error(s.status === 'REFUSED' ? 'Summary refused' : 'Summary failed', s.reasons[0]);
      },
      error: (e: ApiError) => { this.summarizing.set(false); this.toasts.error(e.title, e.detail ?? undefined); },
    });
  }

  propose(d: InvestigationDetail): void {
    this.proposing.set(true);
    this.api.ruleProposals(d.id).subscribe({
      next: (p) => { this.proposing.set(false); if (this.selected() === d.id) this.proposals.set(p); },
      error: (e: ApiError) => { this.proposing.set(false); this.toasts.error(e.title, e.detail ?? undefined); },
    });
  }

  adopt(p: RuleProposal): void {
    this.api.createRule(p.yaml).subscribe({
      next: () => { this.adopted.set(new Set([...this.adopted(), p.ruleId])); this.toasts.success('Rule created, disabled', p.ruleId); },
      error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined),
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

export function templateLabel(t: ProposalTemplate): string {
  return { NEXT_HOP: 'Funds move on', COMMON_FUNDER: 'Shared funder', SUBJECT_DISPERSION: 'Dispersion again', LISTED_COUNTERPARTY: 'Listed counterparty' }[t];
}

export function checksLine(p: RuleProposal): string {
  return `Valid, disabled; ${p.checks.examplesPassed} example(s)` + (p.checks.testsPassed > 0 ? ` and ${p.checks.testsPassed} test(s)` : '') + ' passed.';
}

/** The dry run with the window it actually covered: an empty or short history is said, never hidden. */
export function dryRunLine(d: ProposalDryRun): string {
  if (d.totalEvents === 0 || !d.from || !d.to) return 'Dry run: no event of this network in memory, so nothing could be tested on real traffic.';
  const day = (s: string) => s.replace('T', ' ').replace(/(\.\d+)?Z$/, ' UTC');
  return `Dry run on ${d.totalEvents} event(s) from ${day(d.from)} to ${day(d.to)}: ${d.eventsInScope} in scope, ${d.triggers} trigger(s), `
    + `about ${d.estimatedAlertsPerDay.toFixed(1)} alert(s) per day.`;
}

/** Says, before anything is sent, where the assertions go. */
export function providerNote(p: AiProvider | null): string {
  if (!p) return '';
  return `Only this investigation's assertions are sent, to ${p.label}` + (p.dataLocation ? ` (${p.dataLocation})` : '')
    + '. The summary is shown only if every sentence is supported by the assertions it cites; the report above stays the reference.';
}

export function summaryHeader(s: InvestigationSummary): string {
  const by = `${s.provider}${s.model ? ', ' + s.model : ''}`;
  const when = s.createdAt.replace('T', ' ').replace(/(\.\d+)?Z$/, ' UTC');
  return s.status === 'ACCEPTED' ? `Written by AI (${by}) on ${when}, checked against the assertions`
    : s.status === 'REFUSED' ? `Refused (${by}) on ${when}: it said something the assertions do not support, so it is not shown`
    : `Failed (${by}) on ${when}`;
}

/** A report sentence as an evidence line (spec 2026-11-18): its assertion's level, never upgraded; it cites the assertion. */
export function sentenceLine(t: { text: string; assertionId: string; level: string }): { level: Level; text: string; cite: string } {
  const level: Level = t.level === 'OBSERVED' || t.level === 'CONFIRMED' || t.level === 'INFERRED' ? t.level : 'INFERRED';
  return { level, text: t.text, cite: t.assertionId };
}
