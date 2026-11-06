import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { EvidenceLevel, NETWORKS, NetworkId, RiskContribution, RiskScore } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { formatAge, timeAgo } from '../../shared/format';
import { TableState, query } from '../../shared/query';
import { UI } from '../../shared/ui';

const EVIDENCE_ORDER: Record<EvidenceLevel, number> = { ANOMALY: 0, RISK_SIGNAL: 1, CONFIRMED_BY_SOURCE: 2 };

@Component({
  selector: 'oci-risk-page',
  imports: [FormsModule, RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Risk Intelligence</h1>
          <p>Every score is the sum of explained contributions. Each one shows where it comes from, how confident it is and how old the evidence is. A score is an indicator for prioritisation, never a verdict about a person.</p>
        </div>
      </div>

      <form class="card row" (submit)="$event.preventDefault(); open()">
        <label class="grow">Address<input name="a" class="mono" [ngModel]="addrInput()" (ngModelChange)="addrInput.set($event)" placeholder="Address to score" /></label>
        <label>Network<select name="n" [ngModel]="netInput()" (ngModelChange)="netInput.set($event)">
          @for (n of networks; track n) { <option [ngValue]="n">{{ n }}</option> }
        </select></label>
        <button class="btn btn--primary" type="submit" [disabled]="!addrInput().trim()">Score</button>
      </form>

      <div class="legend card">
        <div><oci-evidence value="ANOMALY" /> unusual behaviour, statistically. Not a risk claim.</div>
        <div><oci-evidence value="RISK_SIGNAL" /> pattern associated with risk. Not proof.</div>
        <div><oci-evidence value="CONFIRMED_BY_SOURCE" /> stated by a cited external source (e.g. official list).</div>
      </div>

      @if (address() && network()) {
        <oci-state [loading]="risk.loading()" [error]="risk.error()" [hasData]="!!risk.data()" (retry)="risk.retry()" loadingText="Computing score…">
          @if (risk.data(); as r) {
            <div class="grid top">
              <section class="card gauge-card" aria-labelledby="h-g">
                <h2 id="h-g">Score</h2>
                <svg viewBox="0 0 200 120" class="gauge" role="img" [attr.aria-label]="'Risk score ' + r.score + ' of 100, ' + r.level">
                  <path d="M20 100 A80 80 0 0 1 180 100" class="track" />
                  <path d="M20 100 A80 80 0 0 1 180 100" class="value risk-{{ r.level.toLowerCase() }}" [attr.stroke-dasharray]="arc() + ' 999'" />
                  <text x="100" y="92" text-anchor="middle" class="num">{{ r.score }}</text>
                  <text x="100" y="114" text-anchor="middle" class="lvl">{{ r.level }}</text>
                </svg>
                <p class="small muted">Method {{ r.method }} · model {{ r.modelVersion }} · computed {{ ago(r.computedAt) }}</p>
                <p class="small"><oci-addr [address]="r.address" [network]="r.network" [full]="true" /></p>
                <div class="row"><a class="btn btn--sm" [routerLink]="['/investigation']" [queryParams]="{ address: r.address, network: r.network }">Open in investigation</a></div>
              </section>
              <section class="card card--flush" aria-labelledby="h-c">
                <h2 id="h-c">Contributions</h2>
                <div class="table-wrap"><table>
                  <thead><tr>
                    <th scope="col">Factor</th>
                    <th scope="col" [attr.aria-sort]="t.aria('ev')"><button type="button" (click)="t.sort('ev')">Evidence {{ t.icon('ev') }}</button></th>
                    <th scope="col" class="right" [attr.aria-sort]="t.aria('points')"><button type="button" (click)="t.sort('points')">Points {{ t.icon('points') }}</button></th>
                    <th scope="col" class="right" [attr.aria-sort]="t.aria('weight')"><button type="button" (click)="t.sort('weight')">Weight {{ t.icon('weight') }}</button></th>
                    <th scope="col" class="right" [attr.aria-sort]="t.aria('confidence')"><button type="button" (click)="t.sort('confidence')">Confidence {{ t.icon('confidence') }}</button></th>
                    <th scope="col" [attr.aria-sort]="t.aria('age')"><button type="button" (click)="t.sort('age')">Age {{ t.icon('age') }}</button></th>
                    <th scope="col">Source</th>
                    <th scope="col">Explanation</th>
                  </tr></thead>
                  <tbody>
                    @for (c of t.view(); track c.factor) {
                      <tr>
                        <td class="nowrap"><strong>{{ c.factor }}</strong>@if (c.signalId) { <div class="small muted mono">{{ c.signalId }}</div> }</td>
                        <td><oci-evidence [value]="c.evidenceLevel" /></td>
                        <td class="right mono" [class.neg]="c.points < 0">{{ c.points > 0 ? '+' : '' }}{{ c.points }}</td>
                        <td class="right mono">{{ c.weight.toFixed(2) }}</td>
                        <td class="right"><meter min="0" max="1" [value]="c.confidence" [attr.aria-label]="'confidence ' + c.confidence"></meter> {{ (c.confidence * 100).toFixed(0) }}%</td>
                        <td class="nowrap">{{ age(c.ageSeconds) }}</td>
                        <td>{{ c.source }}</td>
                        <td class="expl">{{ c.explanation }}</td>
                      </tr>
                    } @empty { <tr><td colspan="8" class="muted">No contribution: nothing notable was observed.</td></tr> }
                  </tbody>
                  <tfoot><tr><td colspan="2"><strong>Total (clamped 0-100)</strong></td><td class="right mono"><strong>{{ r.score }}</strong></td><td colspan="5"></td></tr></tfoot>
                </table></div>
              </section>
            </div>
          }
        </oci-state>
      } @else {
        <div class="state state--empty card">Enter an address to see its risk breakdown.</div>
      }
    </div>
  `,
  styles: `
    .grow { flex: 1; min-width: 240px; }
    .legend { display: grid; gap: 6px; font-size: 13px; color: var(--text-2); }
    .top { grid-template-columns: minmax(240px, 300px) 1fr; }
    @media (max-width: 900px) { .top { grid-template-columns: 1fr; } }
    .gauge { width: 100%; max-width: 260px; display: block; margin: 0 auto; }
    .track { fill: none; stroke: var(--surface-2); stroke-width: 16; }
    .value { fill: none; stroke: currentColor; stroke-width: 16; background: none; }
    .num { font-size: 34px; font-weight: 700; fill: var(--text); }
    .lvl { font-size: 12px; font-weight: 600; fill: var(--muted); letter-spacing: 0.08em; }
    .neg { color: var(--ok); }
    .expl { min-width: 260px; }
    meter { width: 60px; vertical-align: middle; }
  `,
})
export class RiskPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly network = input<NetworkId>();
  readonly address = input<string>();

  readonly networks = NETWORKS;
  readonly addrInput = signal('');
  readonly netInput = signal<NetworkId>('ethereum');
  readonly risk = query<RiskScore>();
  readonly ago = timeAgo;
  readonly age = formatAge;
  /** Half circle of radius 80 has length 80π. */
  readonly arc = computed(() => ((this.risk.data()?.score ?? 0) / 100) * Math.PI * 80);

  readonly t = new TableState<RiskContribution>(
    computed(() => this.risk.data()?.contributions ?? []),
    { ev: (c) => EVIDENCE_ORDER[c.evidenceLevel], points: (c) => c.points, weight: (c) => c.weight, confidence: (c) => c.confidence, age: (c) => c.ageSeconds },
    (c) => c.factor,
    { key: 'points', dir: 'desc' },
  );

  constructor() {
    effect(() => {
      const a = this.address();
      const n = this.network();
      if (!a || !n) return;
      this.addrInput.set(a);
      this.netInput.set(n);
      this.risk.run(() => this.api.walletRisk(a, n), false);
    });
  }

  open(): void {
    const d = detectQuery(this.addrInput());
    const n = d.candidates.includes(this.netInput()) ? this.netInput() : (d.candidates[0] ?? this.netInput());
    this.router.navigate(['/risk', n, d.normalized || this.addrInput().trim()]);
  }
}
