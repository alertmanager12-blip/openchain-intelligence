import { JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, HostListener, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { ALERT_STATUSES, Alert, AlertStatus, Page, SEVERITIES, Severity } from '../../core/models';
import { timeAgo } from '../../shared/format';
import { TableState, query } from '../../shared/query';
import { UI } from '../../shared/ui';

const SEV_RANK: Record<Severity, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, INFO: 0 };

/** Lifecycle actions available from each status (mirrors the backend state machine). */
const ACTIONS: Record<AlertStatus, { to: AlertStatus; label: string; primary?: boolean }[]> = {
  OPEN: [{ to: 'ACKNOWLEDGED', label: 'Acknowledge', primary: true }, { to: 'INVESTIGATING', label: 'Investigate' }, { to: 'RESOLVED', label: 'Resolve' }, { to: 'FALSE_POSITIVE', label: 'False positive' }],
  ACKNOWLEDGED: [{ to: 'INVESTIGATING', label: 'Investigate', primary: true }, { to: 'RESOLVED', label: 'Resolve' }, { to: 'FALSE_POSITIVE', label: 'False positive' }],
  INVESTIGATING: [{ to: 'RESOLVED', label: 'Resolve', primary: true }, { to: 'FALSE_POSITIVE', label: 'False positive' }],
  RESOLVED: [{ to: 'OPEN', label: 'Reopen' }],
  FALSE_POSITIVE: [{ to: 'OPEN', label: 'Reopen' }],
};

@Component({
  selector: 'oci-alerts',
  imports: [FormsModule, JsonPipe, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Alert Center</h1>
          <p>Alerts are raised by your rules. Triage them, record your reasoning, and mark false positives so rules can be tuned.</p>
        </div>
        <button class="btn" type="button" (click)="load()">Refresh</button>
      </div>

      <section class="card card--flush">
        <div class="toolbar">
          <label>Status<select [ngModel]="status()" (ngModelChange)="status.set($event)">
            <option value="">All</option>@for (s of statuses; track s) { <option [value]="s">{{ label(s) }}</option> }
          </select></label>
          <label>Severity<select [ngModel]="severity()" (ngModelChange)="severity.set($event)">
            <option value="">All</option>@for (s of severities; track s) { <option [value]="s">{{ s }}</option> }
          </select></label>
          <label class="grow">Search<input type="search" [ngModel]="t.filter()" (ngModelChange)="t.filter.set($event)" placeholder="rule, address, id, assignee" /></label>
          <span class="small muted">{{ t.view().length }} alert(s)</span>
        </div>
        <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.data()" [empty]="t.view().length === 0" emptyText="No alert matches these filters." (retry)="list.retry()">
          <div class="table-wrap"><table>
            <thead><tr>
              <th scope="col" [attr.aria-sort]="t.aria('severity')"><button type="button" (click)="t.sort('severity')">Severity {{ t.icon('severity') }}</button></th>
              <th scope="col">Alert</th>
              <th scope="col" [attr.aria-sort]="t.aria('status')"><button type="button" (click)="t.sort('status')">Status {{ t.icon('status') }}</button></th>
              <th scope="col">Evidence</th>
              <th scope="col" [attr.aria-sort]="t.aria('assignee')"><button type="button" (click)="t.sort('assignee')">Assignee {{ t.icon('assignee') }}</button></th>
              <th scope="col" [attr.aria-sort]="t.aria('created')"><button type="button" (click)="t.sort('created')">Created {{ t.icon('created') }}</button></th>
            </tr></thead>
            <tbody>
              @for (a of t.view(); track a.id) {
                <tr class="clickable" [class.selected]="selected()?.id === a.id" (click)="open(a)" (keydown.enter)="open(a)" tabindex="0" [attr.aria-label]="'Open alert ' + a.id">
                  <td><oci-severity [value]="a.severity" /></td>
                  <td><strong>{{ a.ruleName }}</strong><div class="small muted"><span class="mono">{{ a.id }}</span> · <oci-net [value]="a.network" /></div></td>
                  <td><span class="status st-{{ a.status.toLowerCase() }}">{{ label(a.status) }}</span></td>
                  <td><oci-evidence [value]="a.evidenceLevel" /></td>
                  <td class="small">{{ a.assignee ?? '—' }}</td>
                  <td class="small nowrap">{{ ago(a.createdAt) }}</td>
                </tr>
              }
            </tbody>
          </table></div>
        </oci-state>
      </section>
    </div>

    @if (selected(); as a) {
      <div class="drawer-backdrop" (click)="close()" aria-hidden="true"></div>
      <aside class="drawer" role="dialog" aria-modal="true" aria-labelledby="drawer-title">
        <div class="row">
          <oci-severity [value]="a.severity" /><span class="status st-{{ a.status.toLowerCase() }}">{{ label(a.status) }}</span>
          <span class="spacer"></span><button class="btn btn--ghost" type="button" (click)="close()" aria-label="Close details" autofocus>✕</button>
        </div>
        <h2 id="drawer-title">{{ a.ruleName }}</h2>
        <p>{{ a.summary }}</p>
        <p class="note">This alert means rule <span class="mono">{{ a.ruleId }}</span> matched an event. It is not a finding of wrongdoing; record your conclusion below.</p>
        <dl class="kv">
          <dt>Alert</dt><dd class="mono">{{ a.id }}</dd>
          <dt>Address</dt><dd>@if (a.address) { <oci-addr [address]="a.address" [network]="a.network" [full]="true" /> }</dd>
          <dt>Transaction</dt><dd class="mono">{{ a.txHash ?? '—' }}</dd>
          <dt>Evidence</dt><dd><oci-evidence [value]="a.evidenceLevel" /></dd>
          <dt>Created</dt><dd>{{ a.createdAt }}</dd>
        </dl>

        <section>
          <h3>Lifecycle</h3>
          <label>Reason (optional, kept in history)<input [ngModel]="reason()" (ngModelChange)="reason.set($event)" placeholder="e.g. known exchange rebalancing" /></label>
          <div class="row mt">
            @for (act of actions(a.status); track act.to) {
              <button class="btn btn--sm" [class.btn--primary]="act.primary" type="button" [disabled]="busy()" (click)="move(a, act.to)">{{ act.label }}</button>
            }
          </div>
        </section>

        <section>
          <h3>Assignee</h3>
          <form class="row" (submit)="$event.preventDefault(); assign(a)">
            <label class="grow"><span class="sr-only">Assignee email</span><input type="email" name="assignee" [ngModel]="assignee()" (ngModelChange)="assignee.set($event)" placeholder="analyst@company.com (empty = unassign)" /></label>
            <button class="btn btn--sm" type="submit" [disabled]="busy()">Assign</button>
          </form>
        </section>

        @if (actionError(); as e) { <p class="field-error" role="alert">{{ e.title }}: {{ e.detail }}</p> }

        <section>
          <h3>Comments</h3>
          @for (c of a.comments; track c.id) {
            <div class="comment"><div class="small muted">{{ c.author }} · {{ ago(c.createdAt) }}</div><p>{{ c.body }}</p></div>
          } @empty { <p class="small muted">No comment yet.</p> }
          <form class="stack" (submit)="$event.preventDefault(); comment(a)">
            <label>Add a comment<textarea name="c" rows="3" [ngModel]="draft()" (ngModelChange)="draft.set($event)"></textarea></label>
            <div><button class="btn btn--sm" type="submit" [disabled]="busy() || !draft().trim()">Post comment</button></div>
          </form>
        </section>

        <section>
          <h3>Matched event</h3>
          <pre class="code">{{ a.matchedEvent | json }}</pre>
        </section>

        <section>
          <h3>History</h3>
          <ol class="timeline">
            @for (h of a.history; track $index) {
              <li><strong>{{ h.action }}</strong> by {{ h.actor }} <span class="small muted">· {{ ago(h.at) }}</span>@if (h.detail) { <div class="small">{{ h.detail }}</div> }</li>
            }
          </ol>
        </section>
      </aside>
    }
  `,
  styles: `
    .grow { flex: 1; min-width: 200px; }
    .mt { margin-top: 8px; }
    .status { font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 4px; background: var(--surface-2); white-space: nowrap; }
    .st-open { color: var(--danger); } .st-acknowledged { color: var(--warn); } .st-investigating { color: var(--accent); }
    .st-resolved { color: var(--ok); } .st-false_positive { color: var(--muted); }
    .comment { border-left: 2px solid var(--border); padding-left: 10px; margin-bottom: 8px; }
  `,
})
export class AlertsPage {
  private readonly api = inject(ApiService);
  /** Query params (deep links from Overview). */
  readonly statusParam = input<AlertStatus | ''>('', { alias: 'status' });
  readonly severityParam = input<Severity | ''>('', { alias: 'severity' });

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
  }

  @HostListener('document:keydown.escape')
  close(): void {
    this.selected.set(null);
  }

  load(): void {
    this.list.retry();
  }

  label(s: AlertStatus): string {
    return s === 'FALSE_POSITIVE' ? 'False positive' : s.charAt(0) + s.slice(1).toLowerCase();
  }

  actions(s: AlertStatus) {
    return ACTIONS[s];
  }

  open(a: Alert): void {
    this.selected.set(a);
    this.assignee.set(a.assignee ?? '');
    this.reason.set('');
    this.draft.set('');
    this.actionError.set(null);
    this.api.alert(a.id).subscribe({ next: (full) => this.selected()?.id === full.id && this.selected.set(full), error: () => undefined });
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
