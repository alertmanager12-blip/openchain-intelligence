import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, afterRenderEffect, inject, input, output, untracked, viewChild } from '@angular/core';
import { I18nService } from '../../core/i18n/i18n.service';
import cytoscape, { Core, ElementDefinition, EventObject, NodeSingular } from 'cytoscape';
import { GraphNode } from '../../core/models';
import { formatUnits, shortHash } from '../../shared/format';
import { GraphCommand, GraphSelection, KeyedEdge, RISK_VAR, edgeWidths, graphDiff } from './graph-model';

/**
 * Cytoscape canvas. Rendered inside a @defer block by the page, so cytoscape (~400 kB) loads only when a graph
 * is shown. All communication goes through inputs (data, theme, commands) and outputs (selection, expand, path).
 */
@Component({
  selector: 'oci-graph-canvas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div #el class="cy" role="img" tabindex="0" (keydown)="key($event)"
      [attr.aria-label]="i18n.t('graph.canvas.aria', { wallets: nodes().length, transfers: edges().length })"></div>
    <div class="zoom" role="group" [attr.aria-label]="i18n.t('graph.zoom')">
      <button type="button" (click)="zoomBy(1.25)" [title]="i18n.t('graph.zoomInTip')" [attr.aria-label]="i18n.t('graph.zoomIn')">+</button>
      <button type="button" (click)="zoomBy(0.8)" [title]="i18n.t('graph.zoomOutTip')" [attr.aria-label]="i18n.t('graph.zoomOut')">&minus;</button>
      <button type="button" (click)="fitAll()" [title]="i18n.t('graph.fitTip')" [attr.aria-label]="i18n.t('graph.fitAria')">&#x2922;</button>
    </div>`,
  styles: `
    :host { display: block; position: relative; }
    .cy { width: 100%; height: 100%; min-height: 420px; touch-action: none; outline: none; }
    .cy:focus-visible { box-shadow: inset 0 0 0 2px var(--focus); }
    .zoom { position: absolute; top: 10px; left: 10px; display: flex; flex-direction: column; gap: 4px; z-index: 3; }
    .zoom button { width: 30px; height: 30px; font-size: 16px; line-height: 1; cursor: pointer; color: var(--ink); background: var(--surface);
      border: 1px solid var(--line); border-radius: 6px; box-shadow: var(--shadow-overlay, none); }
    .zoom button:hover { border-color: var(--line-strong); }
  `,
})
export class GraphCanvas {
  protected readonly i18n = inject(I18nService);
  readonly nodes = input.required<GraphNode[]>();
  readonly edges = input.required<KeyedEdge[]>();
  readonly root = input<string | null>(null);
  /** Effective theme; Cytoscape draws on canvas and cannot read CSS variables itself, so it is restyled on change. */
  readonly theme = input<'light' | 'dark'>('light');
  readonly command = input<GraphCommand | null>(null);
  readonly fileName = input('fund-flow.png');

  readonly selected = output<GraphSelection>();
  readonly expandRequested = output<string>();
  /** Number of path transfers that go through wallets not drawn here. */
  readonly pathResult = output<number>();
  readonly exported = output<void>();

  private readonly el = viewChild.required<ElementRef<HTMLDivElement>>('el');
  private cy?: Core;
  private builtFor: string | null = null;
  private lastCommand = 0;

  constructor() {
    afterRenderEffect({
      write: () => {
        const nodes = this.nodes();
        const edges = this.edges();
        const root = this.root();
        untracked(() => this.sync(nodes, edges, root));
      },
    });
    afterRenderEffect({
      write: () => {
        this.theme();
        untracked(() => {
          if (!this.cy) return;
          this.cy.nodes().forEach((n) => { n.data('color', this.css(n.data('rv'))); });
          this.cy.style(this.stylesheet());
        });
      },
    });
    // the words and numbers drawn on the canvas (root label, amounts on the edges) follow the console's language
    afterRenderEffect({
      write: () => {
        const lang = this.i18n.lang();
        const edges = this.edges();
        untracked(() => {
          const cy = this.cy;
          if (!cy) return;
          cy.nodes('[?root]').data('short', this.i18n.t('graph.canvas.rootShort'));
          for (const { key, edge } of edges) {
            const el = cy.getElementById(key);
            if (!el.empty()) el.data('label', `${formatUnits(edge.rawAmount, edge.token.decimals, 2, lang)} ${edge.token.symbol}`);
          }
        });
      },
    });
    afterRenderEffect({
      write: () => {
        const c = this.command();
        if (!c || c.n === this.lastCommand) return;
        this.lastCommand = c.n;
        untracked(() => this.run(c));
      },
    });
    // Keep the canvas in step with its container (side panel collapsing, window resize).
    // Refit only when the size really changes and the user has not moved the view: refitting on every observer callback
    // reset the user's pan and zoom.
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver((entries) => {
      const r = entries[entries.length - 1]?.contentRect;
      if (!r || (Math.round(r.width) === this.lastSize.w && Math.round(r.height) === this.lastSize.h)) return;
      this.lastSize = { w: Math.round(r.width), h: Math.round(r.height) };
      this.cy?.resize();
      if (!this.interacted) this.fitCapped();
    }) : null;
    afterNextRender(() => ro?.observe(this.el().nativeElement));
    inject(DestroyRef).onDestroy(() => { ro?.disconnect(); this.cy?.destroy(); });
  }

  /** Set once the user pans, zooms or moves a node: automatic fitting then stops. */
  private interacted = false;
  private lastSize = { w: -1, h: -1 };

  /** Zoom around the centre of the view (buttons and keys). */
  zoomBy(factor: number): void {
    const cy = this.cy;
    if (!cy) return;
    this.interacted = true;
    const z = Math.min(cy.maxZoom(), Math.max(cy.minZoom(), cy.zoom() * factor));
    cy.zoom({ level: z, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  }

  fitAll(): void {
    this.interacted = false;
    this.fitCapped();
  }

  key(e: KeyboardEvent): void {
    if (e.key === '+' || e.key === '=') { this.zoomBy(1.25); e.preventDefault(); }
    else if (e.key === '-' || e.key === '_') { this.zoomBy(0.8); e.preventDefault(); }
    else if (e.key === '0') { this.fitAll(); e.preventDefault(); }
  }

  private css(name: string): string {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
  }

  private elements(nodes: GraphNode[], edges: KeyedEdge[], root: string | null): ElementDefinition[] {
    const ids = new Set(nodes.map((n) => n.id));
    const widths = edgeWidths(edges);
    const flagged = new Set(nodes.filter((n) => n.riskLevel === 'HIGH' || n.riskLevel === 'CRITICAL').map((n) => n.id));
    return [
      ...nodes.map((n) => ({
        data: {
          id: n.id, rv: RISK_VAR[n.riskLevel], color: this.css(RISK_VAR[n.riskLevel]), labelled: !!n.label, contract: n.isContract === true, root: n.id === root,
          depth: n.depth,
          // short label at fit zoom (no overlap); full label on hover or selection
          short: n.id === root ? this.i18n.t('graph.canvas.rootShort') : n.label ? n.label : `…${n.address.slice(-4)}`,
          full: n.label ? `${n.label}\n${shortHash(n.address, 6, 4)}` : shortHash(n.address, 6, 4),
        },
      })),
      ...edges.filter(({ edge }) => ids.has(edge.source) && ids.has(edge.target)).map(({ key, edge }) => ({
        data: {
          id: key, source: edge.source, target: edge.target, w: widths.get(key) ?? 1, label: `${formatUnits(edge.rawAmount, edge.token.decimals, 2, this.i18n.lang())} ${edge.token.symbol}`,
          // direction relative to the root wallet: into it, out of it, or between two other wallets
          dir: edge.target === root ? 'in' : edge.source === root ? 'out' : 'hop',
          // the counterparty is scored high or critical (the root's own score is shown on the root): worth a look, not evidence of shared intent
          flag: edge.target === root ? flagged.has(edge.source) : edge.source === root ? flagged.has(edge.target) : flagged.has(edge.source) || flagged.has(edge.target),
        },
      })),
    ];
  }

  private sync(nodes: GraphNode[], edges: KeyedEdge[], root: string | null): void {
    const els = this.elements(nodes, edges, root);
    if (!this.cy || this.builtFor !== root) {
      this.cy?.destroy();
      this.builtFor = root;
      // panning, zooming and node dragging stated explicitly. Wheel: measured in Chrome, the default speed zooms about 5 % per
      // notch and the former 0.3 about 1.4 %, which reads as "zoom does not work"; 2 gives about 10 % per notch.
      this.cy = cytoscape({ container: this.el().nativeElement, elements: els, style: this.stylesheet(), minZoom: 0.1, maxZoom: 4, wheelSensitivity: 2,
        userPanningEnabled: true, userZoomingEnabled: true, autoungrabify: false, autounselectify: false, boxSelectionEnabled: false });
      this.cy.on('tap', 'node', (ev: EventObject) => { this.clearPath(); this.selected.emit({ kind: 'node', id: ev.target.id() }); });
      this.cy.on('tap', 'edge', (ev: EventObject) => { this.clearPath(); this.selected.emit({ kind: 'edge', id: ev.target.id() }); });
      this.cy.on('dbltap', 'node', (ev: EventObject) => this.expandRequested.emit(ev.target.id()));
      this.cy.on('mouseover', 'node, edge', (ev: EventObject) => ev.target.addClass('hover'));
      this.cy.on('mouseout', 'node, edge', (ev: EventObject) => ev.target.removeClass('hover'));
      this.cy.on('tap', (ev: EventObject) => { if (ev.target === this.cy) { this.clearPath(); this.selected.emit(null); } });
      this.interacted = false;
      this.cy.on('dragpan scrollzoom pinchzoom grab', () => (this.interacted = true));
      this.layout();
      return;
    }
    const existing = new Set(this.cy.elements().map((e) => e.id()));
    // the drawn graph must match the current data exactly: a narrower token, date range or depth removes elements
    const { add: added, removeIds } = graphDiff(existing, els);
    if (removeIds.length) {
      const gone = new Set(removeIds);
      this.cy.elements().filter((e) => gone.has(e.id())).remove();
    }
    // edge widths are relative to the whole graph: refresh them on existing edges too
    for (const e of els) if (existing.has(e.data.id!) && e.data['w'] !== undefined) this.cy.getElementById(e.data.id!).data('w', e.data['w']);
    if (added.length) this.cy.add(added);
    if (added.length || removeIds.length) this.layout();
  }

  private layout(): void {
    const cy = this.cy;
    if (!cy) return;
    // Concentric rings by hop distance from the root: the root sits in the centre, each hop is one ring further out.
    const maxDepth = Math.max(0, ...cy.nodes().map((n) => Number(n.data('depth')) || 0));
    cy.layout({
      name: 'concentric', animate: false, fit: false, startAngle: (3 / 2) * Math.PI, avoidOverlap: true, minNodeSpacing: 70, spacingFactor: 1.1,
      concentric: (n: NodeSingular) => (n.data('root') ? maxDepth + 2 : maxDepth + 1 - (Number(n.data('depth')) || 0)), levelWidth: () => 1,
    } as never).run();
    // The container may still be settling (deferred block, panel layout): measure and fit on the next frame,
    // and never zoom in past 1.2 so a small graph keeps readable label sizes.
    requestAnimationFrame(() => {
      if (this.cy !== cy) return;
      cy.resize();
      this.fitCapped();
    });
  }

  /** Fits the graph, but never zooms in past 1.3 so a small graph keeps readable labels. */
  private fitCapped(): void {
    const cy = this.cy;
    if (!cy || cy.elements().empty()) return;
    cy.fit(undefined, 64);
    if (cy.zoom() > 1.3) { cy.zoom(1.3); cy.center(); }
  }

  private clearPath(): void {
    this.cy?.elements().removeClass('path dim');
  }

  private run(c: GraphCommand): void {
    if (!this.cy) return;
    if (c.kind === 'fit') this.cy.animate({ fit: { eles: this.cy.elements(), padding: 40 } }, { duration: matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 200 });
    else if (c.kind === 'clear') this.clearPath();
    else if (c.kind === 'png') {
      const blob = this.cy.png({ full: true, scale: 2, bg: this.css('--surface'), output: 'blob' }) as unknown as Blob;
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = this.fileName();
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      this.exported.emit();
    } else if (c.kind === 'path' && c.hops?.length) {
      // a path computed by the server on the flows (directed, chronological): highlight its transfers as drawn
      this.clearPath();
      const cy = this.cy;
      const lit = cy.collection();
      for (const h of c.hops) {
        lit.merge(cy.getElementById(h.source)).merge(cy.getElementById(h.target));
        lit.merge(cy.edges().filter((e) => e.data('source') === h.source && e.data('target') === h.target));
      }
      cy.elements().addClass('dim');
      lit.removeClass('dim').addClass('path');
      const missing = c.hops.filter((h) => cy.getElementById(h.source).empty() || cy.getElementById(h.target).empty()).length;
      if (missing) this.pathResult.emit(missing);
    }
  }

  private stylesheet() {
    return [
      {
        selector: 'node',
        style: {
          'background-color': 'data(color)', label: 'data(short)', color: this.css('--ink-2'), 'font-size': 12, 'font-family': this.css('--mono'), 'min-zoomed-font-size': 6,
          'text-valign': 'bottom', 'text-margin-y': 5, 'text-wrap': 'wrap', width: 22, height: 22, 'border-width': 2, 'border-color': this.css('--surface'),
          'text-background-color': this.css('--surface'), 'text-background-opacity': 0.8, 'text-background-padding': '1px',
        },
      },
      { selector: 'node[?labelled]', style: { shape: 'diamond', width: 28, height: 28 } },
      { selector: 'node[?contract]', style: { shape: 'round-rectangle' } },
      { selector: 'node[?root]', style: { 'border-color': this.css('--brand'), 'border-width': 3, width: 34, height: 34, 'font-weight': 600 } },
      { selector: 'node[?root]', style: { color: this.css('--ink') } },
      { selector: 'node.hover, node:selected', style: { label: 'data(full)', color: this.css('--ink'), 'z-index': 20, 'text-background-opacity': 1, 'min-zoomed-font-size': 0 } },
      { selector: 'node:selected', style: { 'border-color': this.css('--focus'), 'border-width': 4, 'overlay-opacity': 0 } },
      {
        selector: 'edge',
        style: {
          width: 'data(w)', 'line-color': this.css('--line-strong'), 'line-opacity': 0.55, 'target-arrow-color': this.css('--line-strong'), 'target-arrow-shape': 'triangle', 'arrow-scale': 0.8,
          'curve-style': 'bezier', label: '', 'font-size': 11, color: this.css('--ink-2'), 'text-rotation': 'autorotate',
          'text-background-color': this.css('--surface'), 'text-background-opacity': 0.85, 'text-background-padding': '1px',
        },
      },
      { selector: 'edge[dir = "in"]', style: { 'line-color': this.css('--flow-in'), 'target-arrow-color': this.css('--flow-in'), 'line-opacity': 0.9 } },
      { selector: 'edge[dir = "out"]', style: { 'line-color': this.css('--flow-out'), 'target-arrow-color': this.css('--flow-out'), 'line-opacity': 0.9 } },
      { selector: 'edge[?flag]', style: { 'line-style': 'dashed', 'line-dash-pattern': [6, 4], 'line-color': this.css('--risk-high'), 'target-arrow-color': this.css('--risk-high'), 'line-opacity': 0.95 } },
      { selector: 'edge.hover, edge:selected', style: { label: 'data(label)', 'z-index': 20, 'text-background-opacity': 1, 'line-opacity': 1 } },
      { selector: 'edge:selected', style: { 'line-color': this.css('--focus'), 'target-arrow-color': this.css('--focus'), 'overlay-opacity': 0 } },
      { selector: '.path', style: { 'line-color': this.css('--accent'), 'target-arrow-color': this.css('--accent'), 'z-index': 10 } },
      { selector: 'node.path', style: { 'border-color': this.css('--accent'), 'border-width': 4 } },
      { selector: '.dim', style: { opacity: 0.2 } },
    ] as never;
  }
}
