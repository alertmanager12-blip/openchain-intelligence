import { SlicePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { ChainTransaction, Counterparty, Exposure, NetworkId, WalletAnalysis } from '../../core/models';
import { formatAge, formatUnits, shortHash, timeAgo } from '../../shared/format';
import { TableState, query } from '../../shared/query';
import { UI, sentence } from '../../shared/ui';

type Tab = 'tx' | 'cp' | 'exp' | 'sig';

@Component({
  selector: 'oci-wallet',
  imports: [FormsModule, RouterLink, SlicePipe, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <header class="w-head">
        <div class="ident">
          <div class="row kind"><oci-net [value]="network()" />
            @if (w.data(); as a) { <span class="small muted">{{ a.isContract ? 'Smart contract' : 'Externally owned account' }}</span> }</div>
          <div class="addr-row"><h1 class="mono addr">{{ address() }}</h1><oci-copy [value]="address()" label="Copy address" /></div>
          @if (w.data(); as a) {
            @if (a.labels.length) {
              <ul class="labels" aria-label="Labels">
                @for (l of a.labels; track l.id) {
                  <li><span class="chip">{{ l.category }}</span> <span>{{ l.name }}</span> <oci-evidence [value]="l.evidenceLevel" />
                    <span class="small muted">Source {{ l.source }}, reliability <span class="num">{{ pct(l.reliability) }}</span>%</span></li>
                }
              </ul>
            } @else { <p class="small muted">No label. The absence of a label says nothing about the owner.</p> }
          }
          <div class="row actions">
            <a class="btn btn--primary" [routerLink]="['/investigation']" [queryParams]="{ address: address(), network: network() }">Trace fund flows</a>
            <a class="btn" [routerLink]="['/risk', network(), address()]">Open risk breakdown</a>
          </div>
        </div>
        <div class="score">
          @if (w.data(); as a) {
            @if (a.risk; as r) {
              <oci-gauge size="lg" [score]="r.score" [level]="r.level" label="Wallet risk score" />
              <p class="small muted">Method {{ r.method }}, model {{ r.modelVersion }}, computed {{ ago(r.computedAt) }}.</p>
            } @else {
              <oci-gauge size="lg" [score]="null" label="Wallet risk score" />
              <p class="small muted">Not enough data to score this wallet yet.</p>
            }
          }
        </div>
      </header>

      <oci-state [loading]="w.loading()" [error]="w.error()" [hasData]="!!w.data()" (retry)="w.retry()" loadingText="Analysing the wallet…">
        @if (w.data(); as a) {
          @if (a.risk; as r) {
            <section class="section" aria-labelledby="h-sheet">
              <div class="section-head"><h2 id="h-sheet">How the score is built</h2>
                <p>Each row adds or removes points, and the bars stack from 0.@if (rawTotal() !== r.score) { The contributions add up to {{ rawTotal() }}; the score is capped at {{ r.score }} (red line). } @else { They end at the score. }</p></div>
              <div class="table-wrap"><table class="sheet">
                <thead><tr>
                  <th scope="col">Factor</th><th scope="col">Source</th><th scope="col">Evidence</th>
                  <th scope="col" class="right">Confidence</th><th scope="col" class="right">Age</th><th scope="col" class="right">Points</th>
                  <th scope="col" class="bar-col"><span class="sr-only">Running total of points</span><span aria-hidden="true" class="scale-head"><span>0</span><span>{{ sheet().domain }}</span></span></th>
                </tr></thead>
                <tbody>
                  @for (c of sheet().rows; track c.factor) {
                    <tr>
                      <td><span class="strong">{{ sentence(c.factor) }}</span><div class="small muted expl">{{ c.explanation }}</div></td>
                      <td class="small nowrap">{{ c.source }}</td>
                      <td><oci-evidence [value]="c.evidenceLevel" /></td>
                      <td class="right">{{ pct(c.confidence) }}%</td>
                      <td class="right nowrap">{{ age(c.ageSeconds) }}</td>
                      <td class="right strong" [class.neg]="c.points < 0">{{ c.points > 0 ? '+' : '' }}{{ c.points }}</td>
                      <td class="bar-col"><span class="track" aria-hidden="true">@for (t of sheet().ticks; track $index) { <span class="thr" [style.left.%]="t"></span> }@if (sheet().cap; as cap) { <span class="cap" [style.left.%]="cap"></span> }
                        <span class="piece" [class.neg]="c.points < 0" [style.left.%]="c.left" [style.width.%]="c.width"></span></span></td>
                    </tr>
                  } @empty { <tr><td colspan="7" class="muted">No contribution: nothing notable was observed on this wallet.</td></tr> }
                </tbody>
                <tfoot><tr><td colspan="5">Score</td><td class="right">{{ r.score }}</td><td class="bar-col"><oci-gauge [score]="r.score" [level]="r.level" label="Total score" /></td></tr></tfoot>
              </table></div>
            </section>
          }

          <section class="section profile" aria-label="Profile">
            <div>
              <h2>Profile</h2>
              <dl class="kv">
                <dt>First seen</dt><dd>{{ a.firstSeen ? (a.firstSeen | slice: 0 : 10) : '–' }}</dd>
                <dt>Last active</dt><dd>{{ ago(a.lastSeen) }}</dd>
                <dt>Transactions</dt><dd class="num-l">{{ a.txCount.toLocaleString('en-US') }}</dd>
                <dt>Signals</dt><dd class="num-l">{{ a.signals.length }}</dd>
              </dl>
            </div>
            <div>
              <h2>Balances</h2>
              <table class="mini"><tbody>@for (b of a.balances; track b.token.symbol) {
                <tr><th scope="row">{{ b.token.symbol }}</th><td class="right">{{ units(b.rawAmount, b.token.decimals) }}</td></tr>
              }</tbody></table>
              @if (a.balances[0]; as b0) { <p class="small muted mt">At block {{ b0.atBlock.toLocaleString('en-US') }}</p> }
            </div>
            <div>
              <h2>Lifetime flows</h2>
              <table class="mini">
                <thead><tr><th scope="col">Token</th><th scope="col" class="right">In</th><th scope="col" class="right">Out</th></tr></thead>
                <tbody>@for (f of a.flows; track f.token.symbol) {
                  <tr><th scope="row">{{ f.token.symbol }}</th><td class="right">{{ units(f.rawIn, f.token.decimals, 2) }}</td><td class="right">{{ units(f.rawOut, f.token.decimals, 2) }}</td></tr>
                }</tbody>
              </table>
            </div>
          </section>

          <div class="charts">
            <section class="section" aria-labelledby="h-act">
              <h2 id="h-act">Activity, last 30 days</h2>
              <svg class="chart" viewBox="0 0 300 80" preserveAspectRatio="none" role="img" [attr.aria-label]="'Daily transactions, peak ' + actMax() + ' per day'">
                @for (d of a.activity; track d.day; let i = $index) {
                  <rect [attr.x]="i * 10 + 1" [attr.width]="4" [attr.y]="80 - (d.txIn / actMax()) * 76" [attr.height]="(d.txIn / actMax()) * 76" class="in-bar"><title>{{ d.day }}: {{ d.txIn }} in</title></rect>
                  <rect [attr.x]="i * 10 + 5" [attr.width]="4" [attr.y]="80 - (d.txOut / actMax()) * 76" [attr.height]="(d.txOut / actMax()) * 76" class="out-bar"><title>{{ d.day }}: {{ d.txOut }} out</title></rect>
                }
              </svg>
              <div class="row small legend"><span class="lg in-bg"></span>Incoming <span class="lg out-bg"></span>Outgoing</div>
            </section>
            <section class="section" aria-labelledby="h-rh">
              <h2 id="h-rh">Score history</h2>
              @if (a.risk?.history?.length) {
                <svg class="chart" viewBox="0 0 300 80" preserveAspectRatio="none" role="img" aria-label="Risk score over time, with the 35, 65 and 85 thresholds">
                  @for (t of [35, 65, 85]; track t) { <line x1="0" x2="300" [attr.y1]="80 - t * 0.8" [attr.y2]="80 - t * 0.8" class="thr-line" /> }
                  <polyline [attr.points]="riskLine()" class="risk-line" />
                </svg>
                <p class="small muted">Dotted lines: thresholds 35, 65 and 85. Scores move as evidence ages or new evidence arrives.</p>
              } @else { <p class="muted">No history yet.</p> }
            </section>
          </div>

          <section class="tabs-wrap" aria-label="Wallet records">
            <div class="tabs" role="tablist" aria-label="Wallet records" (keydown)="tabKey($event)">
              @for (t of tabs; track t.id) {
                <button type="button" role="tab" [id]="'tab-' + t.id" [attr.aria-controls]="'panel-' + t.id" [attr.aria-selected]="tab() === t.id" [attr.tabindex]="tab() === t.id ? 0 : -1" (click)="tab.set(t.id)">
                  {{ t.label }}<span class="count">{{ tabCount(t.id, a) }}</span></button>
              }
            </div>

            <div role="tabpanel" id="panel-tx" aria-labelledby="tab-tx" [hidden]="tab() !== 'tx'" tabindex="0">
              <oci-state [loading]="txLoading()" [error]="txError()" [hasData]="txs().length > 0" [empty]="!txLoading() && txs().length === 0" emptyText="No transaction indexed for this wallet yet." (retry)="loadTx()">
                <div class="table-wrap"><table>
                  <thead><tr><th scope="col">Hash</th><th scope="col">When</th><th scope="col">Direction</th><th scope="col">Counterparty</th><th scope="col" class="right">Value</th><th scope="col">Status</th></tr></thead>
                  <tbody>@for (t of txs(); track t.hash) {
                    <tr>
                      <td><a class="mono" [routerLink]="['/explorer']" [queryParams]="{ q: t.hash, network: t.network }" [attr.title]="t.hash">{{ short(t.hash) }}</a></td>
                      <td class="nowrap small">{{ ago(t.timestamp) }}</td>
                      <td>{{ t.from === address() ? 'Out' : 'In' }}</td>
                      <td>@let other = t.from === address() ? t.to : t.from; @if (other) { <oci-addr [address]="other" [network]="t.network" /> }</td>
                      <td class="right nowrap">{{ units(t.value, t.nativeDecimals ?? 18, 4) }} <span class="muted">{{ t.nativeSymbol }}</span></td>
                      <td>@if (t.success) { Confirmed } @else { <span class="warn">Failed</span> }</td>
                    </tr>
                  }</tbody>
                </table></div>
                @if (cursor()) { <div class="more"><button class="btn" type="button" (click)="loadTx()" [disabled]="txLoading()">Load more transactions</button></div> }
              </oci-state>
            </div>

            <div role="tabpanel" id="panel-cp" aria-labelledby="tab-cp" [hidden]="tab() !== 'cp'" tabindex="0">
              <div class="toolbar">
                <label>Filter<input type="search" [ngModel]="cp.filter()" (ngModelChange)="cp.filter.set($event)" placeholder="Address, label or token" /></label>
              </div>
              <div class="table-wrap"><table>
                <thead><tr>
                  <th scope="col" [attr.aria-sort]="cp.aria('address')"><button type="button" (click)="cp.sort('address')">Address {{ cp.icon('address') }}</button></th>
                  <th scope="col">Direction</th>
                  <th scope="col" class="right" [attr.aria-sort]="cp.aria('txCount')"><button type="button" (click)="cp.sort('txCount')">Transactions {{ cp.icon('txCount') }}</button></th>
                  <th scope="col" class="right" [attr.aria-sort]="cp.aria('in')"><button type="button" (click)="cp.sort('in')">In {{ cp.icon('in') }}</button></th>
                  <th scope="col" class="right" [attr.aria-sort]="cp.aria('out')"><button type="button" (click)="cp.sort('out')">Out {{ cp.icon('out') }}</button></th>
                  <th scope="col" [attr.aria-sort]="cp.aria('risk')"><button type="button" (click)="cp.sort('risk')">Risk {{ cp.icon('risk') }}</button></th>
                </tr></thead>
                <tbody>
                  @for (c of cp.view(); track c.address + c.token.symbol) {
                    <tr>
                      <td><oci-addr [address]="c.address" [network]="network()" />@if (c.label) { <div class="small muted">{{ c.label }}</div> }</td>
                      <td>{{ sentence(c.direction) }}</td><td class="right">{{ c.txCount }}</td>
                      <td class="right nowrap">{{ units(c.rawIn, c.token.decimals, 2) }} <span class="muted">{{ c.token.symbol }}</span></td>
                      <td class="right nowrap">{{ units(c.rawOut, c.token.decimals, 2) }} <span class="muted">{{ c.token.symbol }}</span></td>
                      <td><oci-risk [value]="c.riskLevel" /></td>
                    </tr>
                  } @empty { <tr><td colspan="6" class="muted">No counterparty matches the filter. Clear it to see all.</td></tr> }
                </tbody>
              </table></div>
            </div>

            <div role="tabpanel" id="panel-exp" aria-labelledby="tab-exp" [hidden]="tab() !== 'exp'" tabindex="0">
              <oci-state [loading]="exp.loading()" [error]="exp.error()" [hasData]="!!exp.data()" [empty]="expRows().length === 0" emptyText="No exposure to labelled categories was found." (retry)="exp.retry()">
                <p class="small muted">Share of volume connected to labelled categories. Indirect exposure (2 hops or more) is weaker evidence than direct exposure.</p>
                <div class="table-wrap"><table>
                  <thead><tr><th scope="col">Category</th><th scope="col" class="right">Hops</th><th scope="col" class="right">Share of volume</th><th scope="col">Via</th><th scope="col">Evidence</th></tr></thead>
                  <tbody>@for (x of expRows(); track $index) {
                    <tr><td>{{ x.category }}</td><td class="right">{{ x.hops }}</td><td class="right">{{ pct(x.share) }}%</td><td><oci-addr [address]="x.counterparty" [network]="network()" /></td><td><oci-evidence [value]="x.evidenceLevel" /></td></tr>
                  }</tbody>
                </table></div>
              </oci-state>
            </div>

            <div role="tabpanel" id="panel-sig" aria-labelledby="tab-sig" [hidden]="tab() !== 'sig'" tabindex="0">
              @if (a.signals.length) {
                <div class="table-wrap"><table>
                  <thead><tr><th scope="col">Signal</th><th scope="col">Severity</th><th scope="col">Evidence</th><th scope="col">Detector</th><th scope="col" class="right">Detected</th></tr></thead>
                  <tbody>@for (s of a.signals; track s.id) {
                    <tr><td><span class="strong">{{ sentence(s.type) }}</span><div class="small muted">{{ s.evidence[0]?.description }}</div></td>
                      <td><oci-severity [value]="s.severity" /></td><td><oci-evidence [value]="s.evidenceLevel" /></td>
                      <td class="small mono">{{ s.detectorId }}</td><td class="right small nowrap">{{ ago(s.detectedAt) }}</td></tr>
                  }</tbody>
                </table></div>
              } @else { <p class="muted">No signal on this wallet.</p> }
            </div>
          </section>
        }
      </oci-state>
    </div>
  `,
  styles: `
    .w-head { display: grid; grid-template-columns: minmax(0, 1fr) minmax(300px, 440px); gap: 24px 48px; align-items: start; padding-bottom: 8px; }
    @media (max-width: 1000px) { .w-head { grid-template-columns: minmax(0, 1fr); } }
    .ident { display: grid; gap: 10px; min-width: 0; }
    .addr-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; min-width: 0; }
    .addr { font-size: 18px; font-weight: 500; letter-spacing: 0; min-width: 0; }
    .labels { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
    .labels li { display: flex; flex-wrap: wrap; gap: 6px 8px; align-items: center; }
    .actions { margin-top: 4px; }
    .score { display: grid; gap: 8px; padding-top: 4px; }
    .strong { font-weight: 600; }
    .expl { max-width: 52ch; }
    .neg { color: var(--ok); }
    .sheet { min-width: 760px; }
    .sheet .bar-col { width: 30%; min-width: 180px; vertical-align: middle; }
    .scale-head { display: flex; justify-content: space-between; }
    .track { position: relative; display: block; height: 8px; background: var(--line-soft); }
    .thr { position: absolute; top: 0; bottom: 0; width: 1px; background: var(--muted); opacity: 0.5; }
    .cap { position: absolute; top: -3px; bottom: -3px; width: 2px; margin-left: -1px; background: var(--risk-critical); z-index: 1; }
    .piece { position: absolute; top: 0; bottom: 0; background: var(--ink-2); min-width: 2px; }
    .piece.neg { background: var(--risk-low); }
    tfoot oci-gauge { width: 100%; }
    .profile { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr)); gap: 24px 40px; }
    .num-l { font-variant-numeric: tabular-nums; }
    .mini th[scope='row'] { font-weight: 500; color: var(--ink); font-size: 13px; }
    .mt { margin-top: 8px; }
    .charts { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(340px, 100%), 1fr)); gap: 24px 40px; }
    .chart { width: 100%; height: 110px; display: block; }
    .in-bar { fill: var(--ink-2); } .out-bar { fill: var(--accent); }
    .legend { margin-top: 6px; color: var(--ink-2); }
    .lg { display: inline-block; width: 10px; height: 10px; } .in-bg { background: var(--ink-2); } .out-bg { background: var(--accent); }
    .risk-line { fill: none; stroke: var(--ink); stroke-width: 1.5; vector-effect: non-scaling-stroke; }
    .thr-line { stroke: var(--line); stroke-dasharray: 2 3; vector-effect: non-scaling-stroke; }
    .tabs-wrap { min-width: 0; }
    .warn { color: var(--warn); }
    .more { padding: 12px 0; }
    @media (max-width: 720px) { .addr { font-size: 15px; } }
    /* Narrow screens: each contribution becomes a block (factor + points first, then evidence, details, bar)
       instead of a 7-column table that hid the points behind a horizontal scroll. */
    @media (max-width: 640px) {
      .sheet thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
      .sheet tbody tr, .sheet tfoot tr {
        display: grid; grid-template-columns: minmax(0, 1fr) auto auto auto; column-gap: 12px; row-gap: 6px;
        grid-template-areas: 'f f f p' 'e e e e' 's c a a' 'b b b b'; padding: 12px 0; border-bottom: 1px solid var(--line);
      }
      .sheet td { display: block; padding: 0; border: 0; text-align: left; }
      .sheet tbody td:nth-child(1) { grid-area: f; }
      .sheet tbody td:nth-child(2) { grid-area: s; }
      .sheet tbody td:nth-child(3) { grid-area: e; }
      .sheet tbody td:nth-child(4) { grid-area: c; }
      .sheet tbody td:nth-child(5) { grid-area: a; }
      .sheet tbody td:nth-child(6) { grid-area: p; text-align: right; }
      .sheet tbody td:nth-child(7) { grid-area: b; }
      .sheet tbody td:nth-child(4)::before { content: 'Confidence '; color: var(--muted); }
      .sheet tbody td:nth-child(5)::before { content: 'Age '; color: var(--muted); }
      .sheet tbody td[colspan] { grid-column: 1 / -1; }
      .sheet tfoot tr { grid-template-areas: 'l p' 'b b'; grid-template-columns: minmax(0, 1fr) auto; }
      .sheet tfoot td:nth-child(1) { grid-area: l; }
      .sheet tfoot td:nth-child(2) { grid-area: p; text-align: right; }
      .sheet tfoot td:nth-child(3) { grid-area: b; }
      .sheet .bar-col { width: auto; min-width: 0; }
      .sheet, .sheet tbody, .sheet tfoot { display: block; min-width: 0; width: 100%; }
    }
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
  readonly age = formatAge;
  readonly sentence = sentence;
  readonly pct = (v: number) => (v * 100).toFixed(0);

  readonly tabs: { id: Tab; label: string }[] = [
    { id: 'tx', label: 'Transactions' }, { id: 'cp', label: 'Counterparties' }, { id: 'exp', label: 'Exposure' }, { id: 'sig', label: 'Signals' },
  ];
  readonly tab = signal<Tab>('tx');
  readonly expRows = computed(() => {
    const e = this.exp.data();
    return e ? [...e.direct, ...e.indirect] : [];
  });

  /** Total before clamping, to explain when the displayed score differs from the plain sum. */
  readonly rawTotal = computed(() => (this.w.data()?.risk?.contributions ?? []).reduce((s, c) => s + c.points, 0));
  /**
   * Measurement sheet: positive contributions first (largest first), then reductions. Each row's bar
   * starts where the previous one ended, so the bars together end exactly at the score.
   */
  readonly sheet = computed(() => {
    const cs = [...(this.w.data()?.risk?.contributions ?? [])].sort((a, b) => (a.points >= 0) === (b.points >= 0) ? Math.abs(b.points) - Math.abs(a.points) : a.points >= 0 ? -1 : 1);
    let run = 0;
    const rows = cs.map((c) => {
      const from = Math.max(0, run);
      run += c.points;
      const to = Math.max(0, run);
      return { ...c, from, to };
    });
    // The track spans 0..100, or 0..running maximum when contributions add up past the 100 cap.
    const domain = Math.max(100, ...rows.map((r) => Math.max(r.from, r.to)));
    const at = (v: number) => (v / domain) * 100;
    return {
      domain,
      ticks: [35, 65, 85, 100].map(at),
      cap: domain > 100 ? at(100) : null,
      rows: rows.map((r) => ({ ...r, left: at(Math.min(r.from, r.to)), width: at(Math.abs(r.to - r.from)) })),
    };
  });

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

  tabCount(t: Tab, a: WalletAnalysis): string {
    switch (t) {
      case 'tx': return a.txCount.toLocaleString('en-US');
      case 'cp': return String(a.counterparties.length);
      case 'exp': return this.exp.data() ? String(this.expRows().length) : '';
      default: return String(a.signals.length);
    }
  }

  /** Arrow keys move between tabs (WAI-ARIA tabs pattern, automatic activation). */
  tabKey(e: KeyboardEvent): void {
    const ids = this.tabs.map((t) => t.id);
    const i = ids.indexOf(this.tab());
    const next = e.key === 'ArrowRight' ? (i + 1) % ids.length : e.key === 'ArrowLeft' ? (i - 1 + ids.length) % ids.length : e.key === 'Home' ? 0 : e.key === 'End' ? ids.length - 1 : -1;
    if (next < 0) return;
    e.preventDefault();
    this.tab.set(ids[next]);
    (document.getElementById('tab-' + ids[next]) as HTMLElement | null)?.focus();
  }

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
