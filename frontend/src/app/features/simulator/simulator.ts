import { KeyValuePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { MatProgressBar } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Rule, RuleTestRequest } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { shortHash, timeAgo } from '../../shared/format';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { NetworkChip, StateComponent } from '../../shared/ui';

const DRAFT = '__draft__';
const PERIODS = [{ v: 1, l: 'Last 24 hours' }, { v: 7, l: 'Last 7 days' }, { v: 30, l: 'Last 30 days' }];

function readDraft(): string | null {
  try {
    return sessionStorage.getItem('oci.sim.yaml');
  } catch {
    return null;
  }
}

@Component({
  selector: 'oci-simulator',
  imports: [RouterLink, KeyValuePipe, PageHeader, MatProgressBar, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, NetworkChip, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <oci-page-header heading="Rule simulator" lede="Replay a rule over past events as a dry run: nothing is alerted or sent. Use it to estimate noise before you enable a rule." />

      <section class="panel-raised run" aria-labelledby="h-run">
        <h2 id="h-run" class="sr-only">Dry run</h2>
        <form class="fields" (submit)="$event.preventDefault(); run()">
          <mat-form-field class="grow">
            <mat-label>Rule</mat-label>
            <mat-select [value]="ruleId()" (valueChange)="ruleId.set($event)">
              @if (draftYaml) { <mat-option [value]="draftKey">Unsaved draft from the builder</mat-option> }
              @for (r of rules(); track r.id) { <mat-option [value]="r.id">{{ r.name }} <span class="muted">({{ r.enabled ? 'enabled' : 'disabled' }})</span></mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Period</mat-label>
            <mat-select [value]="days()" (valueChange)="days.set($event)">@for (p of periods; track p.v) { <mat-option [value]="p.v">{{ p.l }}</mat-option> }</mat-select>
          </mat-form-field>
          <button matButton="filled" type="submit" [disabled]="!ruleId() || res.loading()">{{ res.loading() ? 'Running…' : 'Run dry run' }}</button>
        </form>
        @if (res.loading()) { <mat-progress-bar mode="indeterminate" aria-label="Replaying events" /> }

        @if (res.status() === 'idle') {
          <p class="empty-run">Choose a rule and a period, then run it to see how many events it would have matched and how many alerts it would have raised.</p>
        } @else {
          <oci-state [loading]="res.loading()" [error]="res.error()" [hasData]="!!res.value()" skeleton="readout" [skeletonRows]="4" loadingText="Replaying events" (retry)="res.reload()">
            @if (res.value(); as r) {
              <dl class="well instrument" aria-label="Dry run results">
                <div class="readout"><dt>Events evaluated</dt><dd>{{ n(r.evaluatedEvents) }}</dd></div>
                <div class="readout"><dt>Matches</dt><dd>{{ n(r.matchCount) }}</dd></div>
                <div class="readout"><dt>Alerts per day, after cooldown</dt><dd [class.warn]="r.permissive">{{ r.estimatedTriggersPerDay }}<span class="unit">estimated</span></dd></div>
                <div class="readout"><dt>Match rate</dt><dd [class.warn]="r.permissive">{{ rate() }}</dd></div>
              </dl>
              @if (r.permissive) {
                <div class="note note--warn" role="alert"><strong>This rule looks too permissive.</strong>
                  @for (w of r.warnings; track $index) { <div>{{ w }}</div> }
                  <a routerLink="/rules/builder">Tighten the conditions in the rule builder</a></div>
              } @else if (r.warnings.length) {
                <div class="note">@for (w of r.warnings; track $index) { <div>{{ w }}</div> }</div>
              }
            }
          </oci-state>
        }
      </section>

      @if (res.value(); as r) {
        <section class="section" aria-labelledby="h-m">
          <div class="section-head"><h2 id="h-m">Matched events</h2><p>A sample of the events the rule matched. Expand a row to see the fields it was evaluated on.</p></div>
          <div class="table-wrap"><table mat-table [dataSource]="r.matches" multiTemplateDataRows [trackBy]="byEvent">
            <ng-container matColumnDef="x">
              <th mat-header-cell *matHeaderCellDef scope="col" class="x"><span class="sr-only">Details</span></th>
              <td mat-cell *matCellDef="let m; let i = dataIndex" class="x"><button matIconButton class="sm expander" type="button" [attr.aria-expanded]="open().has(m.eventId)" [attr.aria-controls]="'ev-' + i"
                (click)="toggle(m.eventId)" [attr.aria-label]="'Fields of event ' + m.eventId">
                <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6"><path d="m6 4 4 4-4 4"/></svg></button></td>
            </ng-container>
            <ng-container matColumnDef="event">
              <th mat-header-cell *matHeaderCellDef scope="col">Event</th>
              <td mat-cell *matCellDef="let m" class="mono small nowrap" [matTooltip]="m.eventId">{{ short(m.eventId) }}</td>
            </ng-container>
            <ng-container matColumnDef="network">
              <th mat-header-cell *matHeaderCellDef scope="col">Network</th>
              <td mat-cell *matCellDef="let m"><oci-net [value]="m.network" /></td>
            </ng-container>
            <ng-container matColumnDef="when">
              <th mat-header-cell *matHeaderCellDef scope="col">When</th>
              <td mat-cell *matCellDef="let m" class="small nowrap">{{ ago(m.eventTime) }}</td>
            </ng-container>
            <ng-container matColumnDef="summary">
              <th mat-header-cell *matHeaderCellDef scope="col">Summary</th>
              <td mat-cell *matCellDef="let m">{{ m.summary }}</td>
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
            <tr class="mat-mdc-row" *matNoDataRow><td class="mat-mdc-cell" [attr.colspan]="cols.length"><div class="empty"><strong>No event matched in this period.</strong><span>Try a longer period or loosen a condition.</span></div></td></tr>
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
    @media (max-width: 720px) { dl.fields { padding-left: 0; } }
  `,
})
export class SimulatorPage {
  private readonly api = inject(ApiService);
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

  readonly open = signal(new Set<string>());
  readonly cols = ['x', 'event', 'network', 'when', 'summary'];
  readonly byEvent = (_: number, m: { eventId: string }) => m.eventId;
  readonly ago = timeAgo;
  readonly n = (v: number) => v.toLocaleString('en-US');
  readonly short = (id: string) => shortHash(id, 22, 6);
  readonly rate = computed(() => {
    const r = this.res.value();
    if (!r || !r.evaluatedEvents) return '–';
    const pct = (r.matchCount / r.evaluatedEvents) * 100;
    return pct < 0.01 ? '<0.01%' : `${pct.toFixed(2)}%`;
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
