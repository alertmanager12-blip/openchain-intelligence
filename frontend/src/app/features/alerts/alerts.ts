import { ChangeDetectionStrategy, Component, DestroyRef, Injector, computed, effect, inject, input, linkedSignal, untracked } from '@angular/core';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { ApiService } from '../../core/api.service';
import { ALERT_STATUSES, Alert, AlertStatus, SEVERITIES, Severity } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { timeAgo } from '../../shared/format';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { TableState } from '../../shared/table-state';
import { UI, sentence } from '../../shared/ui';
import { AlertDetail, AlertDetailData } from './alert-detail';

const SEV_RANK: Record<Severity, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, INFO: 0 };
const ACTIVE: AlertStatus[] = ['OPEN', 'ACKNOWLEDGED', 'INVESTIGATING'];

@Component({
  selector: 'oci-alerts',
  imports: [PageHeader, MatChipsModule, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alerts.html',
  styleUrl: './alerts.scss',
})
export class AlertsPage {
  private readonly api = inject(ApiService);
  /** Query params (deep links from Overview and the command palette). */
  readonly statusParam = input<AlertStatus | ''>('', { alias: 'status' });
  readonly severityParam = input<Severity | ''>('', { alias: 'severity' });
  readonly idParam = input<string | undefined>(undefined, { alias: 'id' });

  readonly statuses = ALERT_STATUSES;
  readonly severities = SEVERITIES;
  readonly active = ACTIVE;
  /** Filters start from the URL and stay editable. */
  readonly status = linkedSignal<AlertStatus | ''>(() => this.statusParam() ?? '');
  readonly severity = linkedSignal<Severity | ''>(() => this.severityParam() ?? '');
  readonly list = apiResource({
    params: () => ({ status: this.status(), severity: this.severity(), limit: 200 }),
    stream: (q) => this.api.alerts(q),
    keepPrevious: true,
  });
  /** Counts for the readout, independent of the filters. */
  readonly summary = apiResource({ params: () => ({}), stream: () => this.api.dashboard() });

  /** The open alert: from a deep link or a row click; fetched in full (comments, history). */
  readonly selectedId = linkedSignal<string | null>(() => this.idParam() ?? null);
  readonly detail = apiResource({ params: () => this.selectedId() ?? undefined, stream: (id) => this.api.alert(id) });
  readonly selected = computed<Alert | null>(() => {
    const id = this.selectedId();
    if (!id) return null;
    const d = this.detail.value();
    return d?.id === id ? d : (this.list.value()?.items.find((a) => a.id === id) ?? null);
  });

  readonly ago = timeAgo;
  readonly sentence = sentence;
  readonly t = new TableState<Alert>(
    computed(() => this.list.value()?.items ?? []),
    { severity: (a) => SEV_RANK[a.severity], status: (a) => ALERT_STATUSES.indexOf(a.status), assignee: (a) => a.assignee, created: (a) => a.createdAt },
    (a) => `${a.id} ${a.ruleName} ${a.ruleId} ${a.address ?? ''} ${a.txHash ?? ''} ${a.assignee ?? ''}`,
    { key: 'created', dir: 'desc' },
  );
  readonly counts = computed(() => this.summary.value()?.alerts ?? null);
  readonly cols = ['severity', 'alert', 'status', 'evidence', 'assignee', 'created'];
  readonly byId = (_: number, a: Alert) => a.id;

  /** The alert dialog (MatDialog): open while an alert is selected; focus returns to the row on close. */
  private readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);
  private ref: MatDialogRef<AlertDetail> | null = null;
  private destroyed = false;

  constructor() {
    effect(() => {
      const open = !!this.selected();
      untracked(() => {
        if (open && !this.ref) {
          const data: AlertDetailData = { alert: this.selected, updated: (a) => this.onUpdated(a) };
          this.ref = this.dialog.open(AlertDetail, {
            data, injector: this.injector, panelClass: 'alert-sheet', width: '1120px', maxWidth: 'calc(100vw - 16px)', height: 'calc(100vh - 16px)', position: { top: '8px', right: '8px' },
            ariaLabelledBy: 'drawer-title', autoFocus: 'first-heading', ariaModal: true,
          });
          this.ref.afterClosed().subscribe(() => {
            this.ref = null;
            if (!this.destroyed) this.selectedId.set(null);
          });
        } else if (!open && this.ref) {
          this.ref.close();
        }
      });
    });
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.ref?.close();
    });
  }

  open(a: Alert): void {
    this.selectedId.set(a.id);
  }

  filterStatus(s: AlertStatus | ''): void {
    this.status.set(s);
  }

  onUpdated(a: Alert): void {
    this.detail.set(a);
    this.list.update((p) => ({ items: (p?.items ?? []).map((r) => (r.id === a.id ? a : r)), nextCursor: p?.nextCursor ?? null }));
    this.summary.reload();
  }
}
