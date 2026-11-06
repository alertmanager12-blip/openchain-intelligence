import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { NETWORKS, NetworkId, TransactionAnalysis } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { formatUnits, timeAgo } from '../../shared/format';
import { query } from '../../shared/query';
import { UI, sentence } from '../../shared/ui';

@Component({
  selector: 'oci-explorer',
  imports: [FormsModule, RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Explorer</h1>
          <p>Look up an address or a transaction. The network is detected from the format. EVM formats are shared by several networks, so confirm the network when asked.</p>
        </div>
      </div>
      <form class="card row" (submit)="$event.preventDefault(); submit()">
        <label class="grow">Address or transaction hash
          <input name="q" class="mono" [ngModel]="text()" (ngModelChange)="text.set($event)" placeholder="0x…, T…, bc1… or a base58 address" autocomplete="off" spellcheck="false" />
        </label>
        <label>Network
          <select name="net" [ngModel]="net()" (ngModelChange)="net.set($event)">
            <option [ngValue]="null">Detect automatically ({{ det().candidates[0] ?? 'none' }})</option>
            @for (n of networks; track n) { <option [ngValue]="n">{{ n }}</option> }
          </select>
        </label>
        <button class="btn btn--primary" type="submit" [disabled]="det().kind === 'unknown'">Search</button>
        @if (text().trim()) { <p class="small muted full">{{ det().reason }}</p> }
      </form>

      @if (tx.status() !== 'idle') {
        <section class="section" aria-labelledby="h-tx">
          <h2 id="h-tx">Transaction</h2>
          <oci-state [loading]="tx.loading()" [error]="tx.error()" [hasData]="!!tx.data()" (retry)="tx.retry()">
            @if (tx.data(); as a) {
              <dl class="kv">
                <dt>Hash</dt><dd class="mono">{{ a.transaction.hash }}</dd>
                <dt>Network</dt><dd><oci-net [value]="a.transaction.network" /></dd>
                <dt>Status</dt><dd>{{ a.transaction.success ? 'Succeeded' : 'Failed' }}, {{ a.confirmations.toLocaleString('en-US') }} confirmations, {{ a.finalized ? 'finalized' : 'not final yet (a reorg is still possible)' }}</dd>
                <dt>Block</dt><dd>{{ a.transaction.blockNumber.toLocaleString('en-US') }} <span class="muted">({{ ago(a.transaction.timestamp) }})</span></dd>
                <dt>From</dt><dd><oci-addr [address]="a.transaction.from" [network]="a.transaction.network" [full]="true" /></dd>
                <dt>To</dt><dd>@if (a.transaction.to) { <oci-addr [address]="a.transaction.to" [network]="a.transaction.network" [full]="true" /> } @else { contract creation }</dd>
                <dt>Value</dt><dd>{{ units(a.transaction.value, a.transaction.nativeDecimals ?? 18) }} {{ a.transaction.nativeSymbol }}</dd>
                <dt>Fee</dt><dd>{{ units(a.transaction.fee, a.transaction.nativeDecimals ?? 18, 8) }} {{ a.transaction.nativeSymbol }}</dd>
              </dl>
              @if (a.transfers.length) {
                <h3 class="mt">Token transfers</h3>
                <div class="table-wrap"><table>
                  <thead><tr><th scope="col" class="right">Log</th><th scope="col">From</th><th scope="col">To</th><th scope="col" class="right">Amount</th></tr></thead>
                  <tbody>@for (t of a.transfers; track t.logIndex) {
                    <tr><td class="right">{{ t.logIndex }}</td><td><oci-addr [address]="t.from" [network]="t.network" /></td><td><oci-addr [address]="t.to" [network]="t.network" /></td>
                      <td class="right">{{ units(t.rawAmount, t.token.decimals) }} <span class="muted">{{ t.token.symbol }}</span></td></tr>
                  }</tbody>
                </table></div>
              }
              <h3 class="mt">Signals</h3>
              @for (s of a.signals; track s.id) {
                <p class="sig"><oci-severity [value]="s.severity" /> <oci-evidence [value]="s.evidenceLevel" /> <strong>{{ sentence(s.type) }}</strong> <span class="ink-2">{{ s.evidence[0]?.description }}</span></p>
              } @empty { <p class="muted">No detection signal on this transaction.</p> }
              <div class="row mt">
                <a class="btn btn--sm" [routerLink]="['/investigation']" [queryParams]="{ address: a.transaction.from, network: a.transaction.network }">Investigate sender</a>
              </div>
            }
          </oci-state>
        </section>
      }
    </div>
  `,
  styles: `.grow { flex: 1; min-width: 240px; } .full { flex-basis: 100%; margin: 0; } .mt { margin-top: 20px; } .sig { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }`,
})
export class ExplorerPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  /** Bound from query params. */
  readonly q = input<string>();
  readonly network = input<NetworkId>();

  readonly networks = NETWORKS;
  readonly text = signal('');
  readonly net = signal<NetworkId | null>(null);
  readonly det = computed(() => detectQuery(this.text()));
  readonly tx = query<TransactionAnalysis>();
  readonly ago = timeAgo;
  readonly units = formatUnits;
  readonly sentence = sentence;

  constructor() {
    effect(() => {
      const q = this.q();
      if (!q) return;
      const d = detectQuery(q);
      const n = this.network() ?? d.candidates[0];
      this.text.set(q);
      this.net.set(this.network() ?? null);
      if (d.kind === 'transaction' && n) this.tx.run(() => this.api.transactionAnalysis(d.normalized, n), false);
    });
  }

  submit(): void {
    const d = this.det();
    const n = this.net() ?? d.candidates[0];
    if (!n) return;
    if (d.kind === 'address') this.router.navigate(['/wallets', n, d.normalized]);
    else this.router.navigate(['/explorer'], { queryParams: { q: d.normalized, network: n } });
  }
}
