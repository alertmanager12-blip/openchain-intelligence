import { KeyValuePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { MatProgressBar } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ApiError, errorText } from '../../core/api-error';
import { fmtNumber, fmtStamp } from '../../core/i18n/format';
import { I18nService } from '../../core/i18n/i18n.service';
import { Rule, RuleSimulation, RuleTestRequest } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { EngineText } from '../../shared/engine-text';
import { shortHash, timeAgo } from '../../shared/format';
import { levelWord } from '../../shared/levels';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { ruleText } from '../../shared/rule-text';
import { NetworkChip, StateComponent } from '../../shared/ui';

const DRAFT = '__draft__';
const PERIODS = [1, 7, 30] as const;

function readDraft(): string | null {
  try {
    return sessionStorage.getItem('oci.sim.yaml');
  } catch {
    return null;
  }
}

@Component({
  selector: 'oci-simulator',
  imports: [RouterLink, KeyValuePipe, PageHeader, MatProgressBar, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, NetworkChip, StateComponent, EngineText],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <oci-page-header [heading]="i18n.t('simulator.title')" [lede]="i18n.t('simulator.lede')" />

      <section class="panel-raised run" aria-labelledby="h-run">
        <h2 id="h-run" class="sr-only">{{ i18n.t('simulator.dryRun') }}</h2>
        <form class="fields" (submit)="$event.preventDefault(); run()">
          <mat-form-field class="grow">
            <mat-label>{{ i18n.t('simulator.rule') }}</mat-label>
            <mat-select [value]="ruleId()" (valueChange)="ruleId.set($event)">
              @if (draftYaml) { <mat-option [value]="draftKey">{{ i18n.t('simulator.draft') }}</mat-option> }
              @for (r of rules(); track r.id) { <mat-option [value]="r.id">{{ ruleLabel(r) }} <span class="muted">({{ i18n.t(r.enabled ? 'simulator.enabled' : 'simulator.disabled') }})</span></mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>{{ i18n.t('simulator.period') }}</mat-label>
            <mat-select [value]="days()" (valueChange)="days.set($event)">@for (p of periods; track p) { <mat-option [value]="p">{{ periodLabel(p) }}</mat-option> }</mat-select>
          </mat-form-field>
          <button matButton="filled" type="submit" [disabled]="!ruleId() || res.loading()">{{ i18n.t(res.loading() ? 'simulator.running' : 'simulator.run') }}</button>
        </form>
        @if (res.loading()) { <mat-progress-bar mode="indeterminate" [attr.aria-label]="i18n.t('simulator.replaying')" /> }

        @if (res.status() === 'idle') {
          <p class="empty-run">{{ i18n.t('simulator.idle') }}</p>
        } @else {
          <oci-state [loading]="res.loading()" [error]="res.error()" [hasData]="!!res.value()" skeleton="readout" [skeletonRows]="4" [loadingText]="i18n.t('simulator.replaying')" (retry)="res.reload()">
            @if (res.value(); as r) {
              <dl class="well instrument" [attr.aria-label]="i18n.t('simulator.results')">
                <div class="readout"><dt>{{ i18n.t('simulator.evaluated') }}</dt><dd>{{ n(r.evaluatedEvents) }}</dd></div>
                <div class="readout"><dt>{{ i18n.t('simulator.matches') }}</dt><dd>{{ n(r.matchCount) }}</dd></div>
                <div class="readout"><dt>{{ i18n.t('simulator.perDay') }}</dt><dd [class.warn]="r.permissive">{{ n(r.estimatedTriggersPerDay) }}<span class="unit">{{ i18n.t('simulator.estimated') }}</span></dd></div>
                <div class="readout"><dt>{{ i18n.t('simulator.rate') }}</dt><dd [class.warn]="r.permissive">{{ rate() }}</dd></div>
              </dl>
              @if (r.permissive) {
                <div class="note note--warn" role="alert"><strong>{{ i18n.t('simulator.permissive') }}</strong>
                  @for (w of r.warnings; track $index) { <div><oci-engine-text [text]="w" [plainInEnglish]="true" /></div> }
                  <a routerLink="/rules/builder">{{ i18n.t('simulator.tighten') }}</a></div>
              } @else if (r.warnings.length) {
                <div class="note">@for (w of r.warnings; track $index) { <div><oci-engine-text [text]="w" [plainInEnglish]="true" /></div> }</div>
              }
            }
          </oci-state>
        }
      </section>

      @if (ruleId() && ruleId() !== draftKey) {
        <section class="panel-raised run" aria-labelledby="h-cmp">
          <h2 id="h-cmp" class="small strong">{{ i18n.t('simulator.compare') }} <span class="muted">({{ simulated() }})</span></h2>
          <p class="muted small">{{ i18n.t('simulator.compareNote') }}</p>
          <div class="cmp-actions">
            <button matButton="outlined" type="button" (click)="editCopy()">{{ i18n.t('simulator.editCopy') }}</button>
            <button matButton="filled" type="button" [disabled]="!proposed() || comparing()" (click)="compare()">{{ i18n.t(comparing() ? 'simulator.comparing' : 'simulator.compareBtn') }}</button>
          </div>
          @if (proposed() !== null) {
            <textarea class="cmp-yaml mono" [attr.aria-label]="i18n.t('simulator.proposedAria')" [value]="proposed()" (input)="proposed.set($any($event.target).value)"></textarea>
          }
          @if (cmp(); as c) {
            <dl class="well instrument" [attr.aria-label]="i18n.t('simulator.comparison')">
              <div class="readout"><dt>{{ i18n.t('simulator.matchesStored', { version: c.currentVersion }) }}</dt><dd>{{ n(c.before.conditionMatches) }}</dd></div>
              <div class="readout"><dt>{{ i18n.t('simulator.matchesProposed', { version: c.proposedVersion }) }}</dt><dd>{{ n(c.after.conditionMatches) }}</dd></div>
              <div class="readout"><dt>{{ i18n.t('simulator.stops') }}</dt><dd>{{ n(c.onlyBefore) }}</dd></div>
              <div class="readout"><dt>{{ i18n.t('simulator.starts') }}</dt><dd>{{ n(c.onlyAfter) }}</dd></div>
              <div class="readout"><dt>{{ i18n.t('simulator.alertsDay') }}</dt><dd>{{ n(c.before.estimatedAlertsPerDay) }} → {{ n(c.after.estimatedAlertsPerDay) }}<span class="unit">{{ i18n.t('simulator.estimated') }}</span></dd></div>
            </dl>
            <p class="muted small">{{ i18n.t('simulator.cmpEvents', { n: c.evaluatedEvents }) }}{{ range(c.window) }}. <oci-engine-text [text]="c.window.note" [plainInEnglish]="true" /></p>
          }
        </section>
      }

      @if (res.value(); as r) {
        <section class="section" aria-labelledby="h-m">
          <div class="section-head"><h2 id="h-m">{{ i18n.t('simulator.matched') }}</h2><p>{{ i18n.t('simulator.matchedLede') }}</p></div>
          <div class="table-wrap"><table mat-table [dataSource]="r.matches" multiTemplateDataRows [trackBy]="byEvent">
            <ng-container matColumnDef="x">
              <th mat-header-cell *matHeaderCellDef scope="col" class="x"><span class="sr-only">{{ i18n.t('simulator.details') }}</span></th>
              <td mat-cell *matCellDef="let m; let i = dataIndex" class="x"><button matIconButton class="sm expander" type="button" [attr.aria-expanded]="open().has(m.eventId)" [attr.aria-controls]="'ev-' + i"
                (click)="toggle(m.eventId)" [attr.aria-label]="i18n.t('simulator.fieldsOf', { id: m.eventId })">
                <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6"><path d="m6 4 4 4-4 4"/></svg></button></td>
            </ng-container>
            <ng-container matColumnDef="event">
              <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('simulator.col.event') }}</th>
              <td mat-cell *matCellDef="let m" class="mono small nowrap" [matTooltip]="m.eventId">{{ short(m.eventId) }}</td>
            </ng-container>
            <ng-container matColumnDef="network">
              <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('simulator.col.network') }}</th>
              <td mat-cell *matCellDef="let m"><oci-net [value]="m.network" /></td>
            </ng-container>
            <ng-container matColumnDef="when">
              <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('simulator.col.when') }}</th>
              <td mat-cell *matCellDef="let m" class="small nowrap">{{ ago(m.eventTime) }}</td>
            </ng-container>
            <ng-container matColumnDef="summary">
              <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('simulator.col.summary') }}</th>
              <td mat-cell *matCellDef="let m"><oci-engine-text [text]="m.summary" [plainInEnglish]="true" /></td>
            </ng-container>
            <ng-container matColumnDef="detail">
              <td mat-cell *matCellDef="let m; let i = dataIndex" [attr.colspan]="cols.length" [id]="'ev-' + i">
                @if (open().has(m.eventId)) {
                  <dl class="kv fields">@for (f of m.fields | keyvalue; track f.key) { <dt class="mono">{{ f.key }}</dt><dd [class.mono]="isHex(f.value)">{{ text(f.value) }}</dd> }</dl>
                }
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="cols"></tr>
            <tr mat-row *matRowDef="let m; columns: cols"></tr>
            <tr mat-row *matRowDef="let m; columns: ['detail']" class="detail-row" [class.open]="open().has(m.eventId)"></tr>
            <tr class="mat-mdc-row" *matNoDataRow><td class="mat-mdc-cell" [attr.colspan]="cols.length"><div class="empty"><strong>{{ i18n.t('simulator.noMatch') }}</strong><span>{{ i18n.t('simulator.noMatchHint') }}</span></div></td></tr>
          </table></div>
        </section>
      }
    </div>
  `,
  styles: `
    .run { display: grid; gap: 16px; }
    mat-progress-bar { margin-top: -8px; }
    .empty-run { margin: 0; color: var(--ink-2); padding: 14px 16px; border-radius: var(--r-well); background: var(--well); box-shadow: var(--shadow-inset); }
    dl.fields { padding-left: 40px; font-size: 13px; }
    .cmp-actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 8px 0; }
    .cmp-yaml { width: 100%; min-height: 220px; font-size: 12px; }
    @media (max-width: 720px) { dl.fields { padding-left: 0; } }
  `,
})
export class SimulatorPage {
  private readonly api = inject(ApiService);
  protected readonly i18n = inject(I18nService);
  /** ?rule=<id> preselects a stored rule; ?draft=1 comes from the builder (the YAML is in sessionStorage). */
  readonly rule = input<string>();
  readonly draft = input<string>();

  readonly draftKey = DRAFT;
  readonly periods = PERIODS;
  readonly draftYaml = readDraft();
  private readonly rulesRes = apiResource({ params: () => ({}), stream: () => this.api.rules().pipe(catchError(() => of({ items: [] as Rule[], nextCursor: null }))) });
  readonly rules = computed(() => this.rulesRes.value()?.items ?? []);
  /** Draft first, then the requested rule, then the first stored rule; the user's choice wins until the list changes. */
  readonly ruleId = linkedSignal(() => {
    const wanted = this.rule();
    const rules = this.rules();
    if (wanted && rules.some((r) => r.id === wanted)) return wanted;
    if (this.draftYaml) return DRAFT;
    return rules[0]?.id ?? '';
  });
  readonly days = signal(7);

  /** Set by run(); the nonce makes a re-run with the same rule and period issue a new request. */
  private readonly request = signal<{ req: RuleTestRequest; nonce: number } | undefined>(undefined);
  readonly res = apiResource({ params: () => this.request(), stream: (p) => this.api.testRule(p.req) });
  private nonce = 0;

  readonly proposed = signal<string | null>(null);
  readonly cmp = signal<RuleSimulation | null>(null);
  readonly comparing = signal(false);
  private readonly toasts = inject(Toasts);

  editCopy(): void {
    const r = this.rules().find((x) => x.id === this.ruleId());
    this.cmp.set(null);
    this.proposed.set(r?.yaml ?? '');
  }

  compare(): void {
    const src = this.proposed();
    if (!src) return;
    this.comparing.set(true);
    this.api.simulateRule(this.ruleId(), src).subscribe({
      next: (c) => { this.comparing.set(false); this.cmp.set(c); },
      error: (e: ApiError) => { this.comparing.set(false); this.toasts.error(errorText(e, this.i18n.lang())); },
    });
  }

  readonly open = signal(new Set<string>());
  readonly cols = ['x', 'event', 'network', 'when', 'summary'];
  readonly byEvent = (_: number, m: { eventId: string }) => m.eventId;
  protected ago(iso: string): string { return timeAgo(iso, Date.now(), this.i18n.lang()); }
  protected n(v: number): string { return fmtNumber(v, this.i18n.lang()); }
  protected ruleLabel(r: Rule): string { return ruleText(r, this.i18n.lang()).name; }
  protected simulated(): string { return levelWord('SIMULATED', this.i18n.lang()); }
  protected periodLabel(days: number): string { return this.i18n.t(`simulator.period.${days}` as 'simulator.period.1'); }
  /** " from X to Y" when the server gave the window of the comparison. */
  protected range(w: { from: string | null; to: string | null }): string {
    const lang = this.i18n.lang();
    return w.from && w.to ? this.i18n.t('simulator.cmpRange', { from: `${fmtStamp(w.from, lang)} UTC`, to: `${fmtStamp(w.to, lang)} UTC` }) : '';
  }
  readonly short = (id: string) => shortHash(id, 22, 6);
  readonly rate = computed(() => {
    const r = this.res.value();
    const lang = this.i18n.lang();
    if (!r || !r.evaluatedEvents) return '–';
    const pct = (r.matchCount / r.evaluatedEvents) * 100;
    const sign = lang === 'fr' ? '\u202f%' : '%';
    return pct < 0.01 ? `<${fmtNumber(0.01, lang, 2, 2)}${sign}` : `${fmtNumber(pct, lang, 2, 2)}${sign}`;
  });

  run(): void {
    const to = new Date();
    const from = new Date(to.getTime() - this.days() * 86_400_000);
    const id = this.ruleId();
    const base = { from: from.toISOString(), to: to.toISOString(), mode: 'DRY_RUN' as const };
    this.open.set(new Set());
    this.request.set({ req: id === DRAFT ? { ...base, yaml: this.draftYaml ?? '' } : { ...base, ruleId: id }, nonce: ++this.nonce });
  }

  toggle(id: string): void {
    this.open.update((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  text(v: unknown): string {
    return typeof v === 'string' ? v : JSON.stringify(v);
  }
  isHex(v: unknown): boolean {
    return typeof v === 'string' && /^(0x)?[0-9a-fA-F]{16,}$|^[1-9A-HJ-NP-Za-km-z]{26,}$/.test(v);
  }
}
