import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { NETWORKS, NetworkId, TransactionAnalysis } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { formatUnits, timeAgo } from '../../shared/format';
import { query } from '../../shared/query';
import { UI } from '../../shared/ui';

@Component({
  selector: 'oci-explorer',
  imports: [FormsModule, RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Explorer</h1>
          <p>Look up an address or a transaction. The network is detected from the format; EVM formats are shared, so confirm the network when several are possible.</p>
        </div>
      </div>
      <form class="card row" (submit)="$event.preventDefault(); submit()">
        <label class="grow">Address or transaction hash
          <input name="q" class="mono" [ngModel]="text()" (ngModelChange)="text.set($event)" placeholder="0x… / T… / bc1… / base58" autocomplete="off" spellcheck="false" />
        </label>
        <label>Network
          <select name="net" [ngModel]="net()" (ngModelChange)="net.set($event)">
            <option [ngValue]="null">Auto ({{ det().candidates[0] ?? '—' }})</option>
            @for (n of networks; track n) { <option [ngValue]="n">{{ n }}</option> }
          </select>
        </label>
        <button class="btn btn--primary" type="submit" [disabled]="det().kind === 'unknown'">Search</button>
        @if (text().trim()) { <p class="small muted full">{{ det().reason }}</p> }
      </form>

      @if (tx.status() !== 'idle') {
        <section class="card" aria-labelledby="h-tx">
          <h2 id="h-tx">Transaction</h2>
          <oci-state [loading]="tx.loading()" [error]="tx.error()" [hasData]="!!tx.data()" (retry)="tx.retry()">
            @if (tx.data(); as a) {
              <dl class="kv">
                <dt>Hash</dt><dd class="mono">{{ a.transaction.hash }}</dd>
                <dt>Network</dt><dd><oci-net [value]="a.transaction.network" /></dd>
                <dt>Status</dt><dd>{{ a.transaction.success ? 'Success' : 'Failed' }} · {{ a.confirmations }} confirmations{{ a.finalized ? ' · finalized' : ' · not final yet (reorg possible)' }}</dd>
                <dt>Block</dt><dd class="mono">{{ a.transaction.blockNumber }} · {{ ago(a.transaction.timestamp) }}</dd>
                <dt>From</dt><dd><oci-addr [address]="a.transaction.from" [network]="a.transaction.network" [full]="true" /></dd>
                <dt>To</dt><dd>@if (a.transaction.to) { <oci-addr [address]="a.transaction.to" [network]="a.transaction.network" [full]="true" /> } @else { contract creation }</dd>
                <dt>Value</dt><dd class="mono">{{ units(a.transaction.value, a.transaction.nativeDecimals ?? 18) }} {{ a.transaction.nativeSymbol }}</dd>
                <dt>Fee</dt><dd class="mono">{{ units(a.transaction.fee, a.transaction.nativeDecimals ?? 18, 8) }} {{ a.transaction.nativeSymbol }}</dd>
              </dl>
              @if (a.transfers.length) {
                <h3 class="mt">Token transfers</h3>
                <div class="table-wrap"><table>
                  <thead><tr><th scope="col">#</th><th scope="col">From</th><th scope="col">To</th><th scope="col" class="right">Amount</th></tr></thead>
                  <tbody>@for (t of a.transfers; track t.logIndex) {
                    <tr><td>{{ t.logIndex }}</td><td><oci-addr [address]="t.from" [network]="t.network" /></td><td><oci-addr [address]="t.to" [network]="t.network" /></td>
                      <td class="right mono">{{ units(t.rawAmount, t.token.decimals) }} {{ t.token.symbol }}</td></tr>
                  }</tbody>
                </table></div>
              }
              <h3 class="mt">Signals</h3>
              @for (s of a.signals; track s.id) {
                <p><oci-severity [value]="s.severity" /> <oci-evidence [value]="s.evidenceLevel" /> {{ s.type }} · {{ s.evidence[0]?.description }}</p>
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
  styles: `.grow { flex: 1; min-width: 240px; } .full { flex-basis: 100%; margin: 0; } .mt { margin-top: 16px; }`,
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
