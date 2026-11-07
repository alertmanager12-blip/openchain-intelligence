import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, linkedSignal, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBar } from '@angular/material/progress-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { FormField, email, form, required, submit } from '@angular/forms/signals';
import { Observable, firstValueFrom } from 'rxjs';
import { ApiError, toApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { Alert, AlertStatus } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { timeAgo } from '../../shared/format';
import { MAT_FORM } from '../../shared/material';
import { UI, sentence } from '../../shared/ui';
import { AlertWhy } from './alert-why';

export interface AlertAction { to: AlertStatus; label: string; done: string; primary?: boolean; key?: string }

/** Lifecycle actions available from each status (mirrors the backend state machine); `done` is the toast text. */
export const ACTIONS: Record<AlertStatus, AlertAction[]> = {
  OPEN: [{ to: 'ACKNOWLEDGED', label: 'Acknowledge', done: 'Alert acknowledged', primary: true, key: 'E' }, { to: 'INVESTIGATING', label: 'Investigate', done: 'Investigation started' }, { to: 'RESOLVED', label: 'Resolve', done: 'Alert resolved' }, { to: 'FALSE_POSITIVE', label: 'False positive', done: 'Marked as false positive' }],
  ACKNOWLEDGED: [{ to: 'INVESTIGATING', label: 'Investigate', done: 'Investigation started', primary: true }, { to: 'RESOLVED', label: 'Resolve', done: 'Alert resolved' }, { to: 'FALSE_POSITIVE', label: 'False positive', done: 'Marked as false positive' }],
  INVESTIGATING: [{ to: 'RESOLVED', label: 'Resolve', done: 'Alert resolved', primary: true }, { to: 'FALSE_POSITIVE', label: 'False positive', done: 'Marked as false positive' }],
  RESOLVED: [{ to: 'OPEN', label: 'Reopen', done: 'Alert reopened' }],
  FALSE_POSITIVE: [{ to: 'OPEN', label: 'Reopen', done: 'Alert reopened' }],
};

/**
 * The alert pane of the inbox: header with the lifecycle actions, then Overview (why it fired) and Activity
 * (history and comments). A labelled region, not a dialog: the list stays usable next to it.
 */
@Component({
  selector: 'oci-alert-detail',
  imports: [DatePipe, FormField, AlertWhy, MatButtonModule, MatProgressBar, MatTabsModule, ...MAT_FORM, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alert-detail.html',
  styleUrl: './alert-detail.scss',
  host: { role: 'region', 'aria-labelledby': 'pane-title' },
})
export class AlertDetail {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  readonly alert = input.required<Alert>();
  readonly updated = output<Alert>();
  readonly closed = output<void>();
  readonly busy = signal(false);
  readonly actionError = signal<ApiError | null>(null);
  /** Optional reason recorded with the next status change; cleared when another alert opens. */
  readonly reason = linkedSignal({ source: () => this.alert().id, computation: () => '' });
  readonly assigning = linkedSignal({ source: () => this.alert().id, computation: () => false });
  readonly tab = linkedSignal({ source: () => this.alert().id, computation: () => 0 });
  readonly ago = timeAgo;
  readonly sentence = sentence;

  /** Signal Forms: the assignee follows the alert; the comment draft resets when another alert opens. */
  readonly assignModel = linkedSignal(() => ({ assignee: this.alert().assignee ?? '' }));
  readonly assignForm = form(this.assignModel, (p) => email(p.assignee, { message: 'Enter an email address, or leave empty to unassign.' }));
  readonly commentModel = linkedSignal({ source: () => this.alert().id, computation: () => ({ body: '' }) });
  readonly commentForm = form(this.commentModel, (p) => required(p.body, { message: 'Write a comment first.' }));

  actions(s: AlertStatus): AlertAction[] {
    return ACTIONS[s];
  }

  action(a: string): string {
    return a === 'CREATED' ? 'Created' : a === 'ANNOTATED' ? 'Annotated' : sentence(a);
  }

  evidenceHelp(a: Alert): string {
    return a.evidenceLevel === 'ANOMALY' ? 'Unusual behaviour, no risk claim.' : a.evidenceLevel === 'RISK_SIGNAL' ? 'Pattern associated with risk, not proof.' : 'Stated by a cited external source.';
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
      this.toasts.error(err.title, err.detail ?? undefined);
      return false;
    } finally {
      this.busy.set(false);
    }
  }

  async move(to: AlertStatus, done: string): Promise<void> {
    if (this.busy()) return;
    const a = this.alert();
    const reason = this.reason().trim() || undefined;
    if (await this.mutate(to === 'ACKNOWLEDGED' && !reason ? this.api.acknowledgeAlert(a.id) : this.api.setAlertStatus(a.id, to, reason), done)) this.reason.set('');
  }

  assign(e: Event): void {
    e.preventDefault();
    void submit(this.assignForm, async () => {
      const who = this.assignModel().assignee.trim() || null;
      if (await this.mutate(this.api.assignAlert(this.alert().id, who), who ? `Alert assigned to ${who}` : 'Alert unassigned')) this.assigning.set(false);
    });
  }

  comment(e: Event): void {
    e.preventDefault();
    void submit(this.commentForm, async () => {
      if (await this.mutate(this.api.commentAlert(this.alert().id, this.commentModel().body.trim()), 'Comment posted')) this.commentModel.set({ body: '' });
    });
  }
}
