import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Alert, NetworkStatus, Page, SEVERITIES, Signal } from '../../core/models';
import { timeAgo } from '../../shared/format';
import { query } from '../../shared/query';
import { UI } from '../../shared/ui';

@Component({
  selector: 'oci-overview',
  imports: [RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Overview</h1>
          <p>Network ingestion health, open alerts and the latest detection signals. A signal is not proof of wrongdoing: it is a reason to look closer.</p>
        </div>
        <button class="btn" type="button" (click)="load()" [disabled]="networks.loading()">Refresh</button>
      </div>

      <section aria-labelledby="h-net">
        <h3 id="h-net">Networks</h3>
        <oci-state [loading]="networks.loading()" [error]="networks.error()" [hasData]="!!networks.data()" (retry)="networks.retry()">
          <div class="grid grid-cards">
            @for (n of networks.data() ?? []; track n.network) {
              <article class="card net">
                <div class="row"><span class="dot" [class.dot--bad]="!n.healthy" aria-hidden="true"></span><strong class="cap">{{ n.network }}</strong>
                  <span class="spacer"></span><span class="small" [class.bad]="!n.healthy">{{ n.healthy ? 'Healthy' : 'Degraded' }}</span></div>
                <div class="kpi mono">{{ n.headBlock.toLocaleString('en-US') }}</div>
                <div class="small muted">head · finalized {{ (n.headBlock - n.finalizedBlock) }} blocks behind · {{ n.latencyMillis }} ms</div>
                <div class="small muted">via {{ n.providerId }} · {{ ago(n.checkedAt) }}</div>
                @if (n.detail) { <p class="small warn">{{ n.detail }}</p> }
              </article>
            }
          </div>
        </oci-state>
      </section>

      <div class="grid grid-2">
        <section class="card" aria-labelledby="h-sev">
          <h2 id="h-sev">Open alerts by severity</h2>
          <oci-state [loading]="alerts.loading()" [error]="alerts.error()" [hasData]="!!alerts.data()" [empty]="openTotal() === 0" emptyText="No open alerts." (retry)="alerts.retry()">
            <ul class="bars">
              @for (s of bySeverity(); track s.severity) {
                <li>
                  <a [routerLink]="['/alerts']" [queryParams]="{ severity: s.severity, status: 'OPEN' }"><oci-severity [value]="s.severity" /></a>
                  <span class="bar" aria-hidden="true"><span class="fill sev-{{ s.severity.toLowerCase() }}" [style.width.%]="s.pct"></span></span>
                  <strong class="count">{{ s.count }}</strong>
                </li>
              }
            </ul>
            <a routerLink="/alerts" class="small">Open the Alert Center →</a>
          </oci-state>
        </section>

        <section class="card card--flush" aria-labelledby="h-sig">
          <h2 id="h-sig">Recent signals</h2>
          <oci-state [loading]="signals.loading()" [error]="signals.error()" [hasData]="!!signals.data()" [empty]="(signals.data()?.items ?? []).length === 0" emptyText="No signals detected yet." (retry)="signals.retry()">
            <div class="table-wrap">
              <table>
                <thead><tr><th scope="col">Signal</th><th scope="col">Evidence</th><th scope="col">Address</th><th scope="col">When</th></tr></thead>
                <tbody>
                  @for (s of (signals.data()?.items ?? []).slice(0, 10); track s.id) {
                    <tr>
                      <td><oci-severity [value]="s.severity" /> {{ s.type }}<div class="small muted">{{ s.evidence[0]?.description }}</div></td>
                      <td><oci-evidence [value]="s.evidenceLevel" /><div class="small muted">conf. {{ (s.confidence * 100).toFixed(0) }}%</div></td>
                      <td><oci-addr [address]="s.address" [network]="s.network" /></td>
                      <td class="nowrap small">{{ ago(s.detectedAt) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </oci-state>
        </section>
      </div>
    </div>
  `,
  styles: `
    .net { display: grid; gap: 4px; }
    .cap { text-transform: capitalize; }
    .bad, .warn { color: var(--warn); }
    .bars { list-style: none; padding: 0; margin: 0 0 12px; display: grid; gap: 10px; }
    .bars li { display: grid; grid-template-columns: 90px 1fr 40px; gap: 10px; align-items: center; }
    .bar { height: 10px; background: var(--surface-2); border-radius: 5px; overflow: hidden; }
    .fill { display: block; height: 100%; border-radius: 5px; background: currentColor; }
    .count { text-align: right; }
  `,
})
export class OverviewPage {
  private readonly api = inject(ApiService);
  readonly networks = query<NetworkStatus[]>();
  readonly alerts = query<Page<Alert>>();
  readonly signals = query<Page<Signal>>();
  readonly ago = timeAgo;

  readonly bySeverity = computed(() => {
    const items = this.alerts.data()?.items ?? [];
    const counts = SEVERITIES.map((severity) => ({ severity, count: items.filter((a) => a.severity === severity).length }));
    const max = Math.max(1, ...counts.map((c) => c.count));
    return counts.map((c) => ({ ...c, pct: (c.count / max) * 100 }));
  });
  readonly openTotal = computed(() => this.bySeverity().reduce((s, c) => s + c.count, 0));

  constructor() {
    this.load();
  }

  load(): void {
    this.networks.run(() => this.api.networks());
    this.alerts.run(() => this.api.alerts({ status: 'OPEN', limit: 500 }));
    this.signals.run(() => this.api.recentSignals(20));
  }
}
