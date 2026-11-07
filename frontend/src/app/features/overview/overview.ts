import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ALERT_STATUSES, NetworkId, NetworkStatus, RiskLevel, SEVERITIES } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { shortHash, timeAgo } from '../../shared/format';
import { MAT_BUTTONS, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { RISK_THRESHOLDS, levelLabel } from '../../shared/risk-gauge';
import { UI, sentence } from '../../shared/ui';
import { ActivityChart } from './activity-chart';

const DIST_ORDER: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN'];
const LAG_WARN = 200;

@Component({
  selector: 'oci-overview',
  imports: [RouterLink, PageHeader, ActivityChart, ...MAT_BUTTONS, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class OverviewPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  /** One call: GET /dashboard. */
  readonly dash = apiResource({ params: () => ({}), stream: () => this.api.dashboard() });
  /** Recent alerts also show their subject wallet's score (GET /wallets/{a}/risk each); null = could not be scored. */
  readonly scores = apiResource({
    params: () => {
      const recent = (this.dash.value()?.alerts?.recent ?? []).filter((a) => a.address);
      return recent.length ? recent : undefined;
    },
    stream: (alerts) =>
      forkJoin(alerts.map((a) => this.api.walletRisk(a.address!, a.network).pipe(
        map((r) => [a.id, { score: r.score, level: r.level }] as const),
        catchError(() => of([a.id, null] as const)),
      ))).pipe(map((pairs) => Object.fromEntries(pairs) as Record<string, { score: number; level: RiskLevel } | null>)),
    keepPrevious: true,
  });

  readonly ago = timeAgo;
  readonly sentence = sentence;
  readonly short = (a: string) => shortHash(a, 8, 6);
  readonly n = (v: number) => v.toLocaleString('en-US');
  readonly thr = RISK_THRESHOLDS;
  readonly severities = SEVERITIES;
  readonly statuses = ALERT_STATUSES;
  readonly lagWarn = LAG_WARN;
  readonly openNet = signal<string | null>(null);
    readonly netCols = ['x', 'network', 'health', 'head', 'lag', 'latency', 'checked'];
  readonly signalCols = ['signal', 'evidence', 'address', 'detected'];
  readonly byId = (_: number, r: { id: string }) => r.id;
  readonly byNetwork = (_: number, r: NetworkStatus) => r.network;

  readonly openAlerts = computed(() => this.dash.value()?.totals.openAlerts ?? null);
  readonly openUrgent = computed(() => {
    const o = this.dash.value()?.alerts?.openBySeverity;
    return o ? { critical: o.CRITICAL ?? 0, high: o.HIGH ?? 0 } : null;
  });
  readonly health = computed(() => {
    const nets = this.dash.value()?.networks ?? [];
    const lags = nets.map((x) => this.lag(x));
    return { healthy: nets.filter((x) => x.healthy).length, total: nets.length, maxLag: lags.length ? Math.max(...lags) : 0 };
  });
  readonly dist = computed(() => {
    const r = this.dash.value()?.riskDistribution;
    const total = DIST_ORDER.reduce((s, l) => s + (r?.[l] ?? 0), 0) || 1;
    return DIST_ORDER.map((level) => ({ level, key: level.toLowerCase(), label: levelLabel(level), count: r?.[level] ?? 0, pct: Math.round(((r?.[level] ?? 0) / total) * 100) }));
  });
  readonly distTotal = computed(() => this.dist().reduce((s, b) => s + b.count, 0));
  readonly distAria = computed(() => 'Risk distribution: ' + this.dist().map((b) => `${b.label} ${b.count}`).join(', '));

  lag(x: NetworkStatus): number {
    return x.indexerLag ?? x.headBlock - x.finalizedBlock;
  }

  /** Whole-row navigation (the row also holds a real link for keyboard and assistive tech). */
  openAlert(id: string): void {
    void this.router.navigate(['/alerts'], { queryParams: { id } });
  }

  openWallet(network: NetworkId, address: string): void {
    void this.router.navigate(['/wallets', network, address]);
  }

  toggleNet(id: string): void {
    this.openNet.update((v) => (v === id ? null : id));
  }
}
