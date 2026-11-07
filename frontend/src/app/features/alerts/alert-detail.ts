import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, Signal, inject, linkedSignal, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatProgressBar } from '@angular/material/progress-bar';
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

/** Lifecycle actions available from each status (mirrors the backend state machine); `done` is the toast text. */
const ACTIONS: Record<AlertStatus, { to: AlertStatus; label: string; done: string; primary?: boolean }[]> = {
  OPEN: [{ to: 'ACKNOWLEDGED', label: 'Acknowledge alert', done: 'Alert acknowledged', primary: true }, { to: 'INVESTIGATING', label: 'Start investigating', done: 'Investigation started' }, { to: 'RESOLVED', label: 'Resolve alert', done: 'Alert resolved' }, { to: 'FALSE_POSITIVE', label: 'Mark as false positive', done: 'Marked as false positive' }],
  ACKNOWLEDGED: [{ to: 'INVESTIGATING', label: 'Start investigating', done: 'Investigation started', primary: true }, { to: 'RESOLVED', label: 'Resolve alert', done: 'Alert resolved' }, { to: 'FALSE_POSITIVE', label: 'Mark as false positive', done: 'Marked as false positive' }],
  INVESTIGATING: [{ to: 'RESOLVED', label: 'Resolve alert', done: 'Alert resolved', primary: true }, { to: 'FALSE_POSITIVE', label: 'Mark as false positive', done: 'Marked as false positive' }],
  RESOLVED: [{ to: 'OPEN', label: 'Reopen alert', done: 'Alert reopened' }],
  FALSE_POSITIVE: [{ to: 'OPEN', label: 'Reopen alert', done: 'Alert reopened' }],
};

export interface AlertDetailData {
  /** The open alert; follows list and detail reloads while the dialog is open. */
  alert: Signal<Alert | null>;
  updated: (a: Alert) => void;
}

/**
 * Alert dialog content (opened with MatDialog by the alerts page): evidence on the left, triage (lifecycle, assignee,
 * history, comments) on the right. The dialog provides role="dialog", aria-modal, the focus trap, Escape and focus return.
 */
@Component({
  selector: 'oci-alert-detail',
  imports: [DatePipe, FormField, AlertWhy, MatDialogModule, MatButtonModule, MatProgressBar, ...MAT_FORM, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alert-detail.html',
  styleUrl: './alert-detail.scss',
})
export class AlertDetail {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  private readonly data = inject<AlertDetailData>(MAT_DIALOG_DATA);
  /** Keeps the last alert while the dialog closes (the selection is cleared first). */
  readonly alert = linkedSignal<Alert | null, Alert>({ source: this.data.alert, computation: (a, prev) => a ?? prev!.value });
  readonly busy = signal(false);
  readonly actionError = signal<ApiError | null>(null);
  readonly reason = signal('');
  readonly ago = timeAgo;
  readonly sentence = sentence;

  /** Signal Forms: the assignee follows the alert; the comment draft resets when another alert opens. */
  readonly assignModel = linkedSignal(() => ({ assignee: this.alert().assignee ?? '' }));
  readonly assignForm = form(this.assignModel, (p) => email(p.assignee, { message: 'Enter an email address, or leave empty to unassign.' }));
  readonly commentModel = linkedSignal({ source: () => this.alert().id, computation: () => ({ body: '' }) });
  readonly commentForm = form(this.commentModel, (p) => required(p.body, { message: 'Write a comment first.' }));

  actions(s: AlertStatus) {
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
      this.data.updated(await firstValueFrom(call));
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
    const a = this.alert();
    const reason = this.reason().trim() || undefined;
    if (await this.mutate(to === 'ACKNOWLEDGED' && !reason ? this.api.acknowledgeAlert(a.id) : this.api.setAlertStatus(a.id, to, reason), done)) this.reason.set('');
  }

  assign(e: Event): void {
    e.preventDefault();
    void submit(this.assignForm, async () => {
      const who = this.assignModel().assignee.trim() || null;
      await this.mutate(this.api.assignAlert(this.alert().id, who), who ? `Alert assigned to ${who}` : 'Alert unassigned');
    });
  }

  comment(e: Event): void {
    e.preventDefault();
    void submit(this.commentForm, async () => {
      if (await this.mutate(this.api.commentAlert(this.alert().id, this.commentModel().body.trim()), 'Comment posted')) this.commentModel.set({ body: '' });
    });
  }
}
