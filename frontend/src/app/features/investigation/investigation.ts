import { ChangeDetectionStrategy, Component, computed, effect, inject, input, linkedSignal, signal } from '@angular/core';
import { DateAdapter, provideNativeDateAdapter } from '@angular/material/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatProgressBar } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { ApiError, errorText, statusTitle } from '../../core/api-error';
import { LOCALE } from '../../core/i18n/format';
import { hasKey, I18nService } from '../../core/i18n/i18n.service';
import { ApiService } from '../../core/api.service';
import { EnabledNetworks } from '../../core/enabled-networks';
import { Graph, GraphDirection, GraphNode, GraphStop, NETWORKS, NetworkId, RiskLevel } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { ThemeService } from '../../core/theme.service';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { shortHash, netLabel, sentence } from '../../shared/format';
import { levelLabel } from '../../shared/risk-gauge';
import { MAT_BUTTONS, MAT_FORM } from '../../shared/material';
import { Skeleton } from '../../shared/skeleton';
import { Crumb, PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';
import { GraphCanvas } from './graph-canvas';
import { GraphCommand, GraphSelection, KeyedEdge } from './graph-model';
import { EngineText } from '../../shared/engine-text';
import { GraphInspector, PathInfo } from './graph-inspector';

const TOKENS: Record<NetworkId, string[]> = {
  ethereum: ['ETH', 'USDT', 'USDC'], tron: ['TRX', 'USDT'], bsc: ['BNB', 'USDT'], polygon: ['POL', 'USDC'], bitcoin: ['BTC'], solana: ['SOL', 'USDC'],
};
const LEGEND: RiskLevel[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'];

/** The calendar day picked in the date picker (local date parts), as YYYY-MM-DD. */
function dayOf(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * Edges with at least one transfer in the range: an edge spans its first to its last transfer, so it is kept when that
 * span overlaps the range (filtering on the last transfer only dropped edges with earlier transfers in range, G10).
 */
export function keyed(g: Graph, range: { from?: string; to?: string }): KeyedEdge[] {
  return g.edges
    .filter((e) => !((range.from && e.timestamp < range.from) || (range.to && (e.firstTimestamp ?? e.timestamp) > range.to)))
    .map((edge) => ({ key: `${edge.source}>${edge.target}>${edge.token.network}:${edge.token.contract ?? edge.token.symbol}`, edge }));
}

/** Stops that tell where the graph is incomplete (not the near-universal unknown opening balance or same-instant order). */
const QUIET_STOPS = new Set(['UNKNOWN_OPENING_BALANCE', 'SAME_TIME_ORDER', 'BELOW_MIN_SHARE', 'DUST', 'UNVERIFIED_TOKEN']);
export function structuralStops(stops: GraphStop[] | null | undefined): GraphStop[] {
  return (stops ?? []).filter((s) => !QUIET_STOPS.has(s.reason));
}

@Component({
  selector: 'oci-investigation',
  imports: [PageHeader, GraphCanvas, GraphInspector, EngineText, Skeleton, MatButtonToggleModule, MatDatepickerModule, MatProgressBar, ...MAT_BUTTONS, ...MAT_FORM, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Date range picker on JS Dates; only this page needs a date adapter.
  providers: [provideNativeDateAdapter()],
  template: `
    <div class="page wide graph-page">
      <oci-page-header [heading]="i18n.t('graph.title')" [titleHidden]="true" [crumbs]="crumbs()" />
      <section class="canvas-card" [attr.aria-label]="i18n.t('graph.section')">
        <form class="root-form" (submit)="$event.preventDefault(); submit()" [attr.aria-label]="i18n.t('graph.rootForm')">
        <mat-form-field class="addr">
          <mat-label>{{ i18n.t('graph.root') }}</mat-label>
          <input matInput name="a" class="mono" [value]="addr()" (input)="addr.set($any($event.target).value)" [placeholder]="i18n.t('graph.rootHint')" autocomplete="off" spellcheck="false" />
        </mat-form-field>
        <mat-form-field class="netf">
          <mat-label>{{ i18n.t('graph.network') }}</mat-label>
          <mat-select [value]="net()" (valueChange)="net.set($event)">@for (n of networks(); track n) { <mat-option [value]="n">{{ netLabel(n) }}</mat-option> }</mat-select>
        </mat-form-field>
        <button matButton="filled" type="submit" [disabled]="!addr().trim() || graph.loading()">{{ i18n.t('graph.load') }}</button>
      </form>
        <div class="tools" role="toolbar" [attr.aria-label]="i18n.t('graph.options')">
          <mat-button-toggle-group [attr.aria-label]="i18n.t('graph.direction')" [value]="direction()" (change)="direction.set($event.value)">
            <mat-button-toggle value="out" [title]="i18n.t('graph.outTip')">{{ i18n.t('graph.out') }}</mat-button-toggle>
            <mat-button-toggle value="in" [title]="i18n.t('graph.inTip')">{{ i18n.t('graph.in') }}</mat-button-toggle>
            <mat-button-toggle value="both" [title]="i18n.t('graph.bothTip')">{{ i18n.t('graph.both') }}</mat-button-toggle>
          </mat-button-toggle-group>
          <mat-button-toggle-group [attr.aria-label]="i18n.t('graph.depth')" [value]="depth()" (change)="depth.set($event.value)">
            @for (d of [1, 2, 3]; track d) { <mat-button-toggle [value]="d">{{ i18n.t('graph.hops', { count: d }) }}</mat-button-toggle> }
          </mat-button-toggle-group>
          <mat-form-field class="tool tok">
            <mat-label>{{ i18n.t('graph.token') }}</mat-label>
            <mat-select [value]="token()" (valueChange)="token.set($event)"><mat-option value="">{{ i18n.t('graph.allTokens') }}</mat-option>@for (t of tokenOptions(); track t) { <mat-option [value]="t">{{ t }}</mat-option> }</mat-select>
          </mat-form-field>
          <mat-form-field class="tool dates">
            <mat-label>{{ i18n.t('graph.dates') }}</mat-label>
            <mat-date-range-input [rangePicker]="range" [max]="today">
              <input matStartDate [placeholder]="i18n.t('graph.fromHint')" [value]="from()" (dateChange)="from.set($event.value)" [attr.aria-label]="i18n.t('graph.fromDate')" />
              <input matEndDate [placeholder]="i18n.t('graph.toHint')" [value]="to()" (dateChange)="to.set($event.value)" [attr.aria-label]="i18n.t('graph.toDate')" />
            </mat-date-range-input>
            <mat-datepicker-toggle matIconSuffix [for]="range">
              <svg matDatepickerToggleIcon viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="2.5" y="3.5" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11M5.5 2v3M10.5 2v3"/></svg>
            </mat-datepicker-toggle>
            <mat-date-range-picker #range />
          </mat-form-field>
          <p class="status-line stats" aria-live="polite">
            @if (nodes().length) {
              <span><strong>{{ nodes().length }}</strong> {{ i18n.t('graph.walletsWord', { count: nodes().length }) }}</span><span><strong>{{ edges().length }}</strong> {{ i18n.t('graph.transfersWord', { count: edges().length }) }}</span><span><strong>{{ maxHop() }}</strong> {{ i18n.t('graph.hopsDeep', { count: hopCount() }) }}</span>
              @if (truncated()) { <span class="warn"><strong>{{ i18n.t('graph.truncated') }}</strong>{{ i18n.t('graph.truncatedHint') }}</span> }
              @if (stops().length) { <span class="warn"><strong>{{ i18n.t('graph.partial') }}</strong>{{ i18n.t('graph.partialHint', { count: stops().length }) }}</span> }
            }
          </p>
          <button matButton="outlined" class="sm" type="button" (click)="verify()" [disabled]="!edges().length || verifying()" [title]="i18n.t('graph.verifyTip')">{{ i18n.t('graph.verify') }}</button>
          <button matButton="outlined" class="sm" type="button" (click)="saveTrace()" [disabled]="!root() || direction() === 'both' || saving()" [title]="i18n.t('graph.saveTraceTip')">{{ i18n.t('graph.saveTrace') }}</button>
          <button matButton="outlined" class="sm" type="button" (click)="cmd('fit')" [disabled]="!nodes().length">{{ i18n.t('graph.fit') }}</button>
          <button matButton="outlined" class="sm" type="button" (click)="cmd('png')" [disabled]="!nodes().length">{{ i18n.t('graph.exportPng') }}</button>
        </div>
        @if (nodes().length) {
          <p class="small muted explain">{{ i18n.t('graph.explain') }}</p>
          @if (stops().length) {
            <details class="stops small"><summary>{{ i18n.t('graph.stops', { count: stops().length }) }}</summary>
              <ul>@for (s of stops(); track s.reason + s.address + (s.token ?? '')) { <li><span class="mono">{{ short(s.address) }}</span>: {{ stopName(s.reason) }}, <oci-engine-text [text]="s.detail" [plainInEnglish]="true" /></li> }</ul>
            </details>
          }
        }
        <div class="stage">
          @defer (when nodes().length > 0) {
            <oci-graph-canvas class="canvas" [nodes]="nodes()" [edges]="edges()" [root]="root()" [theme]="theme.effective()" [command]="command()" [fileName]="fileName()"
              (selected)="select($event)" (expandRequested)="expandById($event)" (pathResult)="pathInfo.set({ key: 'graph.path.missing', params: { count: $event } })" (exported)="toasts.success(i18n.t('graph.toast.exported'), fileName())" />
          } @placeholder {
            <div class="canvas"></div>
          } @loading (after 100ms; minimum 300ms) {
            <div class="canvas"><oci-skeleton kind="lines" [rows]="6" [label]="i18n.t('graph.loadingView')" /></div>
          }
          @if (expanding()) { <mat-progress-bar class="expanding" mode="indeterminate" [attr.aria-label]="i18n.t('graph.expandingBar')" /> }
          @if (graph.loading()) { <div class="overlay"><oci-skeleton kind="lines" [rows]="4" [label]="i18n.t('graph.loading')" class="sk" /></div> }
          @else if (graph.error() ?? expandError(); as e) {
            <div class="overlay err" role="alert"><strong>{{ errTitle(e) }}</strong><span>{{ err(e) }}</span><button matButton="outlined" class="sm" type="button" (click)="graph.reload()">{{ i18n.t('state.retry') }}</button></div>
          } @else if (!nodes().length) {
            <div class="overlay"><div class="empty"><strong>{{ i18n.t('graph.emptyTitle') }}</strong><span>{{ i18n.t('graph.emptyBody') }}</span></div></div>
          }
          <aside class="inspector" [attr.aria-label]="i18n.t('graph.inspector')">
            <oci-graph-inspector [node]="selectedNode()" [edge]="selectedEdge()" [from]="edgeFrom()" [to]="edgeTo()" [expanding]="expanding()" [pathInfo]="pathInfo()"
              (expand)="expand($event)" (highlight)="highlight($event)" />
          </aside>
          <div class="legend" [attr.aria-label]="i18n.t('graph.legend')">
            @for (l of legend; track l) { <span><i class="dot-l bg-{{ l.toLowerCase() }}" aria-hidden="true"></i>{{ levelLabel(l, i18n.lang()) }}</span> }
            <span><i class="shape dia" aria-hidden="true"></i>{{ i18n.t('graph.legend.labelled') }}</span>
            <span><i class="shape sq" aria-hidden="true"></i>{{ i18n.t('graph.legend.contract') }}</span>
            <span><i class="shape root" aria-hidden="true"></i>{{ i18n.t('graph.legend.root') }}</span>
            <span class="sep" aria-hidden="true"></span>
            <span><i class="flow in" aria-hidden="true"></i>{{ i18n.t('graph.legend.in') }}</span>
            <span><i class="flow out" aria-hidden="true"></i>{{ i18n.t('graph.legend.out') }}</span>
            <span><i class="flow hop" aria-hidden="true"></i>{{ i18n.t('graph.legend.hop') }}</span>
            <span><i class="flow flag" aria-hidden="true"></i>{{ i18n.t('graph.legend.flag') }}</span>
            <span><svg class="weight" viewBox="0 0 26 10" aria-hidden="true"><path d="M1 5h6" stroke-width="1"/><path d="M10 5h6" stroke-width="2.5"/><path d="M19 5h6" stroke-width="4.5"/></svg>{{ i18n.t('graph.legend.weight') }}</span>
          </div>
        </div>
      </section>
    </div>
  `,
  styles: `
    .graph-page { gap: 0; padding-bottom: 0; height: 100vh; grid-template-rows: auto auto auto minmax(0, 1fr); }
    .canvas-card { display: contents; }
    .root-form { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 10px 0 0; }
    .root-form .addr { flex: 1 1 360px; max-width: 560px; }
    .root-form .netf { width: 150px; }
    .tools { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 8px 0 10px; border-bottom: 1px solid var(--line); margin: 0 calc(-1 * var(--gutter)); padding-inline: var(--gutter); }
    .root-form { margin: 0 calc(-1 * var(--gutter)); padding-inline: var(--gutter); }
    .tok { width: 140px; }
    .explain { margin: 6px 0 0; }
    .stops { margin: 4px 0 8px; }
    .stops ul { margin: 4px 0 0; padding-left: 18px; }
    .dates { width: 240px; }
    .dates svg { width: 16px; height: 16px; }
    .stats { flex: 1; justify-content: flex-end; flex-wrap: nowrap; gap: 4px 14px; font-size: 12.5px; white-space: nowrap; }
    .expanding { position: absolute; top: 0; left: 0; right: 0; z-index: 2; }
    .stage { position: relative; min-height: 480px; margin: 0 calc(-1 * var(--gutter)); background: radial-gradient(circle at 1px 1px, var(--line) 1px, transparent 0) 0 0 / 20px 20px, var(--bg); overflow: hidden; }
    /* the canvas stops short of the floating inspector and legend, so fitting never hides nodes under them */
    .canvas { display: block; position: absolute; inset: 0 332px 56px 0; }
    .overlay { position: absolute; inset: 0; display: grid; place-items: center; padding: 24px; text-align: left; pointer-events: none; }
    .overlay .sk { width: min(420px, 80%); }
    .overlay.err { pointer-events: auto; display: flex; flex-direction: column; justify-content: center; gap: 6px; color: var(--danger); background: color-mix(in srgb, var(--surface) 80%, transparent); }
    .inspector { position: absolute; top: 16px; right: 16px; width: 300px; max-height: calc(100% - 32px); overflow: auto; padding: 14px 16px;
      background: var(--surface); border: 1px solid var(--line); border-radius: 10px; box-shadow: var(--shadow-overlay); }
    .legend { position: absolute; left: 16px; bottom: 16px; right: 332px; display: flex; flex-wrap: wrap; gap: 4px 14px; padding: 6px 10px; font-size: 12px; color: var(--ink-2);
      background: color-mix(in srgb, var(--surface) 90%, transparent); border: 1px solid var(--line); border-radius: 8px; width: fit-content; backdrop-filter: blur(6px); }
    .legend span { display: inline-flex; align-items: center; }
    .dot-l { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
    .shape { display: inline-block; width: 9px; height: 9px; margin-right: 6px; border: 1.5px solid var(--ink-2); }
    .shape.dia { transform: rotate(45deg) scale(0.85); }
    .shape.sq { border-radius: 2px; }
    .shape.root { border-radius: 50%; border-width: 2.5px; border-color: var(--ink); width: 11px; height: 11px; }
    .weight { width: 26px; height: 10px; margin-right: 6px; stroke: var(--muted); fill: none; }
    .sep { width: 1px; height: 14px; background: var(--line); }
    .flow { display: inline-block; width: 18px; height: 0; margin-right: 6px; border-top: 2px solid var(--line-strong); }
    .flow.in { border-color: var(--flow-in); }
    .flow.out { border-color: var(--flow-out); }
    .flow.flag { border-top-style: dashed; border-color: var(--risk-high); }
    @media (max-width: 900px) {
      .graph-page { height: auto; }
      .stage { overflow: visible; }
      .inspector, .legend { position: static; width: auto; max-height: none; box-shadow: none; border-radius: 0; border-width: 1px 0 0; backdrop-filter: none; }
      .stage { display: grid; grid-template-rows: 460px auto auto; height: auto; }
      .canvas, .overlay { grid-row: 1; position: relative; inset: auto; height: 460px; }
      .stats { order: -1; flex-basis: 100%; flex-wrap: wrap; justify-content: flex-start; }
    }
  `,
})
export class InvestigationPage {
  private readonly api = inject(ApiService);
  protected readonly i18n = inject(I18nService);
  private readonly router = inject(Router);
  private readonly enabledNetworks = inject(EnabledNetworks);
  protected readonly theme = inject(ThemeService);
  protected readonly toasts = inject(Toasts);
  /** Query params. */
  readonly address = input<string>();
  readonly network = input<NetworkId>();

  /** Only networks this instance indexes (all known ones while that list is loading). */
  readonly networks = computed<NetworkId[]>(() => this.enabledNetworks.enabled() ?? [...NETWORKS]);
  readonly crumbs = computed<Crumb[]>(() => {
    const r = this.root();
    return r ? [{ label: this.i18n.t('graph.title'), link: '/investigation' }, { label: shortHash(r, 6, 4), mono: true }] : [{ label: this.i18n.t('graph.title') }];
  });
  readonly netLabel = netLabel;
  readonly legend = LEGEND;
  readonly levelLabel = levelLabel;
  readonly hopCount = computed(() => Number(this.maxHop()) || 0);
  readonly stopName = (reason: string) => { const k = `graph.stop.${reason}`; return hasKey(k) ? this.i18n.t(k) : sentence(reason); };
  readonly err = (e: ApiError) => errorText(e, this.i18n.lang());
  readonly errTitle = (e: ApiError) => statusTitle(e, this.i18n.lang());

  readonly addr = signal('');
  readonly net = signal<NetworkId>('ethereum');
  readonly depth = signal(2);
  /** Directed graph (Fund Flow Trace P0-C): out, in or both; the undirected graph is no longer used. */
  readonly direction = signal<GraphDirection>('both');
  readonly token = signal('');
  /** Date range (whole UTC days); null = open-ended. */
  readonly from = signal<Date | null>(null);
  readonly to = signal<Date | null>(null);
  readonly today = new Date();
  readonly tokenOptions = computed(() => TOKENS[this.network() ?? this.net()]);
  private readonly range = computed(() => ({
    from: this.from() ? new Date(dayOf(this.from()!) + 'T00:00:00Z').toISOString() : undefined,
    to: this.to() ? new Date(dayOf(this.to()!) + 'T23:59:59Z').toISOString() : undefined,
  }));

  /** Root graph: follows the URL (address, network) and the toolbar (depth, token, dates). */
  readonly graph = apiResource({
    params: () => {
      const address = this.address();
      if (!address) return undefined;
      return { address, q: { network: this.network() ?? 'ethereum', depth: this.depth(), direction: this.direction(), token: this.token() || undefined, ...this.range() } };
    },
    stream: (p) => this.api.graph(p.address, p.q),
  });

  /** Loaded wallets and transfers: reset by each root load, then grown by expansions. */
  readonly nodes = linkedSignal<GraphNode[]>(() => this.graph.value()?.nodes ?? []);
  readonly edges = linkedSignal<KeyedEdge[]>(() => {
    const g = this.graph.value();
    return g ? keyed(g, this.range()) : [];
  });
  readonly truncated = linkedSignal(() => this.graph.value()?.truncated ?? false);
  readonly stops = linkedSignal<GraphStop[]>(() => structuralStops(this.graph.value()?.stops));
  readonly verifying = signal(false);
  readonly short = (a: string) => shortHash(a, 6, 4);
  readonly root = computed(() => this.graph.value()?.root ?? null);
  readonly maxHop = computed(() => (this.nodes().length ? Math.max(...this.nodes().map((n) => n.depth)) : '–'));
  readonly fileName = computed(() => `fund-flow-${shortHash(this.address() ?? 'graph', 6, 4).replace('…', '-')}.png`);

  readonly selection = linkedSignal<Graph | undefined, GraphSelection>({ source: () => this.graph.value(), computation: () => null });
  readonly selectedNode = computed(() => {
    const s = this.selection();
    return s?.kind === 'node' ? (this.nodes().find((n) => n.id === s.id) ?? null) : null;
  });
  readonly selectedEdge = computed(() => {
    const s = this.selection();
    return s?.kind === 'edge' ? (this.edges().find((e) => e.key === s.id)?.edge ?? null) : null;
  });
  readonly edgeFrom = computed(() => this.nodes().find((n) => n.id === this.selectedEdge()?.source) ?? null);
  readonly edgeTo = computed(() => this.nodes().find((n) => n.id === this.selectedEdge()?.target) ?? null);

  readonly expanding = signal(false);
  readonly expandError = signal<ApiError | null>(null);
  readonly pathInfo = signal<PathInfo | null>(null);
  readonly command = signal<GraphCommand | null>(null);
  private seq = 0;

  private readonly dates = inject<DateAdapter<Date>>(DateAdapter);

  constructor() {
    // The date picker writes its month and weekday names in the console's language.
    effect(() => this.dates.setLocale(LOCALE[this.i18n.lang()]));
    // Mirror the URL into the editable form.
    effect(() => {
      const a = this.address();
      if (a) this.addr.set(a);
      const n = this.network();
      if (n) this.net.set(n);
    });
  }

  submit(): void {
    const d = detectQuery(this.addr());
    const usable = this.enabledNetworks.enabledCandidates(d.candidates);
    const n = usable.includes(this.net()) ? this.net() : (usable[0] ?? this.net());
    const address = d.normalized || this.addr().trim();
    if (this.address() === address && this.network() === n) this.graph.reload();
    else this.router.navigate(['/investigation'], { queryParams: { address, network: n } });
  }

  cmd(kind: GraphCommand['kind'], target?: string): void {
    this.command.set({ kind, target, n: ++this.seq });
  }

  select(s: GraphSelection): void {
    this.selection.set(s);
    this.pathInfo.set(null);
  }

  /**
   * Path computed by the server on the flows (directed, chronological, one token), not on the drawn edges: toward the
   * wallet for a wallet reached on the out side, from it for one on the in side.
   */
  highlight(n: GraphNode): void {
    const root = this.root();
    if (!root) return;
    const toward = n.side !== 'in';
    this.pathInfo.set({ key: 'graph.path.looking' });
    this.api.graphPath(toward ? root : n.address, toward ? n.address : root, n.network, this.token() || undefined).subscribe({
      next: (p) => {
        this.pathInfo.set(p.outcome === 'FOUND' ? { key: 'graph.path.found', params: { count: p.hops.length }, engine: p.note } : { engine: p.note });
        this.command.set({ kind: p.outcome === 'FOUND' ? 'path' : 'clear', hops: p.hops.map((h) => ({ source: h.from, target: h.to })), n: ++this.seq });
      },
      error: (e: ApiError) => this.pathInfo.set({ error: e }),
    });
  }

  readonly saving = signal(false);

  /** Stores the trace of the root in the chosen direction (ADR-0022): kept with its evidence, on the Stored traces page. */
  saveTrace(): void {
    const root = this.root();
    const dir = this.direction();
    if (!root || dir === 'both') return;
    this.saving.set(true);
    this.api.createTrace({ network: this.network() ?? this.net(), seed: root, direction: dir, depth: this.depth() }).subscribe({
      next: (t) => {
        this.saving.set(false);
        this.toasts.success(this.i18n.t('graph.toast.queued'), this.i18n.t('graph.toast.queuedBody'));
        this.router.navigate(['/traces'], { queryParams: { id: t.id } });
      },
      error: (e: ApiError) => { this.saving.set(false); this.toasts.error(statusTitle(e, this.i18n.lang()), errorText(e, this.i18n.lang())); },
    });
  }

  /** Asks the server which transfers shown are no longer held (chain reorganisation, trimmed working set). */
  verify(): void {
    const ids = [...new Set(this.edges().flatMap((e) => e.edge.eventIds ?? []))].slice(0, 1000);
    if (!ids.length) return;
    this.verifying.set(true);
    this.api.verifyGraph(this.network() ?? this.net(), ids).subscribe({
      next: (r) => {
        this.verifying.set(false);
        if (r.missing.length) this.toasts.error(this.i18n.t('graph.toast.missing', { count: r.missing.length }), `${r.note}`);
        else this.toasts.success(this.i18n.t('graph.toast.noChange'), this.i18n.t('graph.toast.stillHeld', { count: r.checked }));
      },
      error: (e: ApiError) => { this.verifying.set(false); this.toasts.error(statusTitle(e, this.i18n.lang()), errorText(e, this.i18n.lang())); },
    });
  }

  expandById(id: string): void {
    const n = this.nodes().find((x) => x.id === id);
    if (n) this.expand(n);
  }

  /** The running expansion, cancelled by the next one (and ignored if the graph it was started on was replaced). */
  private expansion?: { unsubscribe(): void };

  expand(n: GraphNode): void {
    this.expanding.set(true);
    this.expandError.set(null);
    // an answer that arrives after another root, token, date range or depth was loaded belongs to the previous graph:
    // merging it would mix wallets of two graphs (code review I13)
    const startedOn = this.graph.value();
    this.expansion?.unsubscribe();
    // same direction as the graph; an address reached on one side is expanded on that side
    const dir: GraphDirection = this.direction() === 'both' ? (n.side === 'in' ? 'in' : n.side === 'out' ? 'out' : 'both') : this.direction();
    this.expansion = this.api.graph(n.address, { network: n.network, depth: 1, direction: dir, token: this.token() || undefined, ...this.range() }).subscribe({
      next: (g) => {
        if (this.graph.value() !== startedOn) { this.expanding.set(false); return; }
        const known = new Map(this.nodes().map((x) => [x.id, x]));
        const added = g.nodes.filter((x) => !known.has(x.id)).map((x) => ({ ...x, depth: n.depth + x.depth, side: x.side === 'root' ? n.side : x.side }));
        const keys = new Set(this.edges().map((e) => e.key));
        const newEdges = keyed(g, this.range()).filter((e) => !keys.has(e.key));
        this.nodes.update((l) => [...l, ...added]);
        this.edges.update((l) => [...l, ...newEdges]);
        if (g.truncated) this.truncated.set(true);
        const more = structuralStops(g.stops).filter((s) => !this.stops().some((x) => x.reason === s.reason && x.address === s.address));
        if (more.length) this.stops.update((l) => [...l, ...more]);
        this.expanding.set(false);
        this.toasts.success(this.i18n.t('graph.toast.expanded'), this.i18n.t('graph.toast.expandedBody', { wallets: added.length, transfers: newEdges.length }));
      },
      error: (e: ApiError) => {
        if (this.graph.value() === startedOn) this.expandError.set(e);
        this.expanding.set(false);
      },
    });
  }
}
