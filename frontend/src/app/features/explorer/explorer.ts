import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { fmtNumber } from '../../core/i18n/format';
import { I18nService, Key } from '../../core/i18n/i18n.service';
import { ApiService } from '../../core/api.service';
import { GraphCanvas } from '../investigation/graph-canvas';
import { KeyedEdge } from '../investigation/graph-model';
import { ThemeService } from '../../core/theme.service';
import { NETWORKS, NetworkId } from '../../core/models';
import { EnabledNetworks } from '../../core/enabled-networks';
import { detectQuery } from '../../core/network-detect';
import { apiResource } from '../../shared/api-resource';
import { formatUnits, timeAgo, netLabel } from '../../shared/format';
import { EngineText } from '../../shared/engine-text';
import { signalTypeTitle } from '../../shared/explain';
import { MAT_BUTTONS, MAT_FORM } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';

const EXAMPLES: { family: Key; example: Key }[] = [
  { family: 'explorer.fmt.evmAddress', example: 'explorer.fmt.evmAddress.example' },
  { family: 'explorer.fmt.evmTx', example: 'explorer.fmt.evmTx.example' },
  { family: 'explorer.fmt.tronAddress', example: 'explorer.fmt.tronAddress.example' },
  { family: 'explorer.fmt.tronTx', example: 'explorer.fmt.tronTx.example' },
];

@Component({
  selector: 'oci-explorer',
  imports: [RouterLink, PageHeader, GraphCanvas, EngineText, ...MAT_BUTTONS, ...MAT_FORM, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <oci-page-header [heading]="i18n.t('explorer.title')" [lede]="i18n.t('explorer.lede')" />

      <form class="panel-raised search fields" (submit)="$event.preventDefault(); submit()" role="search" [attr.aria-label]="i18n.t('explorer.form')">
        <mat-form-field class="grow q">
          <mat-label>{{ i18n.t('explorer.query') }}</mat-label>
          <input matInput name="q" class="mono" [value]="text()" (input)="text.set($any($event.target).value)" [placeholder]="i18n.t('explorer.queryHint')" autocomplete="off" spellcheck="false" />
        </mat-form-field>
        <mat-form-field floatLabel="always" class="netf">
          <mat-label>{{ i18n.t('explorer.network') }}</mat-label>
          <mat-select [value]="net()" (valueChange)="net.set($event)" [placeholder]="detectLabel()">
            <mat-option [value]="null">{{ detectLabel() }}</mat-option>
            @for (n of networks(); track n) { <mat-option [value]="n">{{ netLabel(n) }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <button matButton="filled" type="submit" [disabled]="det().kind === 'unknown'">{{ i18n.t('explorer.search') }}</button>
        @if (text().trim()) {
          <p class="small full detect" [class.warn]="det().kind === 'unknown'" [class.muted]="det().kind !== 'unknown'" aria-live="polite">
            @if (det().kind !== 'unknown') { <span class="chip">{{ i18n.t(det().kind === 'address' ? 'explorer.kindAddress' : 'explorer.kindTx') }}</span> }
            {{ i18n.t(det().reasonKey) }}
          </p>
        }
      </form>

      @if (tx.status() !== 'idle') {
        <section class="section" aria-labelledby="h-tx">
          <oci-state [loading]="tx.loading()" [error]="tx.error()" [hasData]="!!tx.value()" skeleton="readout" [skeletonRows]="4" [loadingText]="i18n.t('explorer.loadingTx')" (retry)="tx.reload()">
            @if (tx.value(); as a) {
              <div class="section-head">
                <h2 id="h-tx">{{ i18n.t('explorer.tx') }}</h2>
                <oci-net [value]="a.transaction.network" />
                <span class="act"><a matButton="outlined" class="sm" [routerLink]="['/investigation']" [queryParams]="{ address: a.transaction.from, network: a.transaction.network }">{{ i18n.t('explorer.traceSender') }}</a></span>
              </div>
              <p class="hash"><span class="mono">{{ a.transaction.hash }}</span><oci-copy [value]="a.transaction.hash" labelKey="state.copyHash" /></p>

              <dl class="well instrument">
                <div class="readout"><dt>{{ i18n.t('explorer.status') }}</dt><dd [class.bad]="!a.transaction.success"><span class="status" [class.st-ok]="a.transaction.success" [class.st-failed]="!a.transaction.success">{{ i18n.t(a.transaction.success ? 'explorer.succeeded' : 'explorer.failed') }}</span></dd></div>
                <div class="readout"><dt>{{ i18n.t('explorer.value') }}</dt><dd>{{ units(a.transaction.value, a.transaction.nativeDecimals ?? 18) }}<span class="unit">{{ a.transaction.nativeSymbol }}</span></dd></div>
                <div class="readout"><dt>{{ i18n.t('explorer.fee') }}</dt><dd>{{ units(a.transaction.fee, a.transaction.nativeDecimals ?? 18, 8) }}<span class="unit">{{ a.transaction.nativeSymbol }}</span></dd></div>
                <div class="readout"><dt>{{ i18n.t('explorer.confirmations') }}</dt><dd>{{ num(a.confirmations) }}</dd>
                  <span class="sub" [class.warn]="!a.finalized">{{ i18n.t(a.finalized ? 'explorer.finalized' : 'explorer.notFinal') }}</span></div>
              </dl>

              <dl class="kv parties">
                <dt>{{ i18n.t('explorer.from') }}</dt><dd><oci-addr [address]="a.transaction.from" [network]="a.transaction.network" [full]="true" /></dd>
                <dt>{{ i18n.t('explorer.to') }}</dt><dd>@if (a.transaction.to) { <oci-addr [address]="a.transaction.to" [network]="a.transaction.network" [full]="true" /> } @else { {{ i18n.t('explorer.creation') }} }</dd>
                <dt>{{ i18n.t('explorer.block') }}</dt><dd>{{ num(a.transaction.blockNumber) }} <span class="muted">{{ ago(a.transaction.timestamp) }}</span></dd>
                @if (a.transaction.methodSelector) { <dt>{{ i18n.t('explorer.method') }}</dt><dd class="mono">{{ a.transaction.methodSelector }}</dd> }
              </dl>

              <div class="section">
                <div class="section-head"><h3>{{ i18n.t('explorer.transfers') }}</h3><span class="small muted">{{ a.transfers.length }}</span></div>
                @if (a.transfers.length) {
                  <ol class="transfers">
                    @for (t of a.transfers; track t.logIndex) {
                      <li>
                        <span class="amt">{{ units(t.rawAmount, t.token.decimals) }}<span class="sym">{{ t.token.symbol }}</span></span>
                        <span class="flow">{{ i18n.t('explorer.fromWord') }} <oci-addr [address]="t.from" [network]="t.network" />
                          <svg class="arr" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 8h10M9.5 4.5 13 8l-3.5 3.5"/></svg>
                          {{ i18n.t('explorer.toWord') }} <oci-addr [address]="t.to" [network]="t.network" /></span>
                        <span class="log small muted">{{ i18n.t('explorer.log', { index: t.logIndex }) }}</span>
                      </li>
                    }
                  </ol>
                } @else { <p class="muted">{{ i18n.t('explorer.noTransfers') }}</p> }
              </div>

              <div class="section">
                <div class="section-head"><h3>{{ i18n.t('explorer.signals') }}</h3></div>
                @for (s of a.signals; track s.id) {
                  <div class="sig"><oci-severity [value]="s.severity" /><oci-evidence [value]="s.evidenceLevel" /><strong>{{ signalName(s.type) }}</strong>
                    @if (s.evidence[0]?.description; as d) { <span class="ink-2 small"><oci-engine-text [text]="d" [plainInEnglish]="true" /></span> }</div>
                } @empty { <p class="muted">{{ i18n.t('explorer.noSignals') }}</p> }
              </div>
            }
          </oci-state>
          @if (tx.value(); as a) {
            <div class="mini-graph" aria-labelledby="h-g">
              <div class="section-head"><h3 id="h-g" class="small strong">{{ i18n.t('explorer.senderFlows') }}</h3>
                <span class="act"><a matButton="outlined" class="sm" [routerLink]="['/investigation']" [queryParams]="{ address: a.transaction.from, network: a.transaction.network }">{{ i18n.t('explorer.openGraph') }}</a></span></div>
              @if (graph.value(); as g) {
                @if (g.nodes.length) {
                  @defer {
                    <oci-graph-canvas class="mini-canvas" [nodes]="g.nodes" [edges]="keyedEdges()" [root]="g.root" [theme]="theme.effective()" fileName="sender-flows.png" />
                  }
                } @else { <p class="small muted">{{ i18n.t('explorer.noGraph') }}</p> }
              } @else if (graph.error()) { <p class="small muted">{{ i18n.t('explorer.graphError') }}</p> }
              @else { <p class="small muted">{{ i18n.t('explorer.graphLoading') }}</p> }
            </div>
          }
        </section>
      } @else {
        <section class="section" aria-labelledby="h-fmt">
          <div class="section-head"><h2 id="h-fmt">{{ i18n.t('explorer.formats') }}</h2><p>{{ i18n.t('explorer.formatsLede') }}</p></div>
          <dl class="formats">
            @for (e of examples; track e.family) { <div><dt>{{ i18n.t(e.family) }}</dt><dd class="muted">{{ i18n.t(e.example) }}</dd></div> }
          </dl>
        </section>
      }
    </div>
  `,
  styles: `
    .mini-graph { margin-top: 16px; }
    .mini-canvas { height: 440px; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
    .search { padding: 20px 20px 16px; gap: 12px; align-items: center; }
    .search .mat-mdc-form-field { --mat-form-field-container-height: 44px; --mat-form-field-container-vertical-padding: 11px; --mat-form-field-container-text-size: 15px; }
    .search .q { flex: 1 1 420px; }
    .search .netf { flex: 0 1 240px; }
    .search button[type='submit'] { --mat-button-filled-container-height: 44px; min-width: 104px; }
    .full { flex-basis: 100%; margin: -2px 0 0; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .detect .chip { color: var(--accent); background: var(--accent-tint); }
    @media (max-width: 640px) { .search { padding: 16px; } .search .netf, .search button[type='submit'] { flex: 1 1 100%; } }
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
    .formats > div { padding: 12px 14px; border: 1px solid var(--line-soft); border-radius: var(--r-well); background: var(--well); }
    .formats dt { font-weight: 500; color: var(--ink); }
    .formats dd { margin: 4px 0 0; font-size: var(--fs-12); }
    @media (max-width: 640px) { .transfers li { grid-template-columns: minmax(0, 1fr) auto; } .flow { grid-column: 1 / -1; grid-row: 2; } }
  `,
})
export class ExplorerPage {
  private readonly api = inject(ApiService);
  protected readonly i18n = inject(I18nService);
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
  /** The automatic choice, as the selector shows it. */
  readonly detectLabel = computed(() => {
    if (!this.text().trim()) return this.i18n.t('explorer.detect');
    const d = this.detected();
    return d ? this.i18n.t('explorer.detectNet', { network: netLabel(d) }) : this.i18n.t('explorer.noNetwork');
  });
  readonly examples = EXAMPLES;
  readonly text = signal('');
  readonly net = signal<NetworkId | null>(null);
  readonly det = computed(() => detectQuery(this.text()));
  readonly ago = (iso: string | null | undefined) => timeAgo(iso, Date.now(), this.i18n.lang());
  readonly units = (raw: string | null | undefined, decimals: number, max = 6) => formatUnits(raw, decimals, max, this.i18n.lang());
  readonly num = (n: number) => fmtNumber(n, this.i18n.lang());
  readonly signalName = (type: string) => signalTypeTitle(type, this.i18n.lang());

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

  readonly theme = inject(ThemeService);
  /** One hop around the sender of the transaction shown: the same canvas and controls as the fund flow graph. */
  readonly graph = apiResource({
    params: () => {
      const a = this.tx.value();
      return a ? { address: a.transaction.from, network: a.transaction.network } : undefined;
    },
    stream: (p) => this.api.graph(p.address, { network: p.network, depth: 1, direction: 'both' }),
  });
  readonly keyedEdges = computed<KeyedEdge[]>(() => (this.graph.value()?.edges ?? [])
    .map((edge) => ({ key: `${edge.source}>${edge.target}>${edge.token.network}:${edge.token.contract ?? edge.token.symbol}`, edge })));

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
