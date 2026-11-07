import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, linkedSignal, untracked, viewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { ALERT_STATUSES, Alert, AlertStatus, SEVERITIES, Severity } from '../../core/models';
import { Shell, isTypingTarget } from '../../layout/shell';
import { apiResource } from '../../shared/api-resource';
import { netLabel, shortHash, timeAgo } from '../../shared/format';
import { MAT_BUTTONS, MAT_FORM } from '../../shared/material';
import { Crumb, PageHeader } from '../../shared/page-header';
import { TableState } from '../../shared/table-state';
import { UI, sentence } from '../../shared/ui';
import { AlertDetail } from './alert-detail';

const SEV_RANK: Record<Severity, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, INFO: 0 };

/**
 * Alerts inbox: a compact list on the left, the selected alert in a pane on the right (full screen on small screens).
 * The selection lives in the URL (/alerts?id=…). Keyboard: J / K (or arrow keys in the list) move, Enter opens,
 * E acknowledges the open alert, Esc closes the pane; "?" lists every shortcut (shell).
 */
@Component({
  selector: 'oci-alerts',
  imports: [PageHeader, AlertDetail, ...MAT_BUTTONS, ...MAT_FORM, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alerts.html',
  styleUrl: './alerts.scss',
  host: { '(document:keydown)': 'key($event)' },
})
export class AlertsPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly shell = inject(Shell);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** Query params (deep links from Overview and the command palette). */
  readonly statusParam = input<AlertStatus | ''>('', { alias: 'status' });
  readonly severityParam = input<Severity | ''>('', { alias: 'severity' });
  readonly idParam = input<string | undefined>(undefined, { alias: 'id' });

  readonly statuses = ALERT_STATUSES;
  readonly severities = SEVERITIES;
  readonly mac = this.shell.mac;
  /** Filters start from the URL and stay editable. */
  readonly status = linkedSignal<AlertStatus | ''>(() => this.statusParam() ?? '');
  readonly severity = linkedSignal<Severity | ''>(() => this.severityParam() ?? '');
  readonly list = apiResource({
    params: () => ({ status: this.status(), severity: this.severity(), limit: 200 }),
    stream: (q) => this.api.alerts(q),
    keepPrevious: true,
  });
  /** Counts per status for the filter bar, independent of the filters. */
  readonly summary = apiResource({ params: () => ({}), stream: () => this.api.dashboard() });
  readonly counts = computed(() => this.summary.value()?.alerts?.byStatus ?? null);

  /** The open alert: from a deep link or a click; fetched in full (comments, history). */
  readonly selectedId = linkedSignal<string | null>(() => this.idParam() ?? null);
  readonly detail = apiResource({ params: () => this.selectedId() ?? undefined, stream: (id) => this.api.alert(id) });
  readonly selected = computed<Alert | null>(() => {
    const id = this.selectedId();
    if (!id) return null;
    const d = this.detail.value();
    return d?.id === id ? d : (this.list.value()?.items.find((a) => a.id === id) ?? null);
  });

  readonly ago = (iso: string) => timeAgo(iso).replace(' ago', '');
  readonly short = (a: string) => shortHash(a, 6, 4);
  readonly sentence = sentence;
  readonly netLabel = netLabel;
  readonly t = new TableState<Alert>(
    computed(() => this.list.value()?.items ?? []),
    { severity: (a) => SEV_RANK[a.severity], created: (a) => a.createdAt },
    (a) => `${a.id} ${a.ruleName} ${a.ruleId} ${a.address ?? ''} ${a.txHash ?? ''} ${a.assignee ?? ''}`,
    { key: 'created', dir: 'desc' },
  );
  /** Highlighted row (keyboard cursor); follows the selection. */
  readonly cursor = linkedSignal<string | null>(() => this.selectedId());
  readonly crumbs = computed<Crumb[]>(() => {
    const id = this.selectedId();
    return id ? [{ label: 'Alerts', link: '/alerts' }, { label: id, mono: true }] : [{ label: 'Alerts' }];
  });

  private readonly pane = viewChild(AlertDetail);

  constructor() {
    // keep the highlighted row visible while moving with the keyboard
    effect(() => {
      const id = this.cursor();
      if (id) untracked(() => queueMicrotask(() => this.host.nativeElement.querySelector(`#opt-${CSS.escape(id)}`)?.scrollIntoView?.({ block: 'nearest' })));
    });
  }

  select(id: string | null): void {
    this.selectedId.set(id);
    if (id) this.cursor.set(id);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { id }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  close(): void {
    const id = this.selectedId();
    this.select(null);
    if (id) queueMicrotask(() => this.host.nativeElement.querySelector<HTMLElement>('.list')?.focus());
  }

  setSort(key: 'created' | 'severity'): void {
    this.t.sortKey.set(key);
    this.t.sortDir.set('desc');
  }

  /** Moves the cursor by one row; with the pane open, the next alert opens too (mail-client behaviour). */
  move(step: 1 | -1): void {
    const rows = this.t.view();
    if (!rows.length) return;
    const i = rows.findIndex((a) => a.id === this.cursor());
    const next = rows[i < 0 ? (step > 0 ? 0 : rows.length - 1) : Math.min(rows.length - 1, Math.max(0, i + step))];
    if (this.selectedId()) this.select(next.id);
    else this.cursor.set(next.id);
  }

  key(e: KeyboardEvent): void {
    if (e.metaKey || e.ctrlKey || e.altKey || e.defaultPrevented || isTypingTarget(e.target) || document.querySelector('mat-dialog-container')) return;
    const inList = (e.target as HTMLElement | null)?.classList?.contains('list');
    switch (e.key) {
      case 'j': case 'J': e.preventDefault(); this.move(1); break;
      case 'k': case 'K': e.preventDefault(); this.move(-1); break;
      case 'ArrowDown': if (inList) { e.preventDefault(); this.move(1); } break;
      case 'ArrowUp': if (inList) { e.preventDefault(); this.move(-1); } break;
      case 'Enter': {
        const target = e.target as HTMLElement | null;
        // Enter on a button or link keeps its own meaning
        if (target && target !== document.body && !inList && /^(BUTTON|A|SUMMARY)$/.test(target.tagName)) return;
        const c = this.cursor();
        if (c) { e.preventDefault(); this.select(c); }
        break;
      }
      case 'e': case 'E': {
        const a = this.selected();
        if (a?.status === 'OPEN') { e.preventDefault(); void this.pane()?.move('ACKNOWLEDGED', 'Alert acknowledged'); }
        break;
      }
      case 'Escape':
        if (this.selectedId()) { e.preventDefault(); this.close(); }
        break;
    }
  }

  onUpdated(a: Alert): void {
    this.detail.set(a);
    this.list.update((p) => ({ items: (p?.items ?? []).map((r) => (r.id === a.id ? a : r)), nextCursor: p?.nextCursor ?? null }));
    this.summary.reload();
    this.shell.refresh();
  }

  refresh(): void {
    this.list.reload();
    this.summary.reload();
  }
}
