import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { ALERT_STATUSES, Alert, AlertStatus, SEVERITIES, Severity } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { timeAgo } from '../../shared/format';
import { PageHeader } from '../../shared/page-header';
import { TableState } from '../../shared/table-state';
import { UI, sentence } from '../../shared/ui';
import { AlertDetail } from './alert-detail';

const SEV_RANK: Record<Severity, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, INFO: 0 };
const ACTIVE: AlertStatus[] = ['OPEN', 'ACKNOWLEDGED', 'INVESTIGATING'];

@Component({
  selector: 'oci-alerts',
  imports: [FormsModule, PageHeader, AlertDetail, ...UI],
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

  private returnFocus: HTMLElement | null = null;

  open(a: Alert): void {
    this.returnFocus = document.activeElement as HTMLElement | null;
    this.selectedId.set(a.id);
  }

  close(): void {
    this.selectedId.set(null);
    this.returnFocus?.focus?.();
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
