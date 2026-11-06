import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Rule, RuleTestResult } from '../../core/models';
import { timeAgo } from '../../shared/format';
import { query } from '../../shared/query';
import { UI } from '../../shared/ui';

const DRAFT = '__draft__';
const PERIODS = [{ v: 1, l: 'Last 24 hours' }, { v: 7, l: 'Last 7 days' }, { v: 30, l: 'Last 30 days' }];

@Component({
  selector: 'oci-simulator',
  imports: [FormsModule, RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Rule Simulator</h1>
          <p>Replay a rule over past events in DRY_RUN mode: nothing is alerted or sent. Use it to estimate noise before enabling a rule.</p>
        </div>
      </div>
      <form class="card row" (submit)="$event.preventDefault(); run()">
        <label class="grow">Rule<select name="rule" [ngModel]="ruleId()" (ngModelChange)="ruleId.set($event)">
          @if (draftYaml()) { <option [value]="draftKey">Unsaved draft from the builder</option> }
          @for (r of rules(); track r.id) { <option [value]="r.id">{{ r.name }} ({{ r.enabled ? 'enabled' : 'disabled' }})</option> }
        </select></label>
        <label>Period<select name="p" [ngModel]="days()" (ngModelChange)="days.set(+$event)">@for (p of periods; track p.v) { <option [value]="p.v">{{ p.l }}</option> }</select></label>
        <button class="btn btn--primary" type="submit" [disabled]="!ruleId() || res.loading()">{{ res.loading() ? 'Running…' : 'Run dry run' }}</button>
      </form>

      @if (res.status() !== 'idle') {
        <oci-state [loading]="res.loading()" [error]="res.error()" [hasData]="!!res.data()" (retry)="res.retry()" loadingText="Replaying events…">
          @if (res.data(); as r) {
            <div class="grid grid-cards">
              <div class="card"><div class="small muted">Events evaluated</div><div class="kpi">{{ r.evaluatedEvents.toLocaleString('en-US') }}</div></div>
              <div class="card"><div class="small muted">Matches</div><div class="kpi">{{ r.matchCount.toLocaleString('en-US') }}</div></div>
              <div class="card"><div class="small muted">Estimated alerts / day</div><div class="kpi" [class.bad]="r.permissive">{{ r.estimatedTriggersPerDay }}</div><div class="small muted">after cooldown</div></div>
              <div class="card"><div class="small muted">Match rate</div><div class="kpi">{{ rate() }}</div></div>
            </div>
            @if (r.permissive) {
              <div class="note note--warn" role="alert"><strong>This rule looks too permissive.</strong>
                @for (w of r.warnings; track $index) { <div>{{ w }}</div> }
                <a routerLink="/rules/builder">Tighten it in the builder →</a></div>
            } @else if (r.warnings.length) {
              <div class="note">@for (w of r.warnings; track $index) { <div>{{ w }}</div> }</div>
            }
            <section class="card card--flush">
              <h2>Matched events (sample)</h2>
              <div class="table-wrap"><table>
                <thead><tr><th scope="col">Event</th><th scope="col">Network</th><th scope="col">When</th><th scope="col">Summary</th></tr></thead>
                <tbody>@for (m of r.matches; track m.eventId) {
                  <tr><td class="mono small">{{ m.eventId.slice(0, 28) }}…</td><td><oci-net [value]="m.network" /></td><td class="small nowrap">{{ ago(m.eventTime) }}</td><td class="mono small">{{ m.summary }}</td></tr>
                } @empty { <tr><td colspan="4" class="muted">No event matched in this period.</td></tr> }</tbody>
              </table></div>
            </section>
          }
        </oci-state>
      }
    </div>
  `,
  styles: `.grow { flex: 1; min-width: 240px; } .bad { color: var(--danger); }`,
})
export class SimulatorPage {
  private readonly api = inject(ApiService);
  readonly rule = input<string>();
  readonly draft = input<string>();

  readonly draftKey = DRAFT;
  readonly periods = PERIODS;
  readonly rules = signal<Rule[]>([]);
  readonly ruleId = signal('');
  readonly days = signal(7);
  readonly draftYaml = signal<string | null>(null);
  readonly res = query<RuleTestResult>();
  readonly ago = timeAgo;
  readonly rate = computed(() => {
    const r = this.res.data();
    if (!r || !r.evaluatedEvents) return '—';
    const pct = (r.matchCount / r.evaluatedEvents) * 100;
    return pct < 0.01 ? '<0.01%' : `${pct.toFixed(2)}%`;
  });

  constructor() {
    try {
      const y = sessionStorage.getItem('oci.sim.yaml');
      if (y) {
        this.draftYaml.set(y);
        this.ruleId.set(DRAFT);
      }
    } catch {
      /* no draft */
    }
    this.api.rules().pipe(catchError(() => of({ items: [] as Rule[], nextCursor: null }))).subscribe((p) => {
      this.rules.set(p.items);
      const wanted = this.rule();
      if (wanted && p.items.some((r) => r.id === wanted)) this.ruleId.set(wanted);
      else if (!this.ruleId() && p.items[0]) this.ruleId.set(p.items[0].id);
    });
  }

  run(): void {
    const to = new Date();
    const from = new Date(to.getTime() - this.days() * 86_400_000);
    const id = this.ruleId();
    const req = { from: from.toISOString(), to: to.toISOString(), mode: 'DRY_RUN' as const };
    this.res.run(() => (id === DRAFT ? this.api.testRule({ ...req, yaml: this.draftYaml() ?? '' }) : this.api.testRule({ ...req, ruleId: id })), false);
  }
}
