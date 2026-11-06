import { SlicePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { ChainTransaction, Counterparty, Exposure, NetworkId, WalletAnalysis } from '../../core/models';
import { formatUnits, shortHash, timeAgo } from '../../shared/format';
import { TableState, query } from '../../shared/query';
import { UI } from '../../shared/ui';

@Component({
  selector: 'oci-wallet',
  imports: [FormsModule, RouterLink, SlicePipe, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <div class="row"><oci-net [value]="network()" /><span class="muted small">Wallet intelligence</span></div>
          <h1 class="mono addr">{{ address() }}</h1>
        </div>
        <div class="row">
          <a class="btn" [routerLink]="['/risk', network(), address()]">Risk breakdown</a>
          <a class="btn btn--primary" [routerLink]="['/investigation']" [queryParams]="{ address: address(), network: network() }">Investigate graph</a>
        </div>
      </div>

      <oci-state [loading]="w.loading()" [error]="w.error()" [hasData]="!!w.data()" (retry)="w.retry()" loadingText="Analysing wallet…">
        @if (w.data(); as a) {
          <div class="grid grid-3">
            <section class="card" aria-labelledby="h-prof">
              <h2 id="h-prof">Profile</h2>
              <dl class="kv">
                <dt>Type</dt><dd>{{ a.isContract ? 'Smart contract' : 'Externally owned account' }}</dd>
                <dt>First seen</dt><dd>{{ a.firstSeen ? (a.firstSeen | slice: 0 : 10) : '—' }}</dd>
                <dt>Last active</dt><dd>{{ ago(a.lastSeen) }}</dd>
                <dt>Transactions</dt><dd>{{ a.txCount.toLocaleString('en-US') }}</dd>
                <dt>Risk</dt><dd>@if (a.risk) { <oci-risk [value]="a.risk.level" [score]="a.risk.score" /> } @else { <span class="muted">not scored</span> }</dd>
              </dl>
              <h3 class="mt">Labels</h3>
              @for (l of a.labels; track l.id) {
                <p><span class="chip">{{ l.category }}</span> {{ l.name }} <oci-evidence [value]="l.evidenceLevel" /><br /><span class="small muted">source {{ l.source }} · reliability {{ (l.reliability * 100).toFixed(0) }}%</span></p>
              } @empty { <p class="muted small">No label. Absence of a label says nothing about the owner.</p> }
            </section>

            <section class="card" aria-labelledby="h-bal">
              <h2 id="h-bal">Balances</h2>
              <table>
                <tbody>@for (b of a.balances; track b.token.symbol) {
                  <tr><td>{{ b.token.symbol }}</td><td class="right mono">{{ units(b.rawAmount, b.token.decimals) }}</td></tr>
                }</tbody>
              </table>
              <p class="small muted mt">at block {{ a.balances[0]?.atBlock }}</p>
            </section>

            <section class="card" aria-labelledby="h-flow">
              <h2 id="h-flow">Flows (lifetime)</h2>
              <table>
                <thead><tr><th scope="col">Token</th><th scope="col" class="right">In</th><th scope="col" class="right">Out</th></tr></thead>
                <tbody>@for (f of a.flows; track f.token.symbol) {
                  <tr><td>{{ f.token.symbol }}</td><td class="right mono in">{{ units(f.rawIn, f.token.decimals, 2) }}</td><td class="right mono out">{{ units(f.rawOut, f.token.decimals, 2) }}</td></tr>
                }</tbody>
              </table>
            </section>
          </div>

          <div class="grid grid-2">
            <section class="card" aria-labelledby="h-act">
              <h2 id="h-act">Activity (30 days)</h2>
              <svg class="chart" viewBox="0 0 300 80" preserveAspectRatio="none" role="img" [attr.aria-label]="'Daily transactions, peak ' + actMax() + ' per day'">
                @for (d of a.activity; track d.day; let i = $index) {
                  <rect [attr.x]="i * 10 + 1" [attr.width]="4" [attr.y]="80 - (d.txIn / actMax()) * 76" [attr.height]="(d.txIn / actMax()) * 76" class="in-bar"><title>{{ d.day }}: {{ d.txIn }} in</title></rect>
                  <rect [attr.x]="i * 10 + 5" [attr.width]="4" [attr.y]="80 - (d.txOut / actMax()) * 76" [attr.height]="(d.txOut / actMax()) * 76" class="out-bar"><title>{{ d.day }}: {{ d.txOut }} out</title></rect>
                }
              </svg>
              <div class="row small"><span class="lg in-bg"></span>incoming <span class="lg out-bg"></span>outgoing</div>
            </section>
            <section class="card" aria-labelledby="h-rh">
              <h2 id="h-rh">Risk history</h2>
              @if (a.risk?.history?.length) {
                <svg class="chart" viewBox="0 0 300 80" preserveAspectRatio="none" role="img" aria-label="Risk score over time">
                  <line x1="0" x2="300" [attr.y1]="80 - 0.65 * 80" [attr.y2]="80 - 0.65 * 80" class="thr" />
                  <polyline [attr.points]="riskLine()" class="risk-line" />
                </svg>
                <p class="small muted">Dashed line: HIGH threshold (65). Scores move as evidence ages or new evidence arrives.</p>
              } @else { <p class="muted">No history yet.</p> }
            </section>
          </div>

          <section class="card" aria-labelledby="h-sig">
            <h2 id="h-sig">Signals on this wallet</h2>
            @for (s of a.signals; track s.id) {
              <p><oci-severity [value]="s.severity" /> <oci-evidence [value]="s.evidenceLevel" /> <strong>{{ s.type }}</strong> · {{ s.evidence[0]?.description }} <span class="small muted">({{ s.detectorId }}, {{ ago(s.detectedAt) }})</span></p>
            } @empty { <p class="muted">No signal.</p> }
          </section>

          <section class="card card--flush" aria-labelledby="h-cp">
            <h2 id="h-cp">Counterparties</h2>
            <div class="toolbar">
              <label>Filter<input type="search" [ngModel]="cp.filter()" (ngModelChange)="cp.filter.set($event)" placeholder="address, label, token" /></label>
            </div>
            <div class="table-wrap"><table>
              <thead><tr>
                <th scope="col" [attr.aria-sort]="cp.aria('address')"><button type="button" (click)="cp.sort('address')">Address {{ cp.icon('address') }}</button></th>
                <th scope="col">Direction</th>
                <th scope="col" [attr.aria-sort]="cp.aria('txCount')"><button type="button" (click)="cp.sort('txCount')">Txs {{ cp.icon('txCount') }}</button></th>
                <th scope="col" class="right" [attr.aria-sort]="cp.aria('in')"><button type="button" (click)="cp.sort('in')">In {{ cp.icon('in') }}</button></th>
                <th scope="col" class="right" [attr.aria-sort]="cp.aria('out')"><button type="button" (click)="cp.sort('out')">Out {{ cp.icon('out') }}</button></th>
                <th scope="col" [attr.aria-sort]="cp.aria('risk')"><button type="button" (click)="cp.sort('risk')">Risk {{ cp.icon('risk') }}</button></th>
              </tr></thead>
              <tbody>
                @for (c of cp.view(); track c.address + c.token.symbol) {
                  <tr>
                    <td><oci-addr [address]="c.address" [network]="network()" />@if (c.label) { <div class="small muted">{{ c.label }}</div> }</td>
                    <td>{{ c.direction }}</td><td>{{ c.txCount }}</td>
                    <td class="right mono">{{ units(c.rawIn, c.token.decimals, 2) }} {{ c.token.symbol }}</td>
                    <td class="right mono">{{ units(c.rawOut, c.token.decimals, 2) }} {{ c.token.symbol }}</td>
                    <td><oci-risk [value]="c.riskLevel" /></td>
                  </tr>
                } @empty { <tr><td colspan="6" class="muted">No counterparty matches the filter.</td></tr> }
              </tbody>
            </table></div>
          </section>

          @if (exp.data(); as e) {
            <section class="card" aria-labelledby="h-exp">
              <h2 id="h-exp">Exposure</h2>
              <p class="small muted">Share of volume connected to labelled categories. Indirect exposure (2+ hops) is weaker evidence than direct exposure.</p>
              <div class="table-wrap"><table>
                <thead><tr><th scope="col">Category</th><th scope="col">Hops</th><th scope="col" class="right">Share</th><th scope="col">Via</th><th scope="col">Evidence</th></tr></thead>
                <tbody>@for (x of e.direct.concat(e.indirect); track $index) {
                  <tr><td>{{ x.category }}</td><td>{{ x.hops }}</td><td class="right">{{ (x.share * 100).toFixed(0) }}%</td><td><oci-addr [address]="x.counterparty" [network]="network()" /></td><td><oci-evidence [value]="x.evidenceLevel" /></td></tr>
                }</tbody>
              </table></div>
            </section>
          }

          <section class="card card--flush" aria-labelledby="h-tx">
            <h2 id="h-tx">Transactions</h2>
            <oci-state [loading]="txLoading()" [error]="txError()" [hasData]="txs().length > 0" [empty]="!txLoading() && txs().length === 0" (retry)="loadTx()">
              <div class="table-wrap"><table>
                <thead><tr><th scope="col">Hash</th><th scope="col">When</th><th scope="col">Direction</th><th scope="col">Counterparty</th><th scope="col" class="right">Value</th><th scope="col">Status</th></tr></thead>
                <tbody>@for (t of txs(); track t.hash) {
                  <tr>
                    <td><a class="mono" [routerLink]="['/explorer']" [queryParams]="{ q: t.hash, network: t.network }" [attr.title]="t.hash">{{ short(t.hash) }}</a></td>
                    <td class="nowrap small">{{ ago(t.timestamp) }}</td>
                    <td>{{ t.from === address() ? 'OUT' : 'IN' }}</td>
                    <td>@let other = t.from === address() ? t.to : t.from; @if (other) { <oci-addr [address]="other" [network]="t.network" /> }</td>
                    <td class="right mono">{{ units(t.value, t.nativeDecimals ?? 18, 4) }} {{ t.nativeSymbol }}</td>
                    <td>{{ t.success ? 'ok' : 'failed' }}</td>
                  </tr>
                }</tbody>
              </table></div>
              @if (cursor()) { <div class="state"><button class="btn" type="button" (click)="loadTx()" [disabled]="txLoading()">Load more</button></div> }
            </oci-state>
          </section>
        }
      </oci-state>
    </div>
  `,
  styles: `
    .addr { font-size: 16px; margin-top: 4px; }
    .mt { margin-top: 12px; }
    .in { color: var(--ok); } .out { color: var(--danger); }
    .chart { width: 100%; height: 120px; }
    .in-bar { fill: var(--ok); } .out-bar { fill: var(--accent); }
    .lg { display: inline-block; width: 10px; height: 10px; border-radius: 2px; } .in-bg { background: var(--ok); } .out-bg { background: var(--accent); }
    .risk-line { fill: none; stroke: var(--sev-high); stroke-width: 2; vector-effect: non-scaling-stroke; }
    .thr { stroke: var(--muted); stroke-dasharray: 4 4; vector-effect: non-scaling-stroke; }
  `,
})
export class WalletPage {
  private readonly api = inject(ApiService);
  readonly network = input.required<NetworkId>();
  readonly address = input.required<string>();

  readonly w = query<WalletAnalysis>();
  readonly exp = query<Exposure>();
  readonly txs = signal<ChainTransaction[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly txLoading = signal(false);
  readonly txError = signal<ApiError | null>(null);
  readonly ago = timeAgo;
  readonly units = formatUnits;
  readonly short = (h: string) => shortHash(h, 10, 6);

  private readonly riskOrder = { UNKNOWN: 0, LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 };
  readonly cp = new TableState<Counterparty>(
    computed(() => this.w.data()?.counterparties ?? []),
    {
      address: (c) => c.address, txCount: (c) => c.txCount, in: (c) => BigInt(c.rawIn), out: (c) => BigInt(c.rawOut),
      risk: (c) => this.riskOrder[c.riskLevel],
    },
    (c) => `${c.address} ${c.label ?? ''} ${c.token.symbol}`,
    { key: 'txCount', dir: 'desc' },
  );
  readonly actMax = computed(() => Math.max(1, ...(this.w.data()?.activity ?? []).flatMap((d) => [d.txIn, d.txOut])));
  readonly riskLine = computed(() => {
    const h = this.w.data()?.risk?.history ?? [];
    return h.map((p, i) => `${(i / Math.max(1, h.length - 1)) * 300},${80 - (p.score / 100) * 80}`).join(' ');
  });

  constructor() {
    effect(() => {
      const a = this.address();
      const n = this.network();
      this.w.run(() => this.api.walletAnalysis(a, n), false);
      this.exp.run(() => this.api.walletExposure(a, n), false);
      untracked(() => {
        this.txs.set([]);
        this.cursor.set(null);
        this.loadTx();
      });
    });
  }

  loadTx(): void {
    this.txLoading.set(true);
    this.txError.set(null);
    this.api.walletTransactions(this.address(), this.network(), this.cursor()).subscribe({
      next: (p) => {
        this.txs.update((t) => [...t, ...p.items]);
        this.cursor.set(p.nextCursor);
        this.txLoading.set(false);
      },
      error: (e: ApiError) => {
        this.txError.set(e);
        this.txLoading.set(false);
      },
    });
  }
}
