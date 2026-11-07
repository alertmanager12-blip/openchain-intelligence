import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { NETWORKS, NetworkId } from '../../core/models';
import { EnabledNetworks } from '../../core/enabled-networks';
import { detectQuery } from '../../core/network-detect';
import { apiResource } from '../../shared/api-resource';
import { formatUnits, timeAgo, netLabel } from '../../shared/format';
import { MAT_BUTTONS, MAT_FORM } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI, sentence } from '../../shared/ui';

const EXAMPLES: { family: string; example: string }[] = [
  { family: 'EVM address (Ethereum, BSC, Polygon)', example: '0x + 40 hex characters' },
  { family: 'EVM transaction hash', example: '0x + 64 hex characters' },
  { family: 'TRON address', example: 'T + 33 base58 characters' },
  { family: 'Bitcoin address', example: 'bc1… or a legacy 1… / 3… address' },
  { family: 'TRON or Bitcoin transaction id', example: '64 hex characters without 0x' },
  { family: 'Solana address or signature', example: 'base58, 32 to 44 or 86 to 88 characters' },
];

@Component({
  selector: 'oci-explorer',
  imports: [RouterLink, PageHeader, ...MAT_BUTTONS, ...MAT_FORM, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <oci-page-header heading="Explorer" lede="Look up an address or a transaction. The network is detected from the format; EVM formats are shared, so confirm the network when asked." />

      <form class="panel-raised search fields" (submit)="$event.preventDefault(); submit()">
        <mat-form-field class="grow">
          <mat-label>Address or transaction hash</mat-label>
          <input matInput name="q" class="mono" [value]="text()" (input)="text.set($any($event.target).value)" placeholder="0x…, T…, bc1… or a base58 address" autocomplete="off" spellcheck="false" />
        </mat-form-field>
        <mat-form-field>
          <mat-label>Network</mat-label>
          <mat-select [value]="net()" (valueChange)="net.set($event)">
            <mat-option [value]="null">Detect ({{ detected() ?? 'no enabled network' }})</mat-option>
            @for (n of networks(); track n) { <mat-option [value]="n">{{ netLabel(n) }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <button matButton="filled" type="submit" [disabled]="det().kind === 'unknown'">Search</button>
        @if (text().trim()) { <p class="small full" [class.warn]="det().kind === 'unknown'" [class.muted]="det().kind !== 'unknown'">{{ det().reason }}</p> }
      </form>

      @if (tx.status() !== 'idle') {
        <section class="section" aria-labelledby="h-tx">
          <oci-state [loading]="tx.loading()" [error]="tx.error()" [hasData]="!!tx.value()" skeleton="readout" [skeletonRows]="4" loadingText="Loading the transaction" (retry)="tx.reload()">
            @if (tx.value(); as a) {
              <div class="section-head">
                <h2 id="h-tx">Transaction</h2>
                <oci-net [value]="a.transaction.network" />
                <span class="act"><a matButton="outlined" class="sm" [routerLink]="['/investigation']" [queryParams]="{ address: a.transaction.from, network: a.transaction.network }">Trace the sender</a></span>
              </div>
              <p class="hash"><span class="mono">{{ a.transaction.hash }}</span><oci-copy [value]="a.transaction.hash" label="Copy transaction hash" /></p>

              <dl class="well instrument">
                <div class="readout"><dt>Status</dt><dd [class.bad]="!a.transaction.success">{{ a.transaction.success ? 'Succeeded' : 'Failed' }}</dd></div>
                <div class="readout"><dt>Value</dt><dd>{{ units(a.transaction.value, a.transaction.nativeDecimals ?? 18) }}<span class="unit">{{ a.transaction.nativeSymbol }}</span></dd></div>
                <div class="readout"><dt>Fee</dt><dd>{{ units(a.transaction.fee, a.transaction.nativeDecimals ?? 18, 8) }}<span class="unit">{{ a.transaction.nativeSymbol }}</span></dd></div>
                <div class="readout"><dt>Confirmations</dt><dd>{{ a.confirmations.toLocaleString('en-US') }}</dd>
                  <span class="sub" [class.warn]="!a.finalized">{{ a.finalized ? 'Finalized' : 'Not final yet, a reorg is still possible' }}</span></div>
              </dl>

              <dl class="kv parties">
                <dt>From</dt><dd><oci-addr [address]="a.transaction.from" [network]="a.transaction.network" [full]="true" /></dd>
                <dt>To</dt><dd>@if (a.transaction.to) { <oci-addr [address]="a.transaction.to" [network]="a.transaction.network" [full]="true" /> } @else { Contract creation }</dd>
                <dt>Block</dt><dd>{{ a.transaction.blockNumber.toLocaleString('en-US') }} <span class="muted">{{ ago(a.transaction.timestamp) }}</span></dd>
                @if (a.transaction.methodSelector) { <dt>Method</dt><dd class="mono">{{ a.transaction.methodSelector }}</dd> }
              </dl>

              <div class="section">
                <div class="section-head"><h3>Token transfers</h3><span class="small muted">{{ a.transfers.length }}</span></div>
                @if (a.transfers.length) {
                  <ol class="transfers">
                    @for (t of a.transfers; track t.logIndex) {
                      <li>
                        <span class="amt">{{ units(t.rawAmount, t.token.decimals) }}<span class="sym">{{ t.token.symbol }}</span></span>
                        <span class="flow">from <oci-addr [address]="t.from" [network]="t.network" />
                          <svg class="arr" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 8h10M9.5 4.5 13 8l-3.5 3.5"/></svg>
                          to <oci-addr [address]="t.to" [network]="t.network" /></span>
                        <span class="log small muted">Log {{ t.logIndex }}</span>
                      </li>
                    }
                  </ol>
                } @else { <p class="muted">This transaction moved no token.</p> }
              </div>

              <div class="section">
                <div class="section-head"><h3>Signals</h3></div>
                @for (s of a.signals; track s.id) {
                  <div class="sig"><oci-severity [value]="s.severity" /><oci-evidence [value]="s.evidenceLevel" /><strong>{{ sentence(s.type) }}</strong>
                    <span class="ink-2 small">{{ s.evidence[0]?.description }}</span></div>
                } @empty { <p class="muted">No detection signal on this transaction.</p> }
              </div>
            }
          </oci-state>
        </section>
      } @else {
        <section class="section" aria-labelledby="h-fmt">
          <div class="section-head"><h2 id="h-fmt">What you can search</h2><p>Paste a full value. Partial addresses are not searched, so a typo never opens the wrong wallet.</p></div>
          <dl class="formats">
            @for (e of examples; track e.family) { <div><dt>{{ e.family }}</dt><dd class="muted">{{ e.example }}</dd></div> }
          </dl>
        </section>
      }
    </div>
  `,
  styles: `
    .search { padding-bottom: 14px; }
    .full { flex-basis: 100%; margin: -4px 0 0; }
    .hash { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 0; color: var(--ink-2); }
    .parties { margin-top: 4px; }
    .transfers { list-style: none; margin: 0; padding: 0; display: grid; }
    .transfers li { display: grid; grid-template-columns: minmax(160px, auto) minmax(0, 1fr) auto; gap: 4px 20px; align-items: baseline; padding: 10px 0; border-bottom: 1px solid var(--line-soft); }
    .amt { font: 600 18px var(--font-display); font-variant-numeric: tabular-nums; white-space: nowrap; }
    .sym { font: 500 14px var(--font); color: var(--muted); margin-left: 5px; }
    .flow { display: inline-flex; flex-wrap: wrap; gap: 6px; align-items: center; color: var(--muted); font-size: 14px; min-width: 0; }
    .arr { width: 14px; height: 14px; }
    .sig { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--line-soft); }
    .formats { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(260px, 100%), 1fr)); gap: 16px 32px; margin: 0; }
    .formats dt { font-weight: 600; } .formats dd { margin: 2px 0 0; font-size: 14px; }
    @media (max-width: 640px) { .transfers li { grid-template-columns: minmax(0, 1fr) auto; } .flow { grid-column: 1 / -1; grid-row: 2; } }
  `,
})
export class ExplorerPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly enabledNetworks = inject(EnabledNetworks);
  /** Bound from query params. */
  readonly q = input<string>();
  readonly network = input<NetworkId>();

  /** Networks offered in the selector: those this instance indexes (all known ones while that list is loading). */
  readonly networks = computed<NetworkId[]>(() => this.enabledNetworks.enabled() ?? [...NETWORKS]);
  /** Detected network: the most likely candidate that is enabled here. */
  readonly detected = computed<NetworkId | undefined>(() => this.enabledNetworks.enabledCandidates(this.det().candidates)[0]);
  readonly netLabel = netLabel;
  readonly examples = EXAMPLES;
  readonly text = signal('');
  readonly net = signal<NetworkId | null>(null);
  readonly det = computed(() => detectQuery(this.text()));
  readonly ago = timeAgo;
  readonly units = formatUnits;
  readonly sentence = sentence;

  /** The transaction named by the URL (?q=&network=); idle when the URL holds no transaction. */
  readonly tx = apiResource({
    params: () => {
      const q = this.q();
      if (!q) return undefined;
      const d = detectQuery(q);
      const n = this.network() ?? this.enabledNetworks.enabledCandidates(d.candidates)[0];
      return d.kind === 'transaction' && n ? { hash: d.normalized, network: n } : undefined;
    },
    stream: (p) => this.api.transactionAnalysis(p.hash, p.network),
  });

  constructor() {
    // Mirror the URL into the editable form fields (the form stays editable afterwards).
    effect(() => {
      const q = this.q();
      if (!q) return;
      this.text.set(q);
      this.net.set(this.network() ?? null);
    });
  }

  submit(): void {
    const d = this.det();
    const n = this.net() ?? this.detected();
    if (!n) return;
    if (d.kind === 'address') this.router.navigate(['/wallets', n, d.normalized]);
    else this.router.navigate(['/explorer'], { queryParams: { q: d.normalized, network: n } });
  }
}
