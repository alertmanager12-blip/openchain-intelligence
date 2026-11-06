import { ChangeDetectionStrategy, Component, computed, effect, inject, input, linkedSignal, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { Graph, GraphNode, NETWORKS, NetworkId, RiskLevel } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { ThemeService } from '../../core/theme.service';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { shortHash } from '../../shared/format';
import { levelLabel } from '../../shared/risk-gauge';
import { Skeleton } from '../../shared/skeleton';
import { UI } from '../../shared/ui';
import { GraphCanvas } from './graph-canvas';
import { GraphCommand, GraphSelection, KeyedEdge } from './graph-model';
import { GraphInspector } from './graph-inspector';

const TOKENS: Record<NetworkId, string[]> = {
  ethereum: ['ETH', 'USDT', 'USDC'], tron: ['TRX', 'USDT'], bsc: ['BNB', 'USDT'], polygon: ['POL', 'USDC'], bitcoin: ['BTC'], solana: ['SOL', 'USDC'],
};
const LEGEND: RiskLevel[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'];

function keyed(g: Graph, range: { from?: string; to?: string }): KeyedEdge[] {
  return g.edges
    .filter((e) => !((range.from && e.timestamp < range.from) || (range.to && e.timestamp > range.to)))
    .map((edge) => ({ key: `${edge.id}:${edge.txHash}`, edge }));
}

@Component({
  selector: 'oci-investigation',
  imports: [FormsModule, GraphCanvas, GraphInspector, Skeleton, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page graph-page">
      <div class="head">
        <h1>Fund flow graph</h1>
        <form class="root-form" (submit)="$event.preventDefault(); submit()" aria-label="Graph root">
          <label class="addr"><span class="sr-only">Root address</span><input name="a" class="mono" [ngModel]="addr()" (ngModelChange)="addr.set($event)" placeholder="Root address" autocomplete="off" spellcheck="false" /></label>
          <label><span class="sr-only">Network</span><select name="n" [ngModel]="net()" (ngModelChange)="net.set($event)">@for (n of networks; track n) { <option [ngValue]="n">{{ n }}</option> }</select></label>
          <button class="btn btn--primary" type="submit" [disabled]="!addr().trim() || graph.loading()">Load graph</button>
        </form>
      </div>

      <section class="card card--flush canvas-card" aria-label="Graph">
        <div class="tools" role="toolbar" aria-label="Graph options">
          <div class="seg" role="radiogroup" aria-label="Depth">
            @for (d of [1, 2, 3]; track d) { <button type="button" role="radio" [attr.aria-checked]="depth() === d" (click)="depth.set(d)">{{ d }} {{ d === 1 ? 'hop' : 'hops' }}</button> }
          </div>
          <label class="inline">Token<select [ngModel]="token()" (ngModelChange)="token.set($event)"><option value="">All tokens</option>@for (t of tokenOptions(); track t) { <option [value]="t">{{ t }}</option> }</select></label>
          <label class="inline">From<input type="date" [ngModel]="from()" (ngModelChange)="from.set($event)" /></label>
          <label class="inline">To<input type="date" [ngModel]="to()" (ngModelChange)="to.set($event)" /></label>
          <p class="status-line stats" aria-live="polite">
            @if (nodes().length) {
              <span><strong>{{ nodes().length }}</strong> wallets</span><span><strong>{{ edges().length }}</strong> transfers</span><span><strong>{{ maxHop() }}</strong> {{ maxHop() === 1 ? 'hop' : 'hops' }} deep</span>
              @if (truncated()) { <span class="warn"><strong>Truncated</strong>: narrow the token or dates</span> }
            }
          </p>
          <button class="btn btn--sm" type="button" (click)="cmd('fit')" [disabled]="!nodes().length">Fit to view</button>
          <button class="btn btn--sm" type="button" (click)="cmd('png')" [disabled]="!nodes().length">Export PNG</button>
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
          @if (graph.loading()) { <div class="overlay"><oci-skeleton kind="lines" [rows]="4" label="Loading the graph" class="sk" /></div> }
          @else if (graph.error() ?? expandError(); as e) {
            <div class="overlay err" role="alert"><strong>{{ e.title }}</strong><span>{{ e.detail }}</span><button class="btn btn--sm" type="button" (click)="graph.reload()">Retry</button></div>
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
    .graph-page { gap: 16px; padding-top: 24px; padding-bottom: 24px; max-width: none; }
    .head { display: flex; flex-wrap: wrap; gap: 12px 24px; align-items: center; justify-content: space-between; }
    .root-form { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; flex: 1 1 520px; justify-content: flex-end; }
    .root-form .addr { flex: 1 1 360px; max-width: 520px; }
    .root-form input, .root-form select { width: 100%; }
    .tools { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; padding: 8px 14px; border-bottom: 1px solid var(--line-soft); }
    .inline { display: flex; align-items: center; gap: 6px; }
    .inline select, .inline input { min-height: 30px; padding: 3px 8px; font-size: 14px; }
    .stats { flex: 1; justify-content: flex-end; flex-wrap: nowrap; gap: 4px 14px; font-size: 14px; white-space: nowrap; }
    .inline input[type='date'] { width: 140px; }
    .stage { position: relative; height: max(620px, calc(100vh - 230px)); background: radial-gradient(circle at 1px 1px, var(--line-soft) 1px, transparent 0) 0 0 / 22px 22px, var(--surface); overflow: hidden; }
    /* the canvas stops short of the floating inspector and legend, so fitting never hides nodes under them */
    .canvas { display: block; position: absolute; inset: 0 324px 64px 0; }
    .overlay { position: absolute; inset: 0; display: grid; place-items: center; padding: 24px; text-align: left; pointer-events: none; }
    .overlay .sk { width: min(420px, 80%); }
    .overlay.err { pointer-events: auto; display: flex; flex-direction: column; justify-content: center; gap: 6px; color: var(--danger); background: color-mix(in srgb, var(--surface) 80%, transparent); }
    .inspector { position: absolute; top: 12px; right: 12px; width: 300px; max-height: calc(100% - 24px); overflow: auto; padding: 16px 18px;
      background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-panel); box-shadow: var(--shadow-raised); }
    .legend { position: absolute; left: 12px; bottom: 12px; right: 324px; display: flex; flex-wrap: wrap; gap: 4px 16px; padding: 8px 12px; font-size: 13px; color: var(--ink-2);
      background: color-mix(in srgb, var(--surface) 88%, transparent); border: 1px solid var(--line-soft); border-radius: var(--r-control); width: fit-content; backdrop-filter: blur(4px); }
    .legend span { display: inline-flex; align-items: center; }
    .dot-l { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; }
    .shape { display: inline-block; width: 9px; height: 9px; margin-right: 6px; border: 1.5px solid var(--ink-2); }
    .shape.dia { transform: rotate(45deg) scale(0.85); }
    .shape.sq { border-radius: 2px; }
    .shape.root { border-radius: 50%; border-width: 2.5px; border-color: var(--ink); width: 11px; height: 11px; }
    .weight { width: 26px; height: 10px; margin-right: 6px; stroke: var(--muted); fill: none; }
    @media (max-width: 900px) {
      .stage { height: 460px; overflow: visible; }
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
  protected readonly theme = inject(ThemeService);
  protected readonly toasts = inject(Toasts);
  /** Query params. */
  readonly address = input<string>();
  readonly network = input<NetworkId>();

  readonly networks = NETWORKS;
  readonly legend = LEGEND;
  readonly levelLabel = levelLabel;

  readonly addr = signal('');
  readonly net = signal<NetworkId>('ethereum');
  readonly depth = signal(2);
  readonly token = signal('');
  readonly from = signal('');
  readonly to = signal('');
  readonly tokenOptions = computed(() => TOKENS[this.network() ?? this.net()]);
  private readonly range = computed(() => ({
    from: this.from() ? new Date(this.from() + 'T00:00:00Z').toISOString() : undefined,
    to: this.to() ? new Date(this.to() + 'T23:59:59Z').toISOString() : undefined,
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
    const n = d.candidates.includes(this.net()) ? this.net() : (d.candidates[0] ?? this.net());
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
