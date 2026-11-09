import { ChangeDetectionStrategy, Component, computed, effect, inject, input, linkedSignal, signal } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Counterparty, NetworkId, Page, ChainTransaction } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { formatUnits, netLabel, shortHash, timeAgo } from '../../shared/format';
import { Crumb, PageHeader } from '../../shared/page-header';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { TableState } from '../../shared/table-state';
import { signalSummary, signalTitle } from '../../shared/explain';
import { NetworkNotEnabled, isNetworkNotEnabled } from '../../shared/network-not-enabled';
import { UI, sentence } from '../../shared/ui';
import { HISTORY_POLL_MS, HistoryCoverageLine, historyLoading } from './history-coverage';
import { ScoreSheet } from './score-sheet';
import { WalletProfile } from './wallet-profile';

const RISK_ORDER = { UNKNOWN: 0, LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 };

@Component({
  selector: 'oci-wallet',
  imports: [RouterLink, PageHeader, MatTabsModule, ScoreSheet, WalletProfile, HistoryCoverageLine, NetworkNotEnabled, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './wallet.html',
  styleUrl: './wallet.scss',
})
export class WalletPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly network = input.required<NetworkId>();
  readonly address = input.required<string>();

  private readonly key = computed(() => ({ address: this.address(), network: this.network() }));
  readonly w = apiResource({ params: this.key, stream: (k) => this.api.walletAnalysis(k.address, k.network) });
  readonly history = computed(() => this.w.value()?.history ?? null);
  /** The API refused the network (422 on /network): explain it instead of showing the raw validation error. */
  readonly notEnabled = computed(() => isNetworkNotEnabled(this.w.error()));
  private readonly pollMs = inject(HISTORY_POLL_MS);
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
  readonly signalTitle = signalTitle;
  readonly signalSummary = signalSummary;
  readonly pct = (v: number) => (v * 100).toFixed(0);

  readonly tabIndex = signal(0);
  readonly txCols = ['hash', 'when', 'dir', 'cp', 'value', 'status'];
  readonly cpCols = ['address', 'direction', 'txCount', 'in', 'out', 'risk'];
  readonly expCols = ['category', 'hops', 'share', 'via', 'evidence'];
  readonly sigCols = ['signal', 'severity', 'evidence', 'detected'];
  readonly byHash = (_: number, t: ChainTransaction) => t.id ?? t.hash;
  /** The node cannot list the address's transactions: rows are the transfers the platform knows. */
  readonly knownTransfersOnly = computed(() => this.txs().some((t) => t.kind === 'TRANSFER'));
  readonly byCp = (_: number, c: Counterparty) => c.address + c.token.symbol;
  /** Tab labels with the record count of each (shown after the label). */
  readonly tabs = computed<{ label: string; count?: string | number }[]>(() => {
    const a = this.w.value();
    return [
      { label: 'Score' },
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

  constructor() {
    // While the server loads the address history (PENDING / RUNNING), fetch the analysis again every few seconds.
    // The effect re-runs on each new response and on an address change; its cleanup cancels the pending timer,
    // so polling stops at a terminal status, when the wallet changes and when the page is destroyed.
    effect((onCleanup) => {
      if (!historyLoading(this.history()) || this.w.loading()) return;
      const t = setTimeout(() => this.w.reload(), this.pollMs);
      onCleanup(() => clearTimeout(t));
    });
  }

  readonly crumbs = computed<Crumb[]>(() => [
    { label: 'Wallets', link: '/risk' }, { label: netLabel(this.network()) }, { label: shortHash(this.address(), 6, 4), mono: true },
  ]);

  /** Whole-row navigation; the hash and address cells keep their own links for keyboard users. */
  openTx(t: ChainTransaction): void {
    void this.router.navigate(['/explorer'], { queryParams: { q: t.hash, network: t.network } });
  }

  openWallet(address: string): void {
    void this.router.navigate(['/wallets', this.network(), address]);
  }

  loadMore(): void {
    this.cursor.set(this.nextCursor());
  }
}
