import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { ApiError, errorText } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { RouterLink } from '@angular/router';
import { LOCALE, fmtNumber } from '../../core/i18n/format';
import { hasKey, I18nService, Lang, translate } from '../../core/i18n/i18n.service';
import { EngineText } from '../../shared/engine-text';
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
  imports: [RouterLink, EngineText, PageHeader, EvidenceLine, ...MAT_BUTTONS, ...MAT_TABLE, ...UI],
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
      <oci-page-header [heading]="i18n.t('investigations.title')" [lede]="i18n.t('investigations.lede')" />
      <div class="grid">
        <section class="card card--flush" aria-labelledby="h-inv">
          <div class="card-head"><h2 id="h-inv">{{ i18n.t('investigations.yours') }}</h2></div>
          <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.value()" [empty]="(list.value() ?? []).length === 0" skeleton="table"
            [emptyText]="i18n.t('investigations.empty')" (retry)="list.reload()">
            <div class="table-wrap"><table mat-table [dataSource]="list.value() ?? []">
              <ng-container matColumnDef="subject">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('investigations.address') }}</th>
                <td mat-cell *matCellDef="let t"><span class="mono">{{ short(t.seed) }}</span> <span class="small muted">{{ i18n.t('investigations.version', { version: t.version }) }}</span></td>
              </ng-container>
              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('investigations.status') }}</th>
                <td mat-cell *matCellDef="let t">{{ statusText(t.status) }}@if (t.stale) { <strong class="warn">, {{ i18n.t('investigations.stale') }}</strong> }</td>
              </ng-container>
              <ng-container matColumnDef="created">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('investigations.created') }}</th>
                <td mat-cell *matCellDef="let t" class="nowrap">{{ when(t.createdAt) }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="cols"></tr>
              <tr mat-row *matRowDef="let t; columns: cols" [class.sel]="t.id === selected()" (click)="open(t)" (keydown.enter)="open(t)" tabindex="0"></tr>
            </table></div>
          </oci-state>
        </section>

        <section class="card" aria-labelledby="h-report">
          <div class="card-head"><h2 id="h-report">{{ i18n.t('investigations.report') }}</h2></div>
          @if (!selected()) {
            <p class="small muted">{{ i18n.t('investigations.select') }}</p>
          } @else if (detail(); as d) {
            <p class="small"><span class="mono">{{ d.subject }}</span> {{ i18n.t('investigations.meta', { network: d.network, version: d.version, plan: d.plan, frozen: d.frozenFlows, status: statusText(d.status) }) }}</p>
            @if (d.stale) { <p class="note note--warn">{{ i18n.t('investigations.staleNote', { count: d.staleEventIds?.length ?? 0 }) }}</p> }
            @if (d.error) { <p class="note note--warn"><oci-engine-text [text]="d.error" [plainInEnglish]="true" /></p> }
            @if (d.diff; as df) { <p class="small">{{ i18n.t('investigations.since', { added: df.added.length, removed: df.removed.length }) }}</p> }
            <div class="actions">
              <button matButton="outlined" class="sm" type="button" (click)="rerun(d)" [disabled]="d.status === 'PENDING' || d.status === 'RUNNING'">{{ i18n.t('investigations.rerun') }}</button>
              @if (reviews(); as rv) {
                <button matButton="outlined" class="sm" type="button" (click)="undecidedOnly.set(!undecidedOnly())" [attr.aria-pressed]="undecidedOnly()">
                  {{ i18n.t(undecidedOnly() ? 'investigations.showAll' : 'investigations.undecided') }}</button>
                <span class="small muted">{{ i18n.t('investigations.reviewed', { decided: rv.decided, total: rv.total }) }}</span>
              }
              @if (d.status === 'DONE') {
                <button matButton="outlined" class="sm" type="button" (click)="propose(d)" [disabled]="proposing()">{{ i18n.t('investigations.propose') }}</button>
              }
            </div>
            @if (d.stale && (reviews()?.decided ?? 0) > 0) { <p class="note note--warn">{{ i18n.t('investigations.staleReviews') }}</p> }
            @if (d.report; as r) {
              @for (sec of r.sections; track sec.title) {
                <h3 class="small strong">{{ sectionTitle(sec.title, i18n.lang()) }}</h3>
                @if (sec.sentences.length === 0) { <p class="small muted">{{ i18n.t('investigations.nothing') }}</p> }
                @for (t of sec.sentences; track t.assertionId + $index) {
                  @if (visible(t.assertionId, reviews(), undecidedOnly())) {
                    <div class="small sentence">@if (sentenceLine(t, i18n.lang()); as ln) {<oci-evidence-line [level]="ln.level" [text]="ln.text" [cite]="ln.cite" [textLang]="ln.textLang ?? null" (citeClick)="cite(d, $event)" />}
                      <span class="review">
                        <button type="button" (click)="edit(t.assertionId, 'ACCEPTED')" [attr.aria-label]="i18n.t('investigations.acceptAria', { id: t.assertionId })">{{ i18n.t('investigations.accept') }}</button>
                        <button type="button" (click)="edit(t.assertionId, 'REJECTED')" [attr.aria-label]="i18n.t('investigations.rejectAria', { id: t.assertionId })">{{ i18n.t('investigations.reject') }}</button>
                        <button type="button" (click)="edit(t.assertionId, 'NEEDS_EVIDENCE')" [attr.aria-label]="i18n.t('investigations.needsAria', { id: t.assertionId })">{{ i18n.t('investigations.needs') }}</button>
                      </span>
                      @if (reviews()?.current?.[t.assertionId]; as c) {
                        <span class="decision {{ c.decision }}">{{ reviewNote(c, i18n.lang()) }}</span>
                        @if (c.comment) { <span class="human">{{ i18n.t('investigations.humanComment', { comment: c.comment }) }}</span> }
                      }
                    </div>
                    @if (editing()?.assertionId === t.assertionId) {
                      <div class="editor">
                        <label class="small" [attr.for]="'c-' + t.assertionId">{{ i18n.t('investigations.editorLabel', { decision: decisionLabel(editing()!.decision, i18n.lang()) }) }}</label>
                        <textarea [id]="'c-' + t.assertionId" maxlength="2000" [value]="comment()" (input)="comment.set($any($event.target).value)"></textarea>
                        <div class="actions">
                          <button matButton="filled" class="sm" type="button" (click)="save(d)" [disabled]="saving()">{{ i18n.t('investigations.save') }}</button>
                          <button matButton="outlined" class="sm" type="button" (click)="editing.set(null)">{{ i18n.t('investigations.cancel') }}</button>
                        </div>
                      </div>
                    }
                  }
                }
              }
              <p class="small muted">{{ i18n.t('investigations.reportNote', { templates: r.templates }) }}</p>
            }
            @if (d.status === 'DONE' && providers().length > 0) {
              <div class="ai" aria-labelledby="h-ai">
                <h3 id="h-ai" class="small strong">{{ i18n.t('investigations.ai') }}</h3>
                <div class="actions">
                  <label class="small" for="ai-provider">{{ i18n.t('investigations.provider') }}</label>
                  <select id="ai-provider" [value]="provider()" (change)="provider.set($any($event.target).value)">
                    @for (p of providers(); track p.id) { <option [value]="p.id">{{ p.label }} ({{ p.model }})</option> }
                  </select>
                  <button matButton="outlined" class="sm" type="button" (click)="summarize(d)" [disabled]="summarizing()">{{ i18n.t(summarizing() ? 'investigations.writing' : 'investigations.write') }}</button>
                </div>
                <p class="small muted">{{ providerNote(chosen(), i18n.lang()) }}</p>
                @for (s of summaries(); track s.id) {
                  <div class="proposal">
                    <p class="small"><strong>{{ summaryHeader(s, i18n.lang()) }}</strong></p>
                    @if (s.status === 'ACCEPTED') {
                      @for (t of s.sentences; track $index) {
                        <p class="small sentence"><oci-engine-text [text]="t.text" [plainInEnglish]="true" />@for (a of t.assertionIds; track a) {<button type="button" class="cite" (click)="cite(d, a)">{{ a }}</button>}</p>
                      }
                    } @else {
                      @for (r of s.reasons; track $index) { <p class="small muted"><oci-engine-text [text]="r" [plainInEnglish]="true" /></p> }
                    }
                  </div>
                }
              </div>
            }
            @if (proposals(); as pr) {
              <h3 class="small strong">{{ i18n.t('investigations.proposed', { version: pr.proposalVersion }) }}</h3>
              <p class="small muted">{{ i18n.t('investigations.proposedNote') }}</p>
              @if (pr.proposals.length === 0) { <p class="small muted">{{ i18n.t('investigations.noDraft') }}</p> }
              @for (p of pr.proposals; track p.ruleId) {
                <div class="proposal">
                  <p class="small"><strong>{{ templateLabel(p.template, i18n.lang()) }}</strong> <span class="mono">{{ p.ruleId }}</span> — {{ i18n.t('investigations.from') }}
                    @for (a of p.assertionIds; track a) {
                      <button type="button" class="cite" (click)="cite(d, a)">{{ a }}</button>@if (p.reviews[a]) { <span class="small"> ({{ decisionLabel(p.reviews[a], i18n.lang()).toLowerCase() }})</span> }
                    }
                  </p>
                  <p class="small">{{ checksLine(p, i18n.lang()) }}</p>
                  <p class="small">{{ dryRunLine(p.dryRun, i18n.lang()) }}</p>
                  @for (w of p.dryRun.warnings; track w) { <p class="small warn"><oci-engine-text [text]="w" [plainInEnglish]="true" /></p> }
                  <details><summary class="small">YAML</summary><pre>{{ p.yaml }}</pre></details>
                  <div class="actions">
                    <button matButton="outlined" class="sm" type="button" (click)="adopt(p)" [disabled]="adopted().has(p.ruleId)">
                      {{ i18n.t(adopted().has(p.ruleId) ? 'investigations.adopted' : 'investigations.adopt') }}</button>
                    @if (adopted().has(p.ruleId)) { <a class="small" routerLink="/rules/code">{{ i18n.t('investigations.openRules') }}</a> }
                  </div>
                </div>
              }
              @if (pr.skipped.length > 0) {
                <details><summary class="small">{{ i18n.t('investigations.skipped', { count: pr.skipped.length }) }}</summary>
                  @for (k of pr.skipped; track k.assertionId + k.reason) { <p class="small"><span class="mono">{{ k.assertionId }}</span>: <oci-engine-text [text]="k.reason" [plainInEnglish]="true" /></p> }
                </details>
              }
            }
            @if (shown(); as a) {
              <h3 class="small strong">{{ i18n.t('investigations.assertion', { id: a.id }) }}</h3>
              <pre>{{ json(a) }}</pre>
              @if (historyOf(a.id, reviews()); as h) {
                @if (h.length > 0) {
                  <h3 class="small strong">{{ i18n.t('investigations.reviewsOf') }}</h3>
                  @for (x of h; track x.id) { <p class="small">{{ reviewLine(x, i18n.lang()) }}@if (x.comment) { <span class="human">{{ i18n.t('investigations.humanComment', { comment: x.comment }) }}</span> }</p> }
                }
              }
            }
          } @else {
            <p class="small muted">{{ i18n.t('investigations.loading') }}</p>
          }
        </section>
      </div>
    </div>
  `,
})
export class InvestigationsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);
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

  readonly sectionTitle = sectionTitle;
  readonly statusText = (s: string) => { const k = `traces.status.${s}`; return hasKey(k) ? this.i18n.t(k) : s.toLowerCase(); };
  /** Local date and time in the console's language (English keeps the US short form the date pipe gave). */
  readonly when = (iso: string) => new Date(iso).toLocaleString(this.i18n.lang() === 'en' ? 'en-US' : LOCALE.fr, { dateStyle: 'short', timeStyle: 'short' });
  private err(e: ApiError): void { this.toasts.error(e.title, errorText(e, this.i18n.lang())); }
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
      error: (e: ApiError) => { this.detail.set(null); this.err(e); },
    });
  }

  loadReviews(id: string): void {
    this.api.investigationReviews(id).subscribe({
      next: (r) => { if (this.selected() === id) this.reviews.set(r); },
      error: (e: ApiError) => this.err(e),
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
      next: () => { this.saving.set(false); this.editing.set(null); this.loadReviews(d.id); this.toasts.success(this.i18n.t('investigations.toast.decision'), decisionLabel(e.decision, this.i18n.lang())); },
      error: (err: ApiError) => { this.saving.set(false); this.err(err); },
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
        if (s.status === 'ACCEPTED') this.toasts.success(this.i18n.t('investigations.toast.summary'), this.i18n.t('investigations.toast.summaryBody'));
        else this.toasts.error(this.i18n.t(s.status === 'REFUSED' ? 'investigations.toast.refused' : 'investigations.toast.failed'), s.reasons[0]);
      },
      error: (e: ApiError) => { this.summarizing.set(false); this.err(e); },
    });
  }

  propose(d: InvestigationDetail): void {
    this.proposing.set(true);
    this.api.ruleProposals(d.id).subscribe({
      next: (p) => { this.proposing.set(false); if (this.selected() === d.id) this.proposals.set(p); },
      error: (e: ApiError) => { this.proposing.set(false); this.err(e); },
    });
  }

  adopt(p: RuleProposal): void {
    this.api.createRule(p.yaml).subscribe({
      next: () => { this.adopted.set(new Set([...this.adopted(), p.ruleId])); this.toasts.success(this.i18n.t('investigations.toast.adopted'), p.ruleId); },
      error: (e: ApiError) => this.err(e),
    });
  }

  cite(d: InvestigationDetail, id: string): void { this.shown.set(d.assertions?.find((a) => a.id === id) ?? null); }

  rerun(d: InvestigationDetail): void {
    this.api.rerunInvestigation(d.id).subscribe({
      next: (n) => { this.list.reload(); this.load(n.id); this.toasts.success(this.i18n.t('investigations.toast.rerun'), this.i18n.t('investigations.toast.rerunBody', { version: n.version })); },
      error: (e: ApiError) => this.err(e),
    });
  }
}

const utc = (iso: string) => iso.replace('T', ' ').replace(/(\.\d+)?Z$/, ' UTC');

export function decisionLabel(d: ReviewDecision, lang: Lang = 'en'): string {
  return translate(lang, `enum.decision.${d}`);
}

/** Report section titles come from the server as fixed English names: shown by name in the console's language. */
export function sectionTitle(title: string, lang: Lang = 'en'): string {
  const k = `investigations.section.${title}`;
  return hasKey(k) ? translate(lang, k) : title;
}

/** "Accepted by apikey:analyst on 2026-11-17 10:00 UTC (decided on version 1)": who, when and on which version (UTC, ISO order in both languages). */
export function reviewNote(c: CurrentReview, lang: Lang = 'en'): string {
  return translate(lang, c.inherited ? 'investigations.reviewInherited' : 'investigations.reviewBy',
    { decision: decisionLabel(c.decision, lang), actor: c.actor, when: utc(c.createdAt), version: c.version });
}

export function reviewLine(r: AssertionReview, lang: Lang = 'en'): string {
  return translate(lang, 'investigations.reviewVersion', { note: reviewNote({ ...r, inherited: false }, lang), version: r.version });
}

/** With "undecided only", sentences whose assertion already has a decision are hidden; otherwise everything shows. */
export function visibleSentence(assertionId: string, reviews: InvestigationReviews | null, undecidedOnly: boolean): boolean {
  return !undecidedOnly || !reviews || !reviews.current[assertionId];
}

export function historyOf(assertionId: string, reviews: InvestigationReviews | null): AssertionReview[] {
  return reviews ? reviews.history.filter((r) => r.assertionId === assertionId) : [];
}

export function templateLabel(t: ProposalTemplate, lang: Lang = 'en'): string {
  return translate(lang, `investigations.template.${t}`);
}

export function checksLine(p: RuleProposal, lang: Lang = 'en'): string {
  return translate(lang, p.checks.testsPassed > 0 ? 'investigations.checksTests' : 'investigations.checks', { examples: p.checks.examplesPassed, tests: p.checks.testsPassed });
}

/** The dry run with the window it actually covered: an empty or short history is said, never hidden. */
export function dryRunLine(d: ProposalDryRun, lang: Lang = 'en'): string {
  if (d.totalEvents === 0 || !d.from || !d.to) return translate(lang, 'investigations.dryRunNone');
  return translate(lang, 'investigations.dryRun', { events: String(d.totalEvents), from: utc(d.from), to: utc(d.to), scope: d.eventsInScope, triggers: d.triggers,
    perDay: fmtNumber(d.estimatedAlertsPerDay, lang, 1, 1) });
}

/** Says, before anything is sent, where the assertions go. */
export function providerNote(p: AiProvider | null, lang: Lang = 'en'): string {
  if (!p) return '';
  return translate(lang, 'investigations.providerNote', { label: p.label, where: p.dataLocation ? ` (${p.dataLocation})` : '' });
}

export function summaryHeader(s: InvestigationSummary, lang: Lang = 'en'): string {
  return translate(lang, `investigations.summary.${s.status}`, { by: `${s.provider}${s.model ? ', ' + s.model : ''}`, when: utc(s.createdAt) });
}

/**
 * A report sentence as an evidence line (spec 2026-11-18): its assertion's level, never upgraded; it cites the assertion.
 * The sentence is the engine's English wording: in French it is tagged textLang "en" so it shows under the engine label.
 */
export function sentenceLine(t: { text: string; assertionId: string; level: string }, lang: Lang = 'en'): { level: Level; text: string; cite: string; textLang?: string } {
  const level: Level = t.level === 'OBSERVED' || t.level === 'CONFIRMED' || t.level === 'INFERRED' ? t.level : 'INFERRED';
  return { level, text: t.text, cite: t.assertionId, ...(lang === 'en' ? {} : { textLang: 'en' }) };
}
