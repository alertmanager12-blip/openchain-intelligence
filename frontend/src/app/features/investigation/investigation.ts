import { ChangeDetectionStrategy, Component, computed, effect, inject, input, linkedSignal, signal } from '@angular/core';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatProgressBar } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { EnabledNetworks } from '../../core/enabled-networks';
import { Graph, GraphNode, NETWORKS, NetworkId, RiskLevel } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { ThemeService } from '../../core/theme.service';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { shortHash, netLabel } from '../../shared/format';
import { levelLabel } from '../../shared/risk-gauge';
import { MAT_BUTTONS, MAT_FORM } from '../../shared/material';
import { Skeleton } from '../../shared/skeleton';
import { Crumb, PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';
import { GraphCanvas } from './graph-canvas';
import { GraphCommand, GraphSelection, KeyedEdge } from './graph-model';
import { GraphInspector } from './graph-inspector';

const TOKENS: Record<NetworkId, string[]> = {
  ethereum: ['ETH', 'USDT', 'USDC'], tron: ['TRX', 'USDT'], bsc: ['BNB', 'USDT'], polygon: ['POL', 'USDC'], bitcoin: ['BTC'], solana: ['SOL', 'USDC'],
};
const LEGEND: RiskLevel[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'];

/** The calendar day picked in the date picker (local date parts), as YYYY-MM-DD. */
function dayOf(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function keyed(g: Graph, range: { from?: string; to?: string }): KeyedEdge[] {
  return g.edges
    .filter((e) => !((range.from && e.timestamp < range.from) || (range.to && e.timestamp > range.to)))
    .map((edge) => ({ key: `${edge.id}:${edge.txHash}`, edge }));
}

@Component({
  selector: 'oci-investigation',
  imports: [PageHeader, GraphCanvas, GraphInspector, Skeleton, MatButtonToggleModule, MatDatepickerModule, MatProgressBar, ...MAT_BUTTONS, ...MAT_FORM, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Date range picker on JS Dates; only this page needs a date adapter.
  providers: [provideNativeDateAdapter()],
  template: `
    <div class="page wide graph-page">
      <oci-page-header heading="Fund flow graph" [titleHidden]="true" [crumbs]="crumbs()" />
      <section class="canvas-card" aria-label="Graph">
        <form class="root-form" (submit)="$event.preventDefault(); submit()" aria-label="Graph root">
        <mat-form-field class="addr">
          <mat-label>Root address</mat-label>
          <input matInput name="a" class="mono" [value]="addr()" (input)="addr.set($any($event.target).value)" placeholder="0x…, T… or a base58 address" autocomplete="off" spellcheck="false" />
        </mat-form-field>
        <mat-form-field class="netf">
          <mat-label>Network</mat-label>
          <mat-select [value]="net()" (valueChange)="net.set($event)">@for (n of networks(); track n) { <mat-option [value]="n">{{ netLabel(n) }}</mat-option> }</mat-select>
        </mat-form-field>
        <button matButton="filled" type="submit" [disabled]="!addr().trim() || graph.loading()">Load graph</button>
      </form>
        <div class="tools" role="toolbar" aria-label="Graph options">
          <mat-button-toggle-group aria-label="Depth" [value]="depth()" (change)="depth.set($event.value)">
            @for (d of [1, 2, 3]; track d) { <mat-button-toggle [value]="d">{{ d }} {{ d === 1 ? 'hop' : 'hops' }}</mat-button-toggle> }
          </mat-button-toggle-group>
          <mat-form-field class="tool tok">
            <mat-label>Token</mat-label>
            <mat-select [value]="token()" (valueChange)="token.set($event)"><mat-option value="">All tokens</mat-option>@for (t of tokenOptions(); track t) { <mat-option [value]="t">{{ t }}</mat-option> }</mat-select>
          </mat-form-field>
          <mat-form-field class="tool dates">
            <mat-label>Transfers between</mat-label>
            <mat-date-range-input [rangePicker]="range" [max]="today">
              <input matStartDate placeholder="From" [value]="from()" (dateChange)="from.set($event.value)" aria-label="From date" />
              <input matEndDate placeholder="To" [value]="to()" (dateChange)="to.set($event.value)" aria-label="To date" />
            </mat-date-range-input>
            <mat-datepicker-toggle matIconSuffix [for]="range">
              <svg matDatepickerToggleIcon viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="2.5" y="3.5" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11M5.5 2v3M10.5 2v3"/></svg>
            </mat-datepicker-toggle>
            <mat-date-range-picker #range />
          </mat-form-field>
          <p class="status-line stats" aria-live="polite">
            @if (nodes().length) {
              <span><strong>{{ nodes().length }}</strong> wallets</span><span><strong>{{ edges().length }}</strong> transfers</span><span><strong>{{ maxHop() }}</strong> {{ maxHop() === 1 ? 'hop' : 'hops' }} deep</span>
              @if (truncated()) { <span class="warn"><strong>Truncated</strong>: narrow the token or dates</span> }
            }
          </p>
          <button matButton="outlined" class="sm" type="button" (click)="cmd('fit')" [disabled]="!nodes().length">Fit to view</button>
          <button matButton="outlined" class="sm" type="button" (click)="cmd('png')" [disabled]="!nodes().length">Export PNG</button>
        </div>
        <div class="stage">
          @defer (when nodes().length > 0) {
            <oci-graph-canvas class="canvas" [nodes]="nodes()" [edges]="edges()" [root]="root()" [theme]="theme.effective()" [command]="command()" [fileName]="fileName()"
              (selected)="select($event)" (expandRequested)="expandById($event)" (pathResult)="pathInfo.set($event)" (exported)="toasts.success('Graph exported', fileName())" />
          } @placeholder {
            <div class="canvas"></div>
          } @loading (after 100ms; minimum 300ms) {
            <div class="canvas"><oci-skeleton kind="lines" [rows]="6" label="Loading the graph view" /></div>
          }
          @if (expanding()) { <mat-progress-bar class="expanding" mode="indeterminate" aria-label="Expanding counterparties" /> }
          @if (graph.loading()) { <div class="overlay"><oci-skeleton kind="lines" [rows]="4" label="Loading the graph" class="sk" /></div> }
          @else if (graph.error() ?? expandError(); as e) {
            <div class="overlay err" role="alert"><strong>{{ e.title }}</strong><span>{{ e.detail }}</span><button matButton="outlined" class="sm" type="button" (click)="graph.reload()">Retry</button></div>
          } @else if (!nodes().length) {
            <div class="overlay"><div class="empty"><strong>No graph yet</strong><span>Enter a root address above and load the graph. Transfers in and out of that wallet appear here, hop by hop. A connection to a flagged wallet does not mean shared intent.</span></div></div>
          }
          <aside class="inspector" aria-label="Inspector">
            <oci-graph-inspector [node]="selectedNode()" [edge]="selectedEdge()" [from]="edgeFrom()" [to]="edgeTo()" [expanding]="expanding()" [pathInfo]="pathInfo()"
              (expand)="expand($event)" (highlight)="highlight($event)" />
          </aside>
          <div class="legend" aria-label="Legend">
            @for (l of legend; track l) { <span><i class="dot-l bg-{{ l.toLowerCase() }}" aria-hidden="true"></i>{{ levelLabel(l) }}</span> }
            <span><i class="shape dia" aria-hidden="true"></i>Labelled</span>
            <span><i class="shape sq" aria-hidden="true"></i>Contract</span>
            <span><i class="shape root" aria-hidden="true"></i>Root, rings are hops</span>
            <span><svg class="weight" viewBox="0 0 26 10" aria-hidden="true"><path d="M1 5h6" stroke-width="1"/><path d="M10 5h6" stroke-width="2.5"/><path d="M19 5h6" stroke-width="4.5"/></svg>Line thickness: amount relative to the largest transfer of the same token</span>
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
    return r ? [{ label: 'Fund flow graph', link: '/investigation' }, { label: shortHash(r, 6, 4), mono: true }] : [{ label: 'Fund flow graph' }];
  });
  readonly netLabel = netLabel;
  readonly legend = LEGEND;
  readonly levelLabel = levelLabel;

  readonly addr = signal('');
  readonly net = signal<NetworkId>('ethereum');
  readonly depth = signal(2);
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
      return { address, q: { network: this.network() ?? 'ethereum', depth: this.depth(), token: this.token() || undefined, ...this.range() } };
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
  readonly pathInfo = signal<string | null>(null);
  readonly command = signal<GraphCommand | null>(null);
  private seq = 0;

  constructor() {
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

  highlight(n: GraphNode): void {
    this.cmd('path', n.id);
  }

  expandById(id: string): void {
    const n = this.nodes().find((x) => x.id === id);
    if (n) this.expand(n);
  }

  expand(n: GraphNode): void {
    this.expanding.set(true);
    this.expandError.set(null);
    this.api.graph(n.address, { network: n.network, depth: 1, token: this.token() || undefined, ...this.range() }).subscribe({
      next: (g) => {
        const known = new Map(this.nodes().map((x) => [x.id, x]));
        const added = g.nodes.filter((x) => !known.has(x.id)).map((x) => ({ ...x, depth: n.depth + 1 }));
        const keys = new Set(this.edges().map((e) => e.key));
        const newEdges = keyed(g, this.range()).filter((e) => !keys.has(e.key));
        this.nodes.update((l) => [...l, ...added]);
        this.edges.update((l) => [...l, ...newEdges]);
        if (g.truncated) this.truncated.set(true);
        this.expanding.set(false);
        this.toasts.success('Counterparties expanded', `${added.length} new ${added.length === 1 ? 'wallet' : 'wallets'}, ${newEdges.length} new ${newEdges.length === 1 ? 'transfer' : 'transfers'}`);
      },
      error: (e: ApiError) => {
        this.expandError.set(e);
        this.expanding.set(false);
      },
    });
  }
}
