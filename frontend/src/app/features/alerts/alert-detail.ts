import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBar } from '@angular/material/progress-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { FormField, email, form, required, submit } from '@angular/forms/signals';
import { Observable, firstValueFrom } from 'rxjs';
import { ApiError, errorText, toApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { Alert, AlertStatus } from '../../core/models';
import { I18nService, hasKey, type Key } from '../../core/i18n/i18n.service';
import { Toasts } from '../../core/toasts';
import { ruleName } from '../../shared/rule-text';
import { statusName, timeAgo } from '../../shared/format';
import { MAT_FORM } from '../../shared/material';
import { UI } from '../../shared/ui';
import { EngineText } from '../../shared/engine-text';
import { AlertWhy } from './alert-why';

export interface AlertAction { to: AlertStatus; label: Key; done: Key; primary?: boolean; key?: string }

const ACK: AlertAction = { to: 'ACKNOWLEDGED', label: 'alerts.act.acknowledge', done: 'alerts.done.acknowledge', primary: true, key: 'E' };
const INVESTIGATE: AlertAction = { to: 'INVESTIGATING', label: 'alerts.act.investigate', done: 'alerts.done.investigate' };
const RESOLVE: AlertAction = { to: 'RESOLVED', label: 'alerts.act.resolve', done: 'alerts.done.resolve' };
const FALSE_POSITIVE: AlertAction = { to: 'FALSE_POSITIVE', label: 'alerts.act.falsePositive', done: 'alerts.done.falsePositive' };
const REOPEN: AlertAction = { to: 'OPEN', label: 'alerts.act.reopen', done: 'alerts.done.reopen' };

/** Lifecycle actions available from each status (mirrors the backend state machine); `label` and `done` (the toast) are dictionary keys. */
export const ACTIONS: Record<AlertStatus, AlertAction[]> = {
  OPEN: [ACK, INVESTIGATE, RESOLVE, FALSE_POSITIVE],
  ACKNOWLEDGED: [{ ...INVESTIGATE, primary: true }, RESOLVE, FALSE_POSITIVE],
  INVESTIGATING: [{ ...RESOLVE, primary: true }, FALSE_POSITIVE],
  RESOLVED: [REOPEN],
  FALSE_POSITIVE: [REOPEN],
};

/**
 * The alert pane of the inbox: header with the lifecycle actions, then Overview (why it fired) and Activity
 * (history and comments). A labelled region, not a dialog: the list stays usable next to it.
 */
import { EvidenceLine } from '../../shared/evidence-line';
import { alertLines } from './alert-lines';
import { fmtStamp } from '../../core/i18n/format';
@Component({
  selector: 'oci-alert-detail',
  imports: [FormField, AlertWhy, EngineText, EvidenceLine, MatButtonModule, MatProgressBar, MatTabsModule, ...MAT_FORM, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alert-detail.html',
  styleUrl: './alert-detail.scss',
  host: { role: 'region', 'aria-labelledby': 'pane-title' },
})
export class AlertDetail {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);
  readonly alert = input.required<Alert>();
  /** Rule name in the console's language. */
  readonly title = computed(() => ruleName(this.alert(), this.i18n.lang()));
  /** The finding of the alert with its level of proof (spec 2026-11-18). */
  readonly lines = computed(() => alertLines(this.alert(), this.i18n.lang()));
  readonly updated = output<Alert>();
  readonly closed = output<void>();
  readonly busy = signal(false);
  readonly actionError = signal<ApiError | null>(null);
  /** Optional reason recorded with the next status change; cleared when another alert opens. */
  readonly reason = linkedSignal({ source: () => this.alert().id, computation: () => '' });
  readonly assigning = linkedSignal({ source: () => this.alert().id, computation: () => false });
  readonly tab = linkedSignal({ source: () => this.alert().id, computation: () => 0 });
  readonly ago = (iso: string) => timeAgo(iso, Date.now(), this.i18n.lang());
  readonly statName = (st: string) => statusName(st, this.i18n.lang());
  readonly stamp = (iso: string) => fmtStamp(iso, this.i18n.lang());
  readonly errText = (e: ApiError) => errorText(e, this.i18n.lang());
  /** True in French, where engine-composed sentences stay English and are marked as such. */
  readonly fr = computed(() => this.i18n.lang() === 'fr');
  /** The assignee field's message, from the kind of the error (the server-side wording is never involved). */
  readonly assignError = computed(() => (this.assignForm.assignee().errors().length ? this.i18n.t('alerts.assigneeInvalid') : ''));

  /** Signal Forms: the assignee follows the alert; the comment draft resets when another alert opens. */
  readonly assignModel = linkedSignal(() => ({ assignee: this.alert().assignee ?? '' }));
  readonly assignForm = form(this.assignModel, (p) => email(p.assignee));
  readonly commentModel = linkedSignal({ source: () => this.alert().id, computation: () => ({ body: '' }) });
  readonly commentForm = form(this.commentModel, (p) => required(p.body));

  actions(s: AlertStatus): AlertAction[] {
    return ACTIONS[s];
  }

  action(a: string): string {
    const k = `alerts.hist.${a}`;
    return hasKey(k) ? this.i18n.t(k) : this.statName(a);
  }

  evidenceHelp(a: Alert): string {
    return this.i18n.t(`alerts.help.${a.evidenceLevel}`);
  }

  private async mutate(call: Observable<Alert>, done: string): Promise<boolean> {
    this.busy.set(true);
    this.actionError.set(null);
    try {
      this.updated.emit(await firstValueFrom(call));
      this.toasts.success(done);
      return true;
    } catch (e) {
      const err = toApiError(e);
      this.actionError.set(err);
      this.toasts.error(errorText(err, this.i18n.lang()));
      return false;
    } finally {
      this.busy.set(false);
    }
  }

  async move(to: AlertStatus, done: Key): Promise<void> {
    if (this.busy()) return;
    const a = this.alert();
    const reason = this.reason().trim() || undefined;
    if (await this.mutate(to === 'ACKNOWLEDGED' && !reason ? this.api.acknowledgeAlert(a.id) : this.api.setAlertStatus(a.id, to, reason), this.i18n.t(done))) this.reason.set('');
  }

  assign(e: Event): void {
    e.preventDefault();
    void submit(this.assignForm, async () => {
      const who = this.assignModel().assignee.trim() || null;
      if (await this.mutate(this.api.assignAlert(this.alert().id, who), who ? this.i18n.t('alerts.toastAssigned', { who }) : this.i18n.t('alerts.toastUnassigned'))) this.assigning.set(false);
    });
  }

  comment(e: Event): void {
    e.preventDefault();
    void submit(this.commentForm, async () => {
      if (await this.mutate(this.api.commentAlert(this.alert().id, this.commentModel().body.trim()), this.i18n.t('alerts.toastComment'))) this.commentModel.set({ body: '' });
    });
  }
}
