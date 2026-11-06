import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ALERT_STATUSES, Alert, Dashboard, RiskLevel, SEVERITIES } from '../../core/models';
import { shortHash, timeAgo } from '../../shared/format';
import { query } from '../../shared/query';
import { RISK_THRESHOLDS, levelLabel } from '../../shared/risk-gauge';
import { UI, sentence } from '../../shared/ui';

const DIST_ORDER: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN'];

@Component({
  selector: 'oci-overview',
  imports: [RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Overview</h1>
          <p>Ingestion health, open alerts and the latest detection signals. A signal is a reason to look closer, not proof of wrongdoing.</p>
        </div>
        <div class="row">
          @if (dash.data(); as d) { <span class="small muted">Updated {{ ago(d.generatedAt) }}</span> }
          <button class="btn" type="button" (click)="load()" [disabled]="dash.loading()">Refresh</button>
        </div>
      </div>

      <oci-state [loading]="dash.loading()" [error]="dash.error()" [hasData]="!!dash.data()" loadingText="Loading the dashboard…" (retry)="dash.retry()">
        @if (dash.data(); as d) {
          <p class="totals">
            <span class="num">{{ n(d.totals.walletsTracked) }}</span> wallets tracked, <span class="num">{{ n(d.totals.eventsIndexed) }}</span> events indexed,
            <span class="num">{{ d.totals.rulesEnabled }}</span> of <span class="num">{{ d.totals.rules }}</span> rules enabled@if (d.totals.signals24h !== null) {, <span class="num">{{ d.totals.signals24h }}</span> signals in the last 24 hours}.
          </p>

          <section class="section" aria-labelledby="h-dist">
            <div class="section-head">
              <h2 id="h-dist">Risk distribution of tracked wallets</h2>
              <p>Bands follow the engine thresholds {{ thr.MEDIUM }}, {{ thr.HIGH }} and {{ thr.CRITICAL }}.</p>
            </div>
            @if (distTotal() > 0) {
              <div class="dist" role="img" [attr.aria-label]="distAria()">
                @for (b of dist(); track b.level) {
                  @if (b.count > 0) { <span class="dist-seg d-{{ b.level.toLowerCase() }}" [style.flex-grow]="b.count" [attr.title]="b.label + ': ' + b.count"></span> }
                }
              </div>
              <table class="dist-table">
                <thead><tr>@for (b of dist(); track b.level) { <th scope="col" class="right"><span class="sw d-{{ b.level.toLowerCase() }}" aria-hidden="true"></span>{{ b.label }}</th> }</tr></thead>
                <tbody><tr>@for (b of dist(); track b.level) { <td class="right"><span class="big">{{ n(b.count) }}</span><span class="pct muted">{{ b.pct }}%</span></td> }</tr></tbody>
              </table>
            } @else {
              <p class="muted">No wallet has been scored yet. Look up an address in the Explorer to score it.</p>
            }
          </section>

          <div class="cols">
            <section class="section" aria-labelledby="h-recent">
              <div class="section-head"><h2 id="h-recent">Recent alerts</h2><a class="small" routerLink="/alerts">View all alerts</a></div>
              @if (d.alerts; as al) {
                @if (al.recent.length) {
                  <table class="recent">
                    <thead><tr><th scope="col">Severity</th><th scope="col">Alert</th><th scope="col">Subject wallet score</th><th scope="col" class="right">Created</th></tr></thead>
                    <tbody>
                      @for (a of al.recent; track a.id) {
                        <tr>
                          <td><oci-severity [value]="a.severity" /></td>
                          <td><a [routerLink]="['/alerts']" [queryParams]="{ id: a.id }">{{ a.ruleName }}</a>
                            <div class="sub"><span class="status st-{{ a.status.toLowerCase() }}">{{ sentence(a.status) }}</span>@if (a.address) { <span class="mono">{{ short(a.address) }}</span> }</div></td>
                          <td class="g">
                            @if (a.address) {
                              @if (scores()[a.id]; as s) { <oci-gauge [score]="s.score" [level]="s.level" [label]="'Risk score of ' + short(a.address)" /> }
                              @else if (scores()[a.id] === null) { <span class="small muted">Not available</span> }
                              @else { <span class="small muted">Loading…</span> }
                            } @else { <span class="small muted">No wallet</span> }
                          </td>
                          <td class="right small nowrap">{{ ago(a.createdAt) }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                } @else { <p class="muted">No alert yet. Enable a rule in the Rule builder to start monitoring.</p> }
              } @else { <p class="muted">Alerts are hidden: your role has no alerts:read permission.</p> }
            </section>

            <section class="section" aria-labelledby="h-sev">
              <div class="section-head"><h2 id="h-sev">Alerts by severity and status</h2></div>
              @if (d.alerts; as al) {
                <table class="compact">
                  <thead><tr><th scope="col">Severity</th><th scope="col" class="right">Open</th><th scope="col" class="right">All</th></tr></thead>
                  <tbody>
                    @for (s of severities; track s) {
                      <tr>
                        <th scope="row"><a [routerLink]="['/alerts']" [queryParams]="{ severity: s, status: 'OPEN' }" [attr.aria-label]="'Open ' + sentence(s).toLowerCase() + ' alerts'"><oci-severity [value]="s" /></a></th>
                        <td class="right strong">{{ al.openBySeverity[s] ?? 0 }}</td><td class="right muted">{{ al.bySeverity[s] ?? 0 }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
                <table class="compact mt">
                  <thead><tr><th scope="col">Status</th><th scope="col" class="right">Alerts</th></tr></thead>
                  <tbody>
                    @for (s of statuses; track s) {
                      <tr><th scope="row"><a class="status st-{{ s.toLowerCase() }}" [routerLink]="['/alerts']" [queryParams]="{ status: s }">{{ sentence(s) }}</a></th><td class="right">{{ al.byStatus[s] ?? 0 }}</td></tr>
                    }
                  </tbody>
                </table>
              } @else { <p class="muted">Alerts are hidden: your role has no alerts:read permission.</p> }
            </section>
          </div>

          <section class="section" aria-labelledby="h-net">
            <div class="section-head"><h2 id="h-net">Networks</h2><p>Head is the latest block seen by the provider; indexed is the latest block processed here.</p></div>
            <div class="table-wrap"><table>
              <thead><tr>
                <th scope="col">Network</th><th scope="col">Health</th><th scope="col" class="right">Head block</th><th scope="col" class="right">Indexed block</th>
                <th scope="col" class="right">Lag (blocks)</th><th scope="col" class="right">Latency</th><th scope="col">Provider</th><th scope="col" class="right">Checked</th>
              </tr></thead>
              <tbody>
                @for (x of d.networks; track x.network) {
                  <tr>
                    <td><span class="cap">{{ x.network }}</span>@if (x.simulated) { <span class="chip sim" title="Data comes from the simulated demo ledger, not the real chain">Simulated</span> }</td>
                    <td><span class="health" [class.bad]="!x.healthy"><span class="dot" [class.dot--bad]="!x.healthy" aria-hidden="true"></span>{{ x.healthy ? 'Healthy' : 'Degraded' }}</span>
                      @if (x.detail) { <div class="small warn">{{ x.detail }}</div> }</td>
                    <td class="right">{{ n(x.headBlock) }}</td>
                    <td class="right">{{ x.lastIndexedBlock !== undefined ? n(x.lastIndexedBlock) : '–' }}</td>
                    <td class="right" [class.warn]="(x.indexerLag ?? 0) > 200">{{ x.indexerLag !== undefined ? n(x.indexerLag) : n(x.headBlock - x.finalizedBlock) }}</td>
                    <td class="right" [class.warn]="x.latencyMillis > 1000">{{ n(x.latencyMillis) }} ms</td>
                    <td class="small">{{ x.providerId }}</td>
                    <td class="right small nowrap">{{ ago(x.checkedAt) }}</td>
                  </tr>
                }
              </tbody>
            </table></div>
          </section>

          <div class="cols cols--wide-right">
            <section class="section" aria-labelledby="h-act">
              <div class="section-head"><h2 id="h-act">Activity, last 24 hours</h2></div>
              @if (d.activity.length) {
                <svg class="act" viewBox="0 0 240 64" preserveAspectRatio="none" role="img" [attr.aria-label]="actAria()">
                  @for (b of d.activity; track b.start; let i = $index) {
                    <rect [attr.x]="i * 10 + 1" width="8" [attr.y]="64 - (b.events / actMax()) * 60" [attr.height]="(b.events / actMax()) * 60" class="ev"><title>{{ b.start.slice(11, 16) }} UTC: {{ b.events }} events, {{ b.alerts ?? 0 }} alerts</title></rect>
                    @if (b.alerts) { <rect [attr.x]="i * 10 + 1" width="8" y="0" height="3" class="al" /> }
                  }
                </svg>
                <div class="axis small muted"><span>24 h ago</span><span>now</span></div>
                <p class="small muted">Bars: events indexed per hour. Marks on top: hours in which at least one alert fired.</p>
              } @else { <p class="muted">No activity recorded in the last 24 hours.</p> }
            </section>

            <section class="section" aria-labelledby="h-sig">
              <div class="section-head"><h2 id="h-sig">Recent signals</h2></div>
              @if ((d.recentSignals ?? []).length) {
                <div class="table-wrap"><table>
                  <thead><tr><th scope="col">Signal</th><th scope="col">Evidence</th><th scope="col">Address</th><th scope="col" class="right">Detected</th></tr></thead>
                  <tbody>
                    @for (s of (d.recentSignals ?? []).slice(0, 8); track s.id) {
                      <tr>
                        <td><span class="strong">{{ sentence(s.type) }}</span><div class="small muted">{{ s.evidence[0]?.description }}</div></td>
                        <td><oci-evidence [value]="s.evidenceLevel" /><div class="small muted">Confidence <span class="num">{{ (s.confidence * 100).toFixed(0) }}</span>%</div></td>
                        <td><oci-addr [address]="s.address" [network]="s.network" /></td>
                        <td class="right nowrap small">{{ ago(s.detectedAt) }}</td>
                      </tr>
                    }
                  </tbody>
                </table></div>
              } @else { <p class="muted">No signal detected yet, or your role has no risk:read permission.</p> }
            </section>
          </div>
        }
      </oci-state>
    </div>
  `,
  styles: `
    .totals { color: var(--ink-2); margin: -8px 0 0; }
    .totals .num { color: var(--ink); font-weight: 600; }
    .cols { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(300px, 1fr); gap: 32px; align-items: start; }
    @media (max-width: 1100px) { .cols { grid-template-columns: minmax(0, 1fr); gap: 24px; } }
    .dist { display: flex; height: 20px; gap: 0; margin: 4px 0 12px; }
    .dist-seg { min-width: 3px; }
    .dist-seg + .dist-seg { border-left: 2px solid var(--bg); }
    .d-low { background: var(--risk-low); } .d-medium { background: var(--risk-medium); } .d-high { background: var(--risk-high); }
    .d-critical { background: var(--risk-critical); } .d-unknown { background: var(--risk-unknown); }
    .dist-table { width: auto; }
    .dist-table th, .dist-table td { padding: 4px 28px 4px 0; border: 0; }
    .dist-table th { color: var(--ink-2); font-size: 13px; font-weight: 500; }
    .sw { display: inline-block; width: 10px; height: 10px; margin-right: 6px; vertical-align: -1px; }
    .big { font-size: 20px; font-weight: 600; }
    .pct { margin-left: 6px; font-size: 13px; }
    .cols--wide-right { grid-template-columns: minmax(280px, 1fr) minmax(0, 1.5fr); }
    .sub { display: flex; gap: 10px; align-items: center; margin-top: 2px; font-size: 12px; color: var(--muted); }
    .sub .status { font-size: 12px; color: var(--ink-2); }
    .recent td.g { width: 200px; }
    .recent oci-gauge { width: 180px; }
    .compact { width: 100%; }
    .compact th[scope='row'] { font-weight: 400; color: var(--ink); font-size: 13px; }
    .mt { margin-top: 16px; }
    .strong { font-weight: 600; }
    .cap { text-transform: capitalize; font-weight: 500; }
    .sim { margin-left: 8px; }
    .health { display: inline-flex; gap: 6px; align-items: center; }
    .health.bad, .warn { color: var(--warn); }
    .act { width: 100%; height: 96px; display: block; }
    .ev { fill: var(--ink-2); opacity: 0.55; }
    .al { fill: var(--risk-high); }
    .axis { display: flex; justify-content: space-between; margin: 4px 0 8px; }
  `,
})
export class OverviewPage {
  private readonly api = inject(ApiService);
  /** One call: GET /dashboard. Recent alerts additionally show their subject wallet's score (GET /wallets/{a}/risk each). */
  readonly dash = query<Dashboard>();
  readonly ago = timeAgo;
  readonly sentence = sentence;
  readonly short = (a: string) => shortHash(a, 8, 6);
  readonly n = (v: number) => v.toLocaleString('en-US');
  readonly thr = RISK_THRESHOLDS;
  readonly severities = SEVERITIES;
  readonly statuses = ALERT_STATUSES;
  /** alert id -> score; null = could not be scored. */
  readonly scores = signal<Record<string, { score: number; level: RiskLevel } | null>>({});

  readonly dist = computed(() => {
    const r = this.dash.data()?.riskDistribution;
    const total = DIST_ORDER.reduce((s, l) => s + (r?.[l] ?? 0), 0) || 1;
    return DIST_ORDER.map((level) => ({ level, label: levelLabel(level), count: r?.[level] ?? 0, pct: Math.round(((r?.[level] ?? 0) / total) * 100) }));
  });
  readonly distTotal = computed(() => this.dist().reduce((s, b) => s + b.count, 0));
  readonly distAria = computed(() => 'Risk distribution: ' + this.dist().map((b) => `${b.label} ${b.count}`).join(', '));
  readonly actMax = computed(() => Math.max(1, ...(this.dash.data()?.activity ?? []).map((a) => a.events)));
  readonly actAria = computed(() => {
    const a = this.dash.data()?.activity ?? [];
    return `Events per hour over the last 24 hours, peak ${this.actMax()}, ${a.reduce((s, x) => s + (x.alerts ?? 0), 0)} alerts in total`;
  });

  constructor() {
    this.load();
    effect(() => {
      const recent = this.dash.data()?.alerts?.recent ?? [];
      untracked(() => this.loadScores(recent));
    });
  }

  load(): void {
    this.dash.run(() => this.api.dashboard());
  }

  private loadScores(alerts: Alert[]): void {
    const withAddr = alerts.filter((a) => a.address);
    if (!withAddr.length) return;
    forkJoin(
      withAddr.map((a) =>
        this.api.walletRisk(a.address!, a.network).pipe(
          map((r) => [a.id, { score: r.score, level: r.level }] as const),
          catchError(() => of([a.id, null] as const)),
        ),
      ),
    ).subscribe((pairs) => this.scores.set(Object.fromEntries(pairs)));
  }
}
