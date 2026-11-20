import { ChangeDetectionStrategy, Component, computed, effect, inject, input, linkedSignal, signal } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { codedText, errorText, statusTitle, toApiError } from '../../core/api-error';
import { I18nService } from '../../core/i18n/i18n.service';
import { fmtDate, fmtNumber, fmtPercent } from '../../core/i18n/format';
import { ApiService } from '../../core/api.service';
import { Toasts } from '../../core/toasts';
import { Counterparty, ExposureEntry, NetworkId, Page, ChainTransaction } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { sanctionsSentence } from '../../shared/sanctions-text';
import { formatUnits, netLabel, shortHash, timeAgo } from '../../shared/format';
import { Crumb, PageHeader } from '../../shared/page-header';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { TableState } from '../../shared/table-state';
import { categoryName, signalSummary, signalTitle } from '../../shared/explain';
import { NetworkNotEnabled, isNetworkNotEnabled } from '../../shared/network-not-enabled';
import { EngineText } from '../../shared/engine-text';
import { UI } from '../../shared/ui';
import { HISTORY_POLL_MS, HistoryCoverageLine, historyLoading } from './history-coverage';
import { ScoreSheet } from './score-sheet';
import { WalletProfile } from './wallet-profile';

const RISK_ORDER = { UNKNOWN: 0, LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 };

import { VerdictPanel } from '../../shared/verdict-panel';
import { walletVerdict } from './wallet-verdict';
@Component({
  selector: 'oci-wallet',
  imports: [RouterLink, PageHeader, VerdictPanel, MatTabsModule, ScoreSheet, WalletProfile, HistoryCoverageLine, NetworkNotEnabled, EngineText, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './wallet.html',
  styleUrl: './wallet.scss',
})
export class WalletPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);

  /** Whether this wallet is one of the organisation's own wallets (watchlist). */
  readonly watchlist = apiResource({ params: () => ({}), stream: () => this.api.watchlist() });
  readonly watched = computed(() => (this.watchlist.value() ?? []).find((e) => e.network === this.network()
    && e.address.toLowerCase() === this.address().toLowerCase()) ?? null);
  readonly watchBusy = signal(false);

  async toggleWatch(): Promise<void> {
    this.watchBusy.set(true);
    try {
      const w = this.watched();
      if (w) {
        await firstValueFrom(this.api.removeFromWatchlist(w.id));
        this.watchlist.update((l) => (l ?? []).filter((x) => x.id !== w.id));
        this.toasts.success(this.i18n.t('wallet.toast.unwatched'));
      } else {
        const e = await firstValueFrom(this.api.addToWatchlist(this.network(), this.address(), null));
        this.watchlist.update((l) => [...(l ?? []), e]);
        this.toasts.success(this.i18n.t('wallet.toast.watched'), this.i18n.t('wallet.toast.watchedDetail'));
      }
    } catch (err) {
      this.toasts.error(this.i18n.t('wallet.toast.watchFailed'), errorText(toApiError(err), this.i18n.lang()));
    } finally {
      this.watchBusy.set(false);
    }
  }
  private readonly router = inject(Router);
  readonly network = input.required<NetworkId>();
  readonly address = input.required<string>();

  private readonly key = computed(() => ({ address: this.address(), network: this.network() }));
  /** The verdict at the top of the page (spec 2026-11-18). */
  readonly verdict = computed(() => { const a = this.w.value(); return a ? walletVerdict(a, this.i18n.lang()) : null; });
  /** Official sanctions lists in force (ADR-0027), said in the verdict's coverage. */
  readonly sanctions = apiResource({ params: () => ({}), stream: () => this.api.sanctionsLists() });
  /** A known coverage completed by what sanctions screening rests on; an unknown one stays unknown. */
  withSanctions(coverage: string): string {
    const lang = this.i18n.lang();
    // The history sentence is the server's English one: in French it is shown separately, as the engine's wording.
    if (lang !== 'en') return coverage ? sanctionsSentence(this.sanctions.value()?.items[0], lang) : '';
    return coverage ? `${coverage} ${sanctionsSentence(this.sanctions.value()?.items[0])}` : '';
  }
  /** The server's own coverage sentence, shown apart (labelled as the engine's wording) when the console is not in English. */
  engineCoverage(coverage: string): string | null { return this.i18n.lang() !== 'en' && coverage ? coverage : null; }
  /** Limitations in the console's language, from their codes; the server's English sentence for a code it does not know. */
  readonly limitations = computed(() => {
    const a = this.w.value();
    const lang = this.i18n.lang();
    return (a?.limitations ?? []).map((l, i) => { const c = a?.limitationCodes?.[i]; return c ? codedText(c, lang, l) : l; });
  });
  readonly w = apiResource({ params: this.key, stream: (k) => this.api.walletAnalysis(k.address, k.network) });
  readonly history = computed(() => this.w.value()?.history ?? null);
  /** The API refused the network (422 on /network): explain it instead of showing the raw validation error. */
  readonly notEnabled = computed(() => isNetworkNotEnabled(this.w.error()));
  private readonly pollMs = inject(HISTORY_POLL_MS);
  readonly exp = apiResource({ params: this.key, stream: (k) => this.api.walletExposure(k.address, k.network) });
  readonly rel = apiResource({ params: this.key, stream: (k) => this.api.walletRelations(k.address, k.network) });
  readonly investigating = signal(false);

  /** Runs the investigation plan on this wallet (ADR-0022 amendment 2) and opens its report. */
  investigate(): void {
    this.investigating.set(true);
    this.api.createInvestigation(this.network(), this.address()).subscribe({
      next: (i) => { this.investigating.set(false); void this.router.navigate(['/investigations'], { queryParams: { id: i.id } }); },
      error: (e: unknown) => { this.investigating.set(false); const a = toApiError(e); this.toasts.error(statusTitle(a, this.i18n.lang()), errorText(a, this.i18n.lang())); },
    });
  }

  readonly relError = computed(() => { const e = this.rel.error(); return e ? errorText(e, this.i18n.lang()) : ''; });
  readonly relLabel = (t: string) => this.i18n.t(`wallet.rel.${t === 'COMMON_FUNDER' || t === 'COMMON_SINK' ? t : 'SHARED_COUNTERPARTIES'}`);

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

  readonly ago = (iso: string | null | undefined) => timeAgo(iso, Date.now(), this.i18n.lang());
  readonly units = (raw: string | null | undefined, decimals: number, max = 6) => formatUnits(raw, decimals, max, this.i18n.lang());
  readonly num = (n: number) => fmtNumber(n, this.i18n.lang());
  readonly date = (iso: string) => fmtDate(iso, this.i18n.lang());
  readonly cat = (c: string) => categoryName(c, this.i18n.lang());
  readonly dir = (d: string) => this.i18n.t(`wallet.cp.dir.${d === 'IN' || d === 'OUT' ? d : 'BOTH'}`);
  readonly short = (h: string) => shortHash(h, 10, 6);
  readonly signalTitle = (s: Parameters<typeof signalTitle>[0]) => signalTitle(s, this.i18n.lang());
  readonly signalSummary = signalSummary;
  readonly pct = (v: number) => fmtPercent(v, this.i18n.lang());

  readonly tabIndex = signal(0);
  readonly txCols = ['hash', 'when', 'dir', 'cp', 'value', 'status'];
  readonly cpCols = ['address', 'direction', 'txCount', 'in', 'out', 'risk'];
  readonly expCols = ['category', 'hops', 'amount', 'via', 'evidence'];
  /** Direct: "received 1,000 USDT, sent 0.5 TRX"; indirect: "at most 100 USDT traced". Never a sum across tokens. */
  readonly expAmount = (x: ExposureEntry): string => {
    if (x.traced) return this.i18n.t('wallet.exp.traced', { amount: x.traced.amount, token: x.traced.token });
    const parts = (x.amounts ?? []).flatMap((a) => [
      ...(a.received !== '0' ? [this.i18n.t('wallet.exp.received', { amount: a.received, token: a.token })] : []),
      ...(a.sent !== '0' ? [this.i18n.t('wallet.exp.sent', { amount: a.sent, token: a.token })] : []),
    ]);
    return parts.length ? parts.join(', ') : '—';
  };
  readonly ignoredReason = (r: string) => this.i18n.t(r === 'unverified_token' ? 'wallet.exp.reason.unverified_token' : 'wallet.exp.reason.dust');
  readonly sigCols = ['signal', 'severity', 'evidence', 'detected'];
  readonly byHash = (_: number, t: ChainTransaction) => t.id ?? t.hash;
  /** The node cannot list the address's transactions: rows are the transfers the platform knows. */
  readonly knownTransfersOnly = computed(() => this.txs().some((t) => t.kind === 'TRANSFER'));
  readonly byCp = (_: number, c: Counterparty) => c.address + c.token.symbol;
  /** Tab labels with the record count of each (shown after the label). */
  readonly tabs = computed<{ label: string; count?: string | number }[]>(() => {
    const a = this.w.value();
    const t = (k: 'score' | 'transactions' | 'counterparties' | 'exposure' | 'signals' | 'balances') => this.i18n.t(`wallet.tab.${k}`);
    return [
      { label: t('score') },
      { label: t('transactions'), count: a ? this.num(a.txCount) : undefined },
      { label: t('counterparties'), count: a?.counterparties.length },
      { label: t('exposure'), count: this.exp.value() ? this.expRows().length : undefined },
      { label: t('signals'), count: a?.signals.length },
      { label: t('balances') },
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
    { label: this.i18n.t('wallet.crumb'), link: '/risk' }, { label: netLabel(this.network()) }, { label: shortHash(this.address(), 6, 4), mono: true },
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
