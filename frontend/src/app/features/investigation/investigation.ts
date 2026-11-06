import {
  ChangeDetectionStrategy, Component, ElementRef, OnDestroy, computed, effect, inject, input, signal, untracked, viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import type { Core, ElementDefinition, NodeSingular } from 'cytoscape';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { Graph, GraphEdge, GraphNode, NETWORKS, NetworkId, RiskLevel } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { ThemeService } from '../../core/theme.service';
import { formatUnits, shortHash } from '../../shared/format';
import { UI } from '../../shared/ui';

const TOKENS: Record<NetworkId, string[]> = {
  ethereum: ['ETH', 'USDT', 'USDC'], tron: ['TRX', 'USDT'], bsc: ['BNB', 'USDT'], polygon: ['POL', 'USDC'], bitcoin: ['BTC'], solana: ['SOL', 'USDC'],
};
const RISK_VAR: Record<RiskLevel, string> = { CRITICAL: '--sev-critical', HIGH: '--sev-high', MEDIUM: '--sev-medium', LOW: '--sev-low', UNKNOWN: '--muted' };

@Component({
  selector: 'oci-investigation',
  imports: [FormsModule, RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Investigation</h1>
          <p>Follow funds hop by hop. Click a node to select it, double-click (or "Expand") to load its own counterparties. A connection to a flagged wallet does not mean shared intent.</p>
        </div>
      </div>
      <form class="card toolbar-form" (submit)="$event.preventDefault(); submit()">
        <label class="grow">Root address<input name="a" class="mono" [ngModel]="addr()" (ngModelChange)="addr.set($event)" placeholder="Address" /></label>
        <label>Network<select name="n" [ngModel]="net()" (ngModelChange)="net.set($event)">@for (n of networks; track n) { <option [ngValue]="n">{{ n }}</option> }</select></label>
        <fieldset class="depth"><legend>Depth</legend>
          <div class="seg">@for (d of [1, 2, 3]; track d) { <button type="button" [attr.aria-pressed]="depth() === d" (click)="depth.set(d)">{{ d }}</button> }</div>
        </fieldset>
        <label>Token<select name="t" [ngModel]="token()" (ngModelChange)="token.set($event)">
          <option value="">All</option>@for (t of tokenOptions(); track t) { <option [value]="t">{{ t }}</option> }
        </select></label>
        <label>From<input type="date" name="from" [ngModel]="from()" (ngModelChange)="from.set($event)" /></label>
        <label>To<input type="date" name="to" [ngModel]="to()" (ngModelChange)="to.set($event)" /></label>
        <button class="btn btn--primary" type="submit" [disabled]="!addr().trim() || loading()">Load graph</button>
      </form>

      <div class="layout">
        <div class="card card--flush graph-card">
          <div class="graph" #graphEl role="img" [attr.aria-label]="'Transaction graph with ' + nodeCount() + ' wallets'"></div>
          @if (loading()) { <div class="overlay"><span class="spinner"></span> Loading graph…</div> }
          @if (error(); as e) { <div class="overlay err" role="alert"><strong>{{ e.title }}</strong><span>{{ e.detail }}</span><button class="btn btn--sm" type="button" (click)="submit()">Retry</button></div> }
          @if (!loading() && !error() && nodeCount() === 0) { <div class="overlay">Enter a root address and load the graph.</div> }
          <div class="legend small">
            @for (l of riskLegend; track l) { <span><i class="sw" [style.background]="'var(' + riskVar[l] + ')'"></i>{{ l }}</span> }
            <span>◆ labelled · ■ contract</span>
            @if (truncated()) { <span class="warn">truncated: narrow filters to see all</span> }
          </div>
        </div>
        <aside class="card side" aria-live="polite">
          @if (selected(); as n) {
            <h2>Selected wallet</h2>
            <p class="mono">{{ n.address }}</p>
            <dl class="kv">
              <dt>Risk</dt><dd><oci-risk [value]="n.riskLevel" /></dd>
              <dt>Label</dt><dd>{{ n.label ?? 'none' }}</dd>
              <dt>Hop</dt><dd>{{ n.depth }}</dd>
              <dt>Contract</dt><dd>{{ n.isContract ? 'yes' : 'no' }}</dd>
            </dl>
            <div class="stack">
              <button class="btn btn--sm" type="button" (click)="expand(n)" [disabled]="expanding()">{{ expanding() ? 'Expanding…' : 'Expand counterparties' }}</button>
              <button class="btn btn--sm" type="button" (click)="highlightPath(n)">Highlight path from root</button>
              <a class="btn btn--sm" [routerLink]="['/wallets', n.network, n.address]">Open wallet page</a>
            </div>
            @if (pathInfo()) { <p class="small muted">{{ pathInfo() }}</p> }
          } @else {
            <h2>Selection</h2>
            <p class="muted small">Select a node to see details, expand it or highlight the path from the root.</p>
          }
          <h3 class="mt">Edges shown</h3>
          <p class="small">{{ edgeCount() }} transfers · {{ nodeCount() }} wallets</p>
        </aside>
      </div>
    </div>
  `,
  styles: `
    .toolbar-form { display: flex; flex-wrap: wrap; gap: 10px; align-items: flex-end; }
    .grow { flex: 1; min-width: 220px; }
    .depth { padding: 0 6px 4px; border: 0; }
    .layout { display: grid; grid-template-columns: 1fr 280px; gap: 16px; }
    @media (max-width: 960px) { .layout { grid-template-columns: 1fr; } }
    .graph-card { position: relative; }
    .graph { height: 600px; width: 100%; }
    @media (max-width: 720px) { .graph { height: 420px; } }
    .overlay { position: absolute; inset: 0 0 32px; display: flex; flex-direction: column; gap: 6px; align-items: center; justify-content: center; color: var(--muted); pointer-events: none; }
    .overlay.err { color: var(--danger); pointer-events: auto; }
    .legend { display: flex; flex-wrap: wrap; gap: 12px; padding: 8px 12px; border-top: 1px solid var(--border); color: var(--text-2); }
    .sw { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 4px; vertical-align: -1px; }
    .warn { color: var(--warn); }
    .side { display: grid; gap: 10px; align-content: start; }
    .mt { margin-top: 8px; }
  `,
})
export class InvestigationPage implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly theme = inject(ThemeService);
  readonly address = input<string>();
  readonly network = input<NetworkId>();

  private readonly graphEl = viewChild.required<ElementRef<HTMLDivElement>>('graphEl');
  readonly networks = NETWORKS;
  readonly riskLegend: RiskLevel[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'];
  readonly riskVar = RISK_VAR;

  readonly addr = signal('');
  readonly net = signal<NetworkId>('ethereum');
  readonly depth = signal(2);
  readonly token = signal('');
  readonly from = signal('');
  readonly to = signal('');
  readonly tokenOptions = computed(() => TOKENS[this.net()]);

  readonly loading = signal(false);
  readonly expanding = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly nodeCount = signal(0);
  readonly edgeCount = signal(0);
  readonly truncated = signal(false);
  readonly selected = signal<GraphNode | null>(null);
  readonly pathInfo = signal<string | null>(null);

  private cy?: Core;
  private root: string | null = null;
  private nodes = new Map<string, GraphNode>();
  private edges = new Map<string, GraphEdge>();

  constructor() {
    effect(() => {
      const a = this.address();
      const n = this.network();
      if (!a) return;
      untracked(() => {
        this.addr.set(a);
        if (n) this.net.set(n);
        this.load();
      });
    });
    // re-colour on theme change (Cytoscape draws on canvas and cannot read CSS variables itself)
    effect(() => {
      this.theme.effective();
      untracked(() => this.cy?.style(this.stylesheet()));
    });
  }

  ngOnDestroy(): void {
    this.cy?.destroy();
  }

  submit(): void {
    const d = detectQuery(this.addr());
    const n = d.candidates.includes(this.net()) ? this.net() : (d.candidates[0] ?? this.net());
    this.router.navigate(['/investigation'], { queryParams: { address: d.normalized || this.addr().trim(), network: n } });
    if (this.address() === (d.normalized || this.addr().trim()) && this.network() === n) this.load();
  }

  private range(): { from?: string; to?: string } {
    return {
      from: this.from() ? new Date(this.from() + 'T00:00:00Z').toISOString() : undefined,
      to: this.to() ? new Date(this.to() + 'T23:59:59Z').toISOString() : undefined,
    };
  }

  load(): void {
    const address = this.addr().trim();
    if (!address) return;
    this.loading.set(true);
    this.error.set(null);
    this.selected.set(null);
    this.pathInfo.set(null);
    this.api.graph(address, { network: this.net(), depth: this.depth(), token: this.token() || undefined, ...this.range() }).subscribe({
      next: (g) => {
        this.root = g.root;
        this.nodes.clear();
        this.edges.clear();
        this.merge(g);
        this.loading.set(false);
        void this.render(true);
      },
      error: (e: ApiError) => {
        this.error.set(e);
        this.loading.set(false);
      },
    });
  }

  expand(n: GraphNode): void {
    this.expanding.set(true);
    this.api.graph(n.address, { network: n.network, depth: 1, token: this.token() || undefined, ...this.range() }).subscribe({
      next: (g) => {
        // depth is relative to the root: keep existing nodes' depth
        g.nodes.forEach((x) => (x.depth = this.nodes.get(x.id)?.depth ?? n.depth + 1));
        this.merge(g);
        this.expanding.set(false);
        void this.render(false);
      },
      error: (e: ApiError) => {
        this.error.set(e);
        this.expanding.set(false);
      },
    });
  }

  private merge(g: Graph): void {
    const { from, to } = this.range();
    for (const n of g.nodes) if (!this.nodes.has(n.id)) this.nodes.set(n.id, n);
    for (const e of g.edges) {
      if ((from && e.timestamp < from) || (to && e.timestamp > to)) continue;
      this.edges.set(e.id + ':' + e.txHash, e);
    }
    this.truncated.set(g.truncated);
  }

  private css(name: string): string {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
  }

  private stylesheet() {
    return [
      {
        selector: 'node',
        style: {
          'background-color': 'data(color)', label: 'data(short)', color: this.css('--text-2'), 'font-size': 9, 'font-family': 'monospace',
          'text-valign': 'bottom', 'text-margin-y': 4, width: 22, height: 22, 'border-width': 2, 'border-color': this.css('--surface'),
        },
      },
      { selector: 'node[?labelled]', style: { shape: 'diamond', width: 28, height: 28 } },
      { selector: 'node[?contract]', style: { shape: 'round-rectangle' } },
      { selector: 'node[?root]', style: { 'border-color': this.css('--accent'), 'border-width': 4, width: 32, height: 32 } },
      { selector: 'node:selected', style: { 'border-color': this.css('--focus'), 'border-width': 4 } },
      {
        selector: 'edge',
        style: {
          width: 1.5, 'line-color': this.css('--border'), 'target-arrow-color': this.css('--muted'), 'target-arrow-shape': 'triangle',
          'curve-style': 'bezier', label: 'data(label)', 'font-size': 8, color: this.css('--muted'), 'text-rotation': 'autorotate',
          'text-background-color': this.css('--surface'), 'text-background-opacity': 0.85, 'text-background-padding': '1px',
        },
      },
      { selector: '.path', style: { 'line-color': this.css('--accent'), 'target-arrow-color': this.css('--accent'), width: 3.5, 'z-index': 10 } },
      { selector: 'node.path', style: { 'border-color': this.css('--accent'), 'border-width': 4 } },
      { selector: '.dim', style: { opacity: 0.25 } },
    ] as never;
  }

  private elements(): ElementDefinition[] {
    const nodes: ElementDefinition[] = [...this.nodes.values()].map((n) => ({
      data: { id: n.id, short: n.label ? `${n.label} · ${shortHash(n.address, 6, 4)}` : shortHash(n.address, 6, 4), color: this.css(RISK_VAR[n.riskLevel]), labelled: !!n.label, contract: n.isContract, root: n.id === this.root },
    }));
    const edges: ElementDefinition[] = [...this.edges.entries()]
      .filter(([, e]) => this.nodes.has(e.source) && this.nodes.has(e.target))
      .map(([id, e]) => ({ data: { id, source: e.source, target: e.target, label: `${formatUnits(e.rawAmount, e.token.decimals, 2)} ${e.token.symbol} · ${e.timestamp.slice(0, 10)}` } }));
    return [...nodes, ...edges];
  }

  private async render(fresh: boolean): Promise<void> {
    const { default: cytoscape } = await import('cytoscape');
    const elements = this.elements();
    this.nodeCount.set(this.nodes.size);
    this.edgeCount.set(elements.length - this.nodes.size);
    if (!this.cy || fresh) {
      this.cy?.destroy();
      this.cy = cytoscape({ container: this.graphEl().nativeElement, elements, style: this.stylesheet(), wheelSensitivity: 0.3, minZoom: 0.2, maxZoom: 3 });
      this.cy.on('tap', 'node', (ev) => this.select(ev.target as NodeSingular));
      this.cy.on('dbltap', 'node', (ev) => {
        const n = this.nodes.get((ev.target as NodeSingular).id());
        if (n) this.expand(n);
      });
      this.cy.on('tap', (ev) => {
        if (ev.target === this.cy) {
          this.selected.set(null);
          this.clearPath();
        }
      });
    } else {
      const existing = new Set(this.cy.elements().map((e) => e.id()));
      this.cy.add(elements.filter((e) => !existing.has(e.data.id!)));
    }
    this.cy.layout({ name: 'cose', animate: false, nodeRepulsion: () => 9000, idealEdgeLength: () => 90, padding: 30 } as never).run();
  }

  private select(node: NodeSingular): void {
    this.selected.set(this.nodes.get(node.id()) ?? null);
    this.pathInfo.set(null);
    this.clearPath();
  }

  private clearPath(): void {
    this.cy?.elements().removeClass('path dim');
  }

  highlightPath(n: GraphNode): void {
    if (!this.cy || !this.root) return;
    this.clearPath();
    const res = this.cy.elements().aStar({ root: `#${CSS.escape(this.root)}`, goal: `#${CSS.escape(n.id)}`, directed: false });
    if (!res.found) {
      this.pathInfo.set('No path to the root within the loaded graph.');
      return;
    }
    this.cy.elements().addClass('dim');
    res.path.removeClass('dim').addClass('path');
    this.pathInfo.set(`Path of ${res.path.edges().length} hop(s) from the root (direction ignored).`);
  }
}
