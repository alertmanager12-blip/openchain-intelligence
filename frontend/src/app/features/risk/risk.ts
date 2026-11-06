import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { EvidenceLevel, NETWORKS, NetworkId, RiskContribution, RiskScore } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { formatAge, timeAgo } from '../../shared/format';
import { TableState, query } from '../../shared/query';
import { UI, sentence } from '../../shared/ui';

const EVIDENCE_ORDER: Record<EvidenceLevel, number> = { ANOMALY: 0, RISK_SIGNAL: 1, CONFIRMED_BY_SOURCE: 2 };

@Component({
  selector: 'oci-risk-page',
  imports: [FormsModule, RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Wallet risk</h1>
          <p>Every score is the sum of explained contributions. Each one shows where it comes from, how confident it is and how old the evidence is. A score helps you prioritise; it is never a verdict about a person.</p>
        </div>
      </div>

      <form class="card row" (submit)="$event.preventDefault(); open()">
        <label class="grow">Address<input name="a" class="mono" [ngModel]="addrInput()" (ngModelChange)="addrInput.set($event)" placeholder="Address to score" /></label>
        <label>Network<select name="n" [ngModel]="netInput()" (ngModelChange)="netInput.set($event)">
          @for (n of networks; track n) { <option [ngValue]="n">{{ n }}</option> }
        </select></label>
        <button class="btn btn--primary" type="submit" [disabled]="!addrInput().trim()">Score wallet</button>
      </form>

      <dl class="legend" aria-label="Evidence levels">
        <div><dt><oci-evidence value="ANOMALY" /></dt><dd>Statistically unusual behaviour. Not a risk claim.</dd></div>
        <div><dt><oci-evidence value="RISK_SIGNAL" /></dt><dd>A pattern associated with risk. Not proof.</dd></div>
        <div><dt><oci-evidence value="CONFIRMED_BY_SOURCE" /></dt><dd>Stated by a cited external source, such as an official list.</dd></div>
      </dl>

      @if (address() && network()) {
        <oci-state [loading]="risk.loading()" [error]="risk.error()" [hasData]="!!risk.data()" (retry)="risk.retry()" loadingText="Computing score…">
          @if (risk.data(); as r) {
            <div class="grid top">
              <section class="score" aria-labelledby="h-g">
                <h2 id="h-g">Score</h2>
                <oci-gauge size="lg" [score]="r.score" [level]="r.level" label="Wallet risk score" />
                <p class="small muted">Method {{ r.method }}, model {{ r.modelVersion }}, computed {{ ago(r.computedAt) }}.</p>
                <p class="small"><oci-addr [address]="r.address" [network]="r.network" [full]="true" /></p>
                <div class="row"><a class="btn btn--sm" [routerLink]="['/wallets', r.network, r.address]">Open wallet</a><a class="btn btn--sm" [routerLink]="['/investigation']" [queryParams]="{ address: r.address, network: r.network }">Trace fund flows</a></div>
              </section>
              <section class="section contrib" aria-labelledby="h-c">
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
                        <td class="nowrap"><strong>{{ sentence(c.factor) }}</strong>@if (c.signalId) { <div class="small muted mono">{{ c.signalId }}</div> }</td>
                        <td><oci-evidence [value]="c.evidenceLevel" /></td>
                        <td class="right strong" [class.neg]="c.points < 0">{{ c.points > 0 ? '+' : '' }}{{ c.points }}</td>
                        <td class="right">{{ c.weight.toFixed(2) }}</td>
                        <td class="right nowrap"><span class="conf" aria-hidden="true"><span [style.width.%]="c.confidence * 100"></span></span> {{ (c.confidence * 100).toFixed(0) }}%</td>
                        <td class="nowrap">{{ age(c.ageSeconds) }}</td>
                        <td>{{ c.source }}</td>
                        <td class="expl">{{ c.explanation }}</td>
                      </tr>
                    } @empty { <tr><td colspan="8" class="muted">No contribution: nothing notable was observed.</td></tr> }
                  </tbody>
                  <tfoot><tr><td colspan="2">Score (sum clamped to 0–100)</td><td class="right">{{ r.score }}</td><td colspan="5"></td></tr></tfoot>
                </table></div>
              </section>
            </div>
          }
        </oci-state>
      } @else {
        <div class="state state--empty">Enter an address above and choose its network to see how its score is built.</div>
      }
    </div>
  `,
  styles: `
    .grow { flex: 1; min-width: 240px; }
    .legend { display: flex; flex-wrap: wrap; gap: 8px 32px; margin: 0; font-size: 13px; color: var(--ink-2); }
    .legend div { display: flex; gap: 8px; align-items: center; }
    .legend dd { margin: 0; }
    .top { grid-template-columns: minmax(260px, 360px) minmax(0, 1fr); gap: 40px; align-items: start; }
    @media (max-width: 900px) { .top { grid-template-columns: minmax(0, 1fr); } }
    .score { display: grid; gap: 10px; padding-top: 16px; border-top: 1px solid var(--line); }
    .score h2 { margin: 0; }
    .strong { font-weight: 600; }
    .neg { color: var(--ok); }
    .expl { min-width: 260px; color: var(--ink-2); }
    .conf { display: inline-block; width: 48px; height: 4px; background: var(--line-soft); vertical-align: middle; margin-right: 4px; }
    .conf span { display: block; height: 100%; background: var(--ink-2); }
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
  readonly sentence = sentence;

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
