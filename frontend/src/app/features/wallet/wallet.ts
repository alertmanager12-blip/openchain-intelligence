import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Counterparty, NetworkId, Page, ChainTransaction } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { formatUnits, shortHash, timeAgo } from '../../shared/format';
import { TabDef, TabList } from '../../shared/tab-list';
import { TableState } from '../../shared/table-state';
import { UI, sentence } from '../../shared/ui';
import { ScoreSheet } from './score-sheet';
import { WalletProfile } from './wallet-profile';

type Tab = 'tx' | 'cp' | 'exp' | 'sig' | 'prof';
const RISK_ORDER = { UNKNOWN: 0, LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 };

@Component({
  selector: 'oci-wallet',
  imports: [FormsModule, RouterLink, TabList, ScoreSheet, WalletProfile, ...UI],
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

  readonly tab = signal<Tab>('tx');
  readonly tabs = computed<TabDef<Tab>[]>(() => {
    const a = this.w.value();
    return [
      { id: 'tx', label: 'Transactions', count: a?.txCount.toLocaleString('en-US') },
      { id: 'cp', label: 'Counterparties', count: a?.counterparties.length },
      { id: 'exp', label: 'Exposure', count: this.exp.value() ? this.expRows().length : undefined },
      { id: 'sig', label: 'Signals', count: a?.signals.length },
      { id: 'prof', label: 'Balances and activity' },
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
