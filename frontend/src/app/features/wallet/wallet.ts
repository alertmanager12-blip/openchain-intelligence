import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Counterparty, NetworkId, Page, ChainTransaction } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { formatUnits, shortHash, timeAgo } from '../../shared/format';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { TableState } from '../../shared/table-state';
import { UI, sentence } from '../../shared/ui';
import { ScoreSheet } from './score-sheet';
import { WalletProfile } from './wallet-profile';

const RISK_ORDER = { UNKNOWN: 0, LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 };

@Component({
  selector: 'oci-wallet',
  imports: [RouterLink, MatTabsModule, ScoreSheet, WalletProfile, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './wallet.html',
  styleUrl: './wallet.scss',
})
export class WalletPage {
  private readonly api = inject(ApiService);
  readonly network = input.required<NetworkId>();
  readonly address = input.required<string>();

  private readonly key = computed(() => ({ address: this.address(), network: this.network() }));
  readonly w = apiResource({ params: this.key, stream: (k) => this.api.walletAnalysis(k.address, k.network) });
  readonly exp = apiResource({ params: this.key, stream: (k) => this.api.walletExposure(k.address, k.network) });

  /** Transaction pages: the cursor resets when the wallet changes; pages accumulate for "Load more". */
  private readonly cursor = linkedSignal<string, string | null>({ source: () => this.address() + this.network(), computation: () => null });
  readonly txPage = apiResource({ params: () => ({ ...this.key(), cursor: this.cursor() }), stream: (p) => this.api.walletTransactions(p.address, p.network, p.cursor) });
  readonly txs = linkedSignal<{ wallet: string; cursor: string | null; page: Page<ChainTransaction> | undefined }, ChainTransaction[]>({
    source: () => ({ wallet: this.address() + this.network(), cursor: this.cursor(), page: this.txPage.value() }),
    computation: (src, prev) => {
      const same = prev?.source.wallet === src.wallet;
      if (!src.page) return same ? prev.value : [];
      return src.cursor === null || !same ? src.page.items : [...prev.value, ...src.page.items];
    },
  });
  readonly nextCursor = computed(() => this.txPage.value()?.nextCursor ?? null);

  readonly ago = timeAgo;
  readonly units = formatUnits;
  readonly short = (h: string) => shortHash(h, 10, 6);
  readonly sentence = sentence;
  readonly pct = (v: number) => (v * 100).toFixed(0);

  readonly tabIndex = signal(0);
  readonly txCols = ['hash', 'when', 'dir', 'cp', 'value', 'status'];
  readonly cpCols = ['address', 'direction', 'txCount', 'in', 'out', 'risk'];
  readonly expCols = ['category', 'hops', 'share', 'via', 'evidence'];
  readonly sigCols = ['signal', 'severity', 'evidence', 'detected'];
  readonly byHash = (_: number, t: ChainTransaction) => t.hash;
  readonly byCp = (_: number, c: Counterparty) => c.address + c.token.symbol;
  /** Tab labels with the record count of each (shown after the label). */
  readonly tabs = computed<{ label: string; count?: string | number }[]>(() => {
    const a = this.w.value();
    return [
      { label: 'Transactions', count: a?.txCount.toLocaleString('en-US') },
      { label: 'Counterparties', count: a?.counterparties.length },
      { label: 'Exposure', count: this.exp.value() ? this.expRows().length : undefined },
      { label: 'Signals', count: a?.signals.length },
      { label: 'Balances and activity' },
    ];
  });
  readonly expRows = computed(() => {
    const e = this.exp.value();
    return e ? [...e.direct, ...e.indirect] : [];
  });

  readonly cp = new TableState<Counterparty>(
    computed(() => this.w.value()?.counterparties ?? []),
    { address: (c) => c.address, txCount: (c) => c.txCount, in: (c) => BigInt(c.rawIn), out: (c) => BigInt(c.rawOut), risk: (c) => RISK_ORDER[c.riskLevel] },
    (c) => `${c.address} ${c.label ?? ''} ${c.token.symbol}`,
    { key: 'txCount', dir: 'desc' },
  );

  loadMore(): void {
    this.cursor.set(this.nextCursor());
  }
}
