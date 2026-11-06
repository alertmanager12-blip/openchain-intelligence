import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, HostListener, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { ALERT_STATUSES, Alert, AlertStatus, Page, RiskScore, SEVERITIES, Severity } from '../../core/models';
import { shortHash, timeAgo } from '../../shared/format';
import { TableState, query } from '../../shared/query';
import { UI, sentence } from '../../shared/ui';
import { compareScale, isoDuration, opSymbol, parseCondition, parseEventId, parseStep } from './evidence';

const SEV_RANK: Record<Severity, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, INFO: 0 };

/** Lifecycle actions available from each status (mirrors the backend state machine). */
const ACTIONS: Record<AlertStatus, { to: AlertStatus; label: string; primary?: boolean }[]> = {
  OPEN: [{ to: 'ACKNOWLEDGED', label: 'Acknowledge alert', primary: true }, { to: 'INVESTIGATING', label: 'Start investigating' }, { to: 'RESOLVED', label: 'Resolve' }, { to: 'FALSE_POSITIVE', label: 'Mark false positive' }],
  ACKNOWLEDGED: [{ to: 'INVESTIGATING', label: 'Start investigating', primary: true }, { to: 'RESOLVED', label: 'Resolve' }, { to: 'FALSE_POSITIVE', label: 'Mark false positive' }],
  INVESTIGATING: [{ to: 'RESOLVED', label: 'Resolve', primary: true }, { to: 'FALSE_POSITIVE', label: 'Mark false positive' }],
  RESOLVED: [{ to: 'OPEN', label: 'Reopen alert' }],
  FALSE_POSITIVE: [{ to: 'OPEN', label: 'Reopen alert' }],
};

@Component({
  selector: 'oci-alerts',
  imports: [FormsModule, JsonPipe, DatePipe, RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Alerts</h1>
          <p>Alerts are raised by your rules. Triage them, record your reasoning, and mark false positives so the rules can be tuned.</p>
        </div>
        <button class="btn" type="button" (click)="load()">Refresh list</button>
      </div>
      @if (!selected()) {
        @if (actionError(); as e) { <div class="state state--error" role="alert"><strong>{{ e.title }}</strong><p>{{ e.detail }} Pick the alert from the list instead.</p></div> }
      }

      <section class="card card--flush" aria-label="Alert list">
        <div class="toolbar">
          <label>Status<select [ngModel]="status()" (ngModelChange)="status.set($event)">
            <option value="">All statuses</option>@for (s of statuses; track s) { <option [value]="s">{{ label(s) }}</option> }
          </select></label>
          <label>Severity<select [ngModel]="severity()" (ngModelChange)="severity.set($event)">
            <option value="">All severities</option>@for (s of severities; track s) { <option [value]="s">{{ sentence(s) }}</option> }
          </select></label>
          <label class="grow">Search<input type="search" [ngModel]="t.filter()" (ngModelChange)="t.filter.set($event)" placeholder="Rule, address, alert id or assignee" /></label>
          <span class="small muted count" aria-live="polite"><span class="num">{{ t.view().length }}</span> {{ t.view().length === 1 ? 'alert' : 'alerts' }}</span>
        </div>
        <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.data()" [empty]="t.view().length === 0" emptyText="No alert matches these filters. Clear the search or choose another status." (retry)="list.retry()">
          <div class="table-wrap"><table>
            <thead><tr>
              <th scope="col" [attr.aria-sort]="t.aria('severity')"><button type="button" (click)="t.sort('severity')">Severity {{ t.icon('severity') }}</button></th>
              <th scope="col">Alert</th>
              <th scope="col" [attr.aria-sort]="t.aria('status')"><button type="button" (click)="t.sort('status')">Status {{ t.icon('status') }}</button></th>
              <th scope="col">Evidence</th>
              <th scope="col" [attr.aria-sort]="t.aria('assignee')"><button type="button" (click)="t.sort('assignee')">Assignee {{ t.icon('assignee') }}</button></th>
              <th scope="col" class="right" [attr.aria-sort]="t.aria('created')"><button type="button" (click)="t.sort('created')">Created {{ t.icon('created') }}</button></th>
            </tr></thead>
            <tbody>
              @for (a of t.view(); track a.id) {
                <tr class="clickable" [class.selected]="selected()?.id === a.id" (click)="open(a)" (keydown.enter)="open(a)" tabindex="0" [attr.aria-label]="'Open alert ' + a.id">
                  <td><oci-severity [value]="a.severity" /></td>
                  <td><span class="rule">{{ a.ruleName }}</span><div class="sub"><span class="mono">{{ a.id }}</span><oci-net [value]="a.network" /></div></td>
                  <td><span class="status st-{{ a.status.toLowerCase() }}">{{ label(a.status) }}</span></td>
                  <td><oci-evidence [value]="a.evidenceLevel" /></td>
                  <td class="small">{{ a.assignee ?? 'Unassigned' }}</td>
                  <td class="small nowrap right">{{ ago(a.createdAt) }}</td>
                </tr>
              }
            </tbody>
          </table></div>
        </oci-state>
      </section>
    </div>

    @if (selected(); as a) {
      <div class="drawer-backdrop" (click)="close()" aria-hidden="true"></div>
      <aside class="drawer sheet" role="dialog" aria-modal="true" aria-labelledby="drawer-title">
        <header class="sheet-head">
          <div class="row">
            <oci-severity [value]="a.severity" /><span class="status st-{{ a.status.toLowerCase() }}">{{ label(a.status) }}</span><oci-evidence [value]="a.evidenceLevel" />
            <span class="spacer"></span>
            <button class="btn btn--sm btn--ghost" type="button" (click)="close()" aria-label="Close alert details" autofocus>Close</button>
          </div>
          <h2 id="drawer-title">{{ a.ruleName }}</h2>
          <p class="summary">{{ a.summary }}</p>
          <dl class="meta">
            <div><dt>Alert</dt><dd class="mono">{{ a.id }}</dd></div>
            <div><dt>Network</dt><dd><oci-net [value]="a.network" /></dd></div>
            <div><dt>Created</dt><dd>{{ a.createdAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }} UTC</dd></div>
            @if (a.occurrenceCount && a.occurrenceCount > 1) { <div><dt>Occurrences</dt><dd class="num">{{ a.occurrenceCount }}</dd></div> }
          </dl>
        </header>

        <div class="sheet-body">
          <div class="why" aria-labelledby="h-why">
            <h3 id="h-why" class="h">Why this alert fired</h3>
            <p class="note">Rule <span class="mono">{{ a.ruleId }}</span> matched the values below. This is not a finding of wrongdoing: record your conclusion in the panel on the right.</p>

            <dl class="kv rule-kv">
              <dt>Rule</dt><dd>{{ a.ruleName }} <span class="muted">({{ a.ruleId }}@if (a.ruleVersion) {, version {{ a.ruleVersion }}})</span></dd>
              <dt>Severity</dt><dd><oci-severity [value]="a.severity" /></dd>
              <dt>Evidence level</dt><dd class="ev-dd"><oci-evidence [value]="a.evidenceLevel" /><span class="small muted">{{ evidenceHelp(a) }}</span></dd>
            </dl>

            <h4>Measured values</h4>
            @if (a.evidence; as ev) {
              @if (ev.kind === 'AGGREGATION') {
                <div class="measure">
                  <div class="m-label">{{ aggLabel(ev.function, ev.field) }} per {{ ev.groupBy.join(', ') || 'rule' }}, over {{ dur(ev.window) }}</div>
                  <dl class="measures">
                    <div><dt>Observed</dt><dd>{{ ev.value }}</dd></div>
                    <div><dt>Threshold</dt><dd>{{ op(ev.operator) }} {{ ev.threshold }}</dd></div>
                    <div><dt>Window</dt><dd>{{ dur(ev.window) }}</dd></div>
                    <div><dt>Events in window</dt><dd>{{ ev.eventCount }}</dd></div>
                  </dl>
                  @if (scale(ev.value, ev.threshold); as sc) {
                    <div class="cmp" aria-hidden="true"><span class="cmp-bar" [style.width.%]="sc.observed"></span><span class="cmp-thr" [style.left.%]="sc.threshold"></span></div>
                    <p class="small muted">Bar: observed value. Line: threshold. Window {{ ev.windowStartExclusive | date: 'MMM d, HH:mm' : 'UTC' }} to {{ ev.windowEnd | date: 'MMM d, HH:mm' : 'UTC' }} UTC.</p>
                  }
                </div>
              }
              @if (ev.kind === 'SEQUENCE') {
                <p class="small">Steps completed in order for <span class="mono">{{ ev.key }}</span>, within {{ dur(ev.within) }} (actual span {{ dur(ev.span) }}).</p>
                <ol class="steps">
                  @for (st of steps(ev.steps); track $index) {
                    <li><strong>{{ st.name }}</strong><span class="small muted">&#32;{{ st.at | date: 'MMM d, HH:mm:ss' : 'UTC' }} UTC</span><div class="mono small">{{ st.eventId }}</div></li>
                  }
                </ol>
              }
              @if (conditions(ev.conditions).length) {
                <div class="table-wrap"><table class="cond">
                  <caption class="sr-only">Rule conditions with observed values</caption>
                  <thead><tr><th scope="col">Field</th><th scope="col">Condition</th><th scope="col" class="right">Observed</th><th scope="col">Result</th></tr></thead>
                  <tbody>
                    @for (c of conditions(ev.conditions); track $index) {
                      <tr>
                        <td class="mono">{{ c.field }}</td>
                        <td class="nowrap">{{ op(c.op) }} {{ c.expected }}</td>
                        <td class="right strong">{{ c.actual }}</td>
                        <td><span class="res" [class.met]="c.matched">{{ c.matched ? 'Met' : 'Not met' }}</span></td>
                      </tr>
                    }
                  </tbody>
                </table></div>
              }
            } @else {
              <p class="muted small">The server returned no engine evidence for this alert. The raw matched event below is all that was recorded.</p>
            }

            <h4>Contributing transactions</h4>
            @if (txs(a).length) {
              <ul class="txs">
                @for (x of txs(a); track x.key) {
                  <li><a class="mono" [routerLink]="['/explorer']" [queryParams]="{ q: x.hash, network: x.network }" [attr.title]="x.hash">{{ short(x.hash) }}</a>@if (x.logIndex) { <span class="small muted">log {{ x.logIndex }}</span> }</li>
                }
              </ul>
            } @else { <p class="muted small">No transaction is attached to this alert.</p> }

            @if (a.address) {
              <h4>Subject wallet</h4>
              <p><oci-addr [address]="a.address" [network]="a.network" [full]="true" /></p>
              <oci-state [loading]="risk.loading()" [error]="risk.error()" [hasData]="!!risk.data()" loadingText="Loading the wallet score…" (retry)="risk.retry()">
                @if (risk.data(); as r) {
                  <oci-gauge size="lg" [score]="r.score" [level]="r.level" label="Subject wallet risk score" />
                  <table class="contrib">
                    <caption class="sr-only">Largest contributions to the wallet score</caption>
                    <thead><tr><th scope="col">Top contributions</th><th scope="col">Evidence</th><th scope="col" class="right">Points</th></tr></thead>
                    <tbody>@for (c of topContributions(); track c.factor) {
                      <tr><td>{{ factorLabel(c.factor) }}<div class="small muted">{{ c.source }}</div></td><td><oci-evidence [value]="c.evidenceLevel" /></td><td class="right strong">{{ c.points > 0 ? '+' : '' }}{{ c.points }}</td></tr>
                    }</tbody>
                  </table>
                  <a class="small" [routerLink]="['/wallets', a.network, a.address]">Open the wallet to see every contribution</a>
                }
              </oci-state>
            }

            <details class="raw">
              <summary>Raw matched event</summary>
              <pre class="code">{{ a.matchedEvent | json }}</pre>
            </details>
          </div>

          <div class="rail">
            <section aria-labelledby="h-life">
              <h3 id="h-life" class="h">Lifecycle</h3>
              <label>Reason (optional, kept in history)<input [ngModel]="reason()" (ngModelChange)="reason.set($event)" placeholder="e.g. known exchange rebalancing" /></label>
              <div class="row mt">
                @for (act of actions(a.status); track act.to) {
                  <button class="btn btn--sm" [class.btn--primary]="act.primary" type="button" [disabled]="busy()" (click)="move(a, act.to)">{{ act.label }}</button>
                }
              </div>
            </section>

            <section aria-labelledby="h-asg">
              <h3 id="h-asg" class="h">Assignee</h3>
              <form class="row" (submit)="$event.preventDefault(); assign(a)">
                <label class="grow"><span class="sr-only">Assignee email</span><input type="email" name="assignee" [ngModel]="assignee()" (ngModelChange)="assignee.set($event)" placeholder="Email, or empty to unassign" /></label>
                <button class="btn btn--sm" type="submit" [disabled]="busy()">Assign</button>
              </form>
            </section>

            @if (actionError(); as e) { <p class="field-error" role="alert">{{ e.title }}: {{ e.detail }}</p> }

            <section aria-labelledby="h-hist">
              <h3 id="h-hist" class="h">History</h3>
              <ol class="timeline">
                @for (h of a.history; track $index) {
                  <li><span class="t-act">{{ action(h.action) }}</span><span class="small">&#32;by {{ h.actor }}</span>
                    <div class="small muted"><time [attr.datetime]="h.at">{{ h.at | date: 'MMM d, HH:mm' : 'UTC' }} UTC</time>, {{ ago(h.at) }}</div>
                    @if (h.detail) { <div class="small">{{ h.detail }}</div> }</li>
                }
              </ol>
            </section>

            <section aria-labelledby="h-com">
              <h3 id="h-com" class="h">Comments</h3>
              @for (c of a.comments; track c.id) {
                <div class="comment"><div class="small muted">{{ c.author }}, {{ ago(c.createdAt) }}</div><p>{{ c.body }}</p></div>
              } @empty { <p class="small muted">No comment yet. Write down what you checked so the next analyst does not repeat it.</p> }
              <form class="stack" (submit)="$event.preventDefault(); comment(a)">
                <label>Add a comment<textarea name="c" rows="3" [ngModel]="draft()" (ngModelChange)="draft.set($event)"></textarea></label>
                <div><button class="btn btn--sm" type="submit" [disabled]="busy() || !draft().trim()">Post comment</button></div>
              </form>
            </section>
          </div>
        </div>
      </aside>
    }
  `,
  styles: `
    .grow { flex: 1; min-width: 200px; }
    .count { align-self: center; margin-left: auto; }
    .mt { margin-top: 8px; }
    .rule { font-weight: 500; }
    .sub { display: flex; gap: 8px; align-items: center; margin-top: 2px; font-size: 12px; color: var(--muted); }
    .sheet { width: min(1120px, 100vw); padding: 0; gap: 0; grid-template-rows: auto 1fr; align-content: stretch; }
    .sheet-head { padding: 16px 24px 16px; border-bottom: 1px solid var(--line); position: sticky; top: 0; background: var(--surface); z-index: 1; }
    .sheet-head h2 { font-size: 20px; margin: 10px 0 4px; }
    .summary { color: var(--ink-2); margin: 0 0 10px; }
    .meta { display: flex; flex-wrap: wrap; gap: 4px 24px; margin: 0; font-size: 13px; }
    .meta div { display: flex; gap: 6px; align-items: baseline; }
    .meta dt { color: var(--muted); }
    .meta dd { margin: 0; }
    .sheet-body { display: grid; grid-template-columns: minmax(0, 1fr) 340px; }
    .why { padding: 20px 24px 32px; display: grid; gap: 12px; align-content: start; min-width: 0; }
    .rail { padding: 20px 24px 32px; border-left: 1px solid var(--line); background: var(--bg); display: grid; gap: 24px; align-content: start; }
    @media (max-width: 860px) { .sheet-body { grid-template-columns: minmax(0, 1fr); } .rail { border-left: 0; border-top: 1px solid var(--line); } }
    .h { font-size: 16px; margin: 0 0 4px; }
    h4 { font-size: 14px; margin: 12px 0 0; padding-top: 12px; border-top: 1px solid var(--line-soft); }
    .rule-kv { margin-bottom: 4px; }
    .ev-dd { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .m-label { font-size: 13px; color: var(--ink-2); margin-bottom: 6px; }
    .cmp { position: relative; height: 8px; background: var(--line-soft); margin: 10px 0 6px; }
    .cmp-bar { position: absolute; inset: 0 auto 0 0; background: var(--ink-2); }
    .cmp-thr { position: absolute; top: -4px; bottom: -4px; width: 2px; margin-left: -1px; background: var(--risk-high); }
    .strong { font-weight: 600; }
    .res { font-size: 13px; color: var(--muted); }
    .res.met { color: var(--ink); font-weight: 500; }
    .cond td.mono { font-size: 12px; }
    .steps { margin: 0; padding-left: 20px; display: grid; gap: 6px; font-size: 13px; }
    .txs { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 4px 16px; font-size: 13px; }
    .txs li { display: flex; gap: 8px; align-items: baseline; }
    .contrib { margin-top: 8px; }
    .raw summary { cursor: pointer; font-size: 13px; color: var(--ink-2); padding: 4px 0; }
    .raw pre { margin-top: 8px; }
    .t-act { font-weight: 600; }
    .comment { border-left: 2px solid var(--line); padding-left: 10px; margin-bottom: 8px; }
    .comment p { margin: 2px 0 0; }
  `,
})
export class AlertsPage {
  private readonly api = inject(ApiService);
  /** Query params (deep links from Overview). */
  readonly statusParam = input<AlertStatus | ''>('', { alias: 'status' });
  readonly severityParam = input<Severity | ''>('', { alias: 'severity' });
  /** Deep link to one alert (?id=al_0001), e.g. from the Overview recent alerts. */
  readonly idParam = input<string | undefined>(undefined, { alias: 'id' });

  readonly statuses = ALERT_STATUSES;
  readonly severities = SEVERITIES;
  readonly status = signal<AlertStatus | ''>('');
  readonly severity = signal<Severity | ''>('');
  readonly list = query<Page<Alert>>();
  readonly selected = signal<Alert | null>(null);
  readonly busy = signal(false);
  readonly actionError = signal<ApiError | null>(null);
  readonly reason = signal('');
  readonly assignee = signal('');
  readonly draft = signal('');
  readonly ago = timeAgo;
  readonly sentence = sentence;
  readonly dur = isoDuration;
  readonly op = opSymbol;
  readonly scale = compareScale;
  readonly short = (h: string) => shortHash(h, 10, 8);
  /** Score of the alert's subject wallet (GET /wallets/{a}/risk), loaded when an alert opens. */
  readonly risk = query<RiskScore>();
  readonly topContributions = computed(() => [...(this.risk.data()?.contributions ?? [])].sort((x, y) => Math.abs(y.points) - Math.abs(x.points)).slice(0, 3));

  private readonly rows = signal<Alert[]>([]);
  readonly t = new TableState<Alert>(
    this.rows,
    { severity: (a) => SEV_RANK[a.severity], status: (a) => ALERT_STATUSES.indexOf(a.status), assignee: (a) => a.assignee, created: (a) => a.createdAt },
    (a) => `${a.id} ${a.ruleName} ${a.ruleId} ${a.address ?? ''} ${a.txHash ?? ''} ${a.assignee ?? ''}`,
    { key: 'created', dir: 'desc' },
  );

  constructor() {
    effect(() => {
      this.status.set(this.statusParam() ?? '');
      this.severity.set(this.severityParam() ?? '');
    });
    effect(() => {
      const status = this.status();
      const severity = this.severity();
      untracked(() => this.list.run(() => this.api.alerts({ status, severity, limit: 200 })));
    });
    effect(() => this.rows.set(this.list.data()?.items ?? []));
    effect(() => {
      const id = this.idParam();
      if (id) untracked(() => this.openById(id));
    });
  }

  private openById(id: string): void {
    this.api.alert(id).subscribe({ next: (a) => this.open(a, false), error: (e: ApiError) => this.actionError.set(e) });
  }

  evidenceHelp(a: Alert): string {
    return a.evidenceLevel === 'ANOMALY' ? 'Unusual behaviour, no risk claim.' : a.evidenceLevel === 'RISK_SIGNAL' ? 'Pattern associated with risk, not proof.' : 'Stated by a cited external source.';
  }

  aggLabel(fn: string, field?: string): string {
    const f = fn.toLowerCase().replace(/_/g, ' ');
    return field ? `${sentence(f)} of ${field}` : f === 'count' ? 'Number of matching events' : sentence(f);
  }

  conditions(cs: string[] | undefined) {
    return (cs ?? []).map(parseCondition).filter((c) => c !== null);
  }

  steps(s: string[]) {
    return s.map(parseStep);
  }

  /** Transactions behind the alert: matched event ids, plus the alert's own tx hash. */
  txs(a: Alert): { key: string; network: Alert['network']; hash: string; logIndex: string }[] {
    const out = new Map<string, { key: string; network: Alert['network']; hash: string; logIndex: string }>();
    for (const id of a.matchedEventIds ?? []) {
      const p = parseEventId(id);
      if (p) out.set(id, { key: id, ...p });
    }
    if (a.txHash && ![...out.values()].some((x) => x.hash === a.txHash)) out.set(a.txHash, { key: a.txHash, network: a.network, hash: a.txHash, logIndex: '' });
    return [...out.values()];
  }

  factorLabel(f: string): string {
    return sentence(f);
  }

  action(a: string): string {
    return a === 'CREATED' ? 'Created' : a === 'ANNOTATED' ? 'Annotated' : this.label(a as AlertStatus);
  }

  @HostListener('document:keydown.escape')
  close(): void {
    this.selected.set(null);
  }

  load(): void {
    this.list.retry();
  }

  label(s: AlertStatus): string {
    return sentence(s);
  }

  actions(s: AlertStatus) {
    return ACTIONS[s];
  }

  open(a: Alert, refetch = true): void {
    this.selected.set(a);
    this.assignee.set(a.assignee ?? '');
    this.reason.set('');
    this.draft.set('');
    this.actionError.set(null);
    if (a.address) {
      const { address, network } = a;
      this.risk.run(() => this.api.walletRisk(address, network), false);
    }
    if (refetch) this.api.alert(a.id).subscribe({ next: (full) => this.selected()?.id === full.id && this.selected.set(full), error: () => undefined });
  }

  private mutate(call: Observable<Alert>, after?: () => void): void {
    this.busy.set(true);
    this.actionError.set(null);
    call.subscribe({
      next: (updated) => {
        this.selected.set(updated);
        this.rows.update((rs) => rs.map((r) => (r.id === updated.id ? updated : r)));
        this.busy.set(false);
        after?.();
      },
      error: (e: ApiError) => {
        this.actionError.set(e);
        this.busy.set(false);
      },
    });
  }

  move(a: Alert, to: AlertStatus): void {
    const reason = this.reason().trim() || undefined;
    this.mutate(to === 'ACKNOWLEDGED' && !reason ? this.api.acknowledgeAlert(a.id) : this.api.setAlertStatus(a.id, to, reason), () => this.reason.set(''));
  }

  assign(a: Alert): void {
    this.mutate(this.api.assignAlert(a.id, this.assignee().trim() || null));
  }

  comment(a: Alert): void {
    this.mutate(this.api.commentAlert(a.id, this.draft().trim()), () => this.draft.set(''));
  }
}
