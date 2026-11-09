import { ChangeDetectionStrategy, Component } from '@angular/core';

interface Contribution { readonly title: string; readonly points: number; readonly level: string; readonly age: string }
interface Peer { readonly x: number; readonly y: number; readonly label: string; readonly amount: string; readonly w: number; readonly flagged?: boolean }

/**
 * "One address" dossier, drawn after the console's wallet page with fictional data (labeled). Contribution titles,
 * evidence levels and detectors are the real ones; TRON has no historical balance, so no rapid-drain contribution here.
 * Rendered when scrolled near (@defer on viewport) to keep the first paint light.
 */
@Component({
  selector: 'oci-cs-investigation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    section { background: radial-gradient(ellipse 60% 50% at 50% 0%, rgba(111, 183, 217, 0.06), transparent 70%); }
    .head { display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 24px; }
    .win { margin-top: 48px; border: 1px solid var(--cs-line-2); border-radius: 14px; background: var(--cs-surface); overflow: hidden; box-shadow: 0 30px 80px -40px rgba(0, 0, 0, 0.8); }
    .bar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; padding: 14px 20px; border-bottom: 1px solid var(--cs-line); }
    .net-chip { padding: 2px 8px; border-radius: 5px; background: #2a1416; color: var(--cs-risk); font: 600 12px var(--cs-mono); }
    .addr { font: 500 14px var(--cs-mono); color: var(--cs-text); overflow-wrap: anywhere; }
    .bar .sp { flex: 1; }
    .body { display: grid; grid-template-columns: minmax(250px, 0.95fr) minmax(0, 1.25fr) minmax(240px, 0.9fr); }
    .col { padding: 22px 20px; border-right: 1px solid var(--cs-line); min-width: 0; }
    .col:last-child { border-right: 0; }
    .col h3 { font-size: 13px; font-weight: 600; color: var(--cs-muted); margin-bottom: 12px; }
    .gauge { display: flex; align-items: center; gap: 16px; }
    .gauge svg { width: 112px; height: 64px; flex: none; }
    .score { font-size: 40px; font-weight: 650; letter-spacing: -0.03em; line-height: 1; }
    .score small { font-size: 15px; color: var(--cs-muted); font-weight: 500; }
    .level { margin-top: 4px; color: var(--cs-risk); font-weight: 600; font-size: 14px; }
    .contrib { list-style: none; margin: 18px 0 0; padding: 0; display: grid; gap: 10px; }
    .contrib li { display: grid; grid-template-columns: 1fr auto; gap: 2px 10px; font-size: 14px; }
    .contrib b { font-weight: 500; }
    .contrib .pts { font: 600 13px var(--cs-mono); color: var(--cs-text); }
    .contrib .meta { grid-column: 1 / -1; color: var(--cs-faint); font-size: 12px; }
    .method { margin-top: 16px; color: var(--cs-faint); font-size: 12px; line-height: 1.5; }
    .graph svg { width: 100%; height: auto; display: block; }
    .g-edge { fill: none; stroke: #33404f; }
    .g-edge.hot { stroke: var(--cs-risk); }
    .g-node { fill: var(--cs-raised); stroke: var(--cs-line-2); stroke-width: 1.5; }
    .g-node.hot { fill: #2a1416; stroke: var(--cs-risk); }
    .g-me { fill: #0f2230; stroke: var(--cs-flow); stroke-width: 2; }
    .g-txt { fill: var(--cs-muted); font: 500 11px var(--cs-mono); }
    .g-amt { fill: var(--cs-faint); font: 500 10px var(--cs-font); }
    dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 8px 14px; font-size: 14px; }
    dt { color: var(--cs-muted); }
    dd { margin: 0; text-align: right; font-variant-numeric: tabular-nums; }
    table { width: 100%; border-collapse: collapse; border-spacing: 0; margin-top: 4px; font-size: 13px; }
    table th, table td { background: none; border-bottom: 0; height: auto; position: static; }
    th { text-align: left; font-weight: 500; color: var(--cs-faint); padding: 0 0 6px; }
    td { padding: 7px 0; border-top: 1px solid var(--cs-line); font-variant-numeric: tabular-nums; }
    td.r, th.r { text-align: right; }
    .in { color: var(--cs-accent); } .out { color: var(--cs-warn); }
    .ev { border-top: 1px solid var(--cs-line); display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); }
    .ev div { padding: 16px 20px; border-right: 1px solid var(--cs-line); font-size: 13px; line-height: 1.5; min-width: 0; }
    .ev div:last-child { border-right: 0; }
    .ev b { display: block; font-size: 14px; font-weight: 600; }
    .ev span { color: var(--cs-muted); }
    .ev span { display: block; }
    .ev .ev-meta { display: flex; flex-wrap: wrap; gap: 4px 10px; margin-top: 6px; }
    .ev code { font-family: var(--cs-mono); font-size: 12px; color: var(--cs-flow); }
    .ph { margin-top: 48px; height: 640px; border: 1px solid var(--cs-line); border-radius: 14px; background: var(--cs-surface); }
    @media (max-width: 1080px) {
      .body { grid-template-columns: 1fr 1fr; }
      .graph { grid-column: 1 / -1; order: -1; border-right: 0; border-bottom: 1px solid var(--cs-line); }
    }
    @media (max-width: 700px) {
      .body, .ev { grid-template-columns: 1fr; }
      .col, .ev div { border-right: 0; border-bottom: 1px solid var(--cs-line); }
    }
  `,
  template: `
    <section class="cs-section" id="investigation" aria-labelledby="inv-title">
      <div class="cs-wrap">
        <div class="head">
          <div>
            <h2 class="cs-h2" id="inv-title">One Address.<br />A World of Intelligence.</h2>
            <p class="cs-lede">Paste an address in the console and get its profile, its counterparties, the signals behind its score and the transfers to look at first.</p>
          </div>
          <span class="cs-illus">Illustrative data, not a real wallet</span>
        </div>

        @defer (on viewport) {
          <div class="win" role="figure" aria-label="Illustration of the wallet page for a fictional TRON address with a high risk score">
            <div class="bar">
              <span class="net-chip">TRON</span>
              <span class="addr">TQ7mJ4qP9a…Xw2vR8kN3e</span>
              <span class="sp"></span>
              <span class="cs-status cs-status--planned">On the watchlist</span>
            </div>
            <div class="body">
              <div class="col">
                <h3>Risk</h3>
                <div class="gauge">
                  <svg viewBox="0 0 112 64" aria-hidden="true">
                    <path d="M8 58 A48 48 0 0 1 104 58" fill="none" stroke="#2a3542" stroke-width="8" stroke-linecap="round" />
                    <path d="M8 58 A48 48 0 0 1 104 58" fill="none" stroke="#f0645c" stroke-width="8" stroke-linecap="round" pathLength="100" stroke-dasharray="78 100" />
                  </svg>
                  <div><div class="score">78<small> / 100</small></div><div class="level">High</div></div>
                </div>
                <ul class="contrib">
                  @for (c of contributions; track c.title) {
                    <li><b>{{ c.title }}</b><span class="pts">+{{ c.points }}</span><span class="meta">{{ c.level }}, {{ c.age }}</span></li>
                  }
                </ul>
                <p class="method">Every contribution links to its signal and transactions. Older evidence weighs less (180-day half-life).</p>
              </div>

              <div class="col graph">
                <h3>Counterparties, last 7 days</h3>
                <svg viewBox="0 0 360 260" aria-hidden="true">
                  @for (p of peers; track p.label) { <path class="g-edge" [class.hot]="p.flagged" [attr.d]="'M180 130 L' + p.x + ' ' + p.y" [attr.stroke-width]="p.w" /> }
                  @for (p of peers; track p.label) {
                    <circle class="g-node" [class.hot]="p.flagged" [attr.cx]="p.x" [attr.cy]="p.y" r="10" />
                    <text class="g-txt" [attr.x]="p.x" [attr.y]="p.y + (p.y > 130 ? 26 : -16)" text-anchor="middle">{{ p.label }}</text>
                    <text class="g-amt" [attr.x]="p.x" [attr.y]="p.y + (p.y > 130 ? 39 : -29)" text-anchor="middle">{{ p.amount }}</text>
                  }
                  <circle class="g-me" cx="180" cy="130" r="17" />
                </svg>
              </div>

              <div class="col">
                <h3>Activity</h3>
                <dl>
                  <dt>First seen</dt><dd>12 days ago</dd>
                  <dt>Transactions</dt><dd>1,284</dd>
                  <dt>Counterparties</dt><dd>211</dd>
                  <dt>Tokens</dt><dd>USDT, TRX</dd>
                </dl>
                <h3 style="margin-top: 22px">Transfers to review</h3>
                <table>
                  <thead><tr><th scope="col">When</th><th scope="col" class="r">Amount</th></tr></thead>
                  <tbody>
                    @for (t of transfers; track t.when) {
                      <tr><td>{{ t.when }}</td><td class="r" [class.in]="t.dir === 'in'" [class.out]="t.dir === 'out'">{{ t.dir === 'in' ? '+' : '−' }}{{ t.amount }}</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
            <div class="ev">
              @for (s of signals; track s.title) {
                <div><b>{{ s.title }}</b><span>{{ s.text }}</span><span class="ev-meta"><code>{{ s.detector }}</code><span>{{ s.level }}</span></span></div>
              }
            </div>
          </div>
        } @placeholder {
          <div class="ph" aria-hidden="true"></div>
        }
      </div>
    </section>
  `,
})
export class InvestigationPreview {
  protected readonly contributions: readonly Contribution[] = [
    { title: 'Fast forwarding', points: 29, level: 'Risk signal', age: '2 h ago' },
    { title: 'Exposure to a labeled wallet, 2 hops', points: 22, level: 'Risk signal', age: '1 day ago' },
    { title: 'Structuring', points: 18, level: 'Anomaly', age: '3 days ago' },
    { title: 'Fan-out', points: 9, level: 'Anomaly', age: '5 days ago' },
  ];
  protected readonly peers: readonly Peer[] = [
    { x: 60, y: 52, label: 'TLa9…f2', amount: '410k USDT', w: 4 },
    { x: 300, y: 50, label: 'TPk3…9d', amount: '980k USDT', w: 6, flagged: true },
    { x: 330, y: 150, label: 'TXe1…7a', amount: '120k USDT', w: 2 },
    { x: 262, y: 206, label: 'TNb8…c4', amount: '64k USDT', w: 1.5 },
    { x: 104, y: 202, label: 'TGu2…0b', amount: '210k USDT', w: 3 },
    { x: 34, y: 150, label: 'TRm5…e8', amount: '18k USDT', w: 1.2 },
  ];
  protected readonly transfers = [
    { when: 'Today 14:05', amount: '250,000 USDT', dir: 'in' },
    { when: 'Today 14:06', amount: '240,000 USDT', dir: 'out' },
    { when: 'Yesterday', amount: '9,900 USDT', dir: 'out' },
    { when: 'Yesterday', amount: '9,850 USDT', dir: 'out' },
  ] as const;
  protected readonly signals = [
    { title: 'Fast forwarding', text: '96% of an incoming transfer sent on within 22 s.', detector: 'fast-forwarding', level: 'Risk signal' },
    { title: 'Indirect exposure', text: 'Funds received from a labeled wallet through one intermediary.', detector: 'exposure', level: 'Risk signal' },
    { title: 'Structuring', text: '6 transfers just under 10,000 USDT in 24 h.', detector: 'structuring', level: 'Anomaly' },
  ];
}
