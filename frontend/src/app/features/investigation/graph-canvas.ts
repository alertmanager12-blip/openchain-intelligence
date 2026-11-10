import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, afterRenderEffect, inject, input, output, untracked, viewChild } from '@angular/core';
import cytoscape, { Core, ElementDefinition, EventObject, NodeSingular } from 'cytoscape';
import { GraphNode } from '../../core/models';
import { formatUnits, shortHash } from '../../shared/format';
import { GraphCommand, GraphSelection, KeyedEdge, RISK_VAR, edgeWidths } from './graph-model';

/**
 * Cytoscape canvas. Rendered inside a @defer block by the page, so cytoscape (~400 kB) loads only when a graph
 * is shown. All communication goes through inputs (data, theme, commands) and outputs (selection, expand, path).
 */
@Component({
  selector: 'oci-graph-canvas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div #el class="cy" role="img" [attr.aria-label]="'Fund flow graph with ' + nodes().length + ' wallets and ' + edges().length + ' transfers. The selected element is described in the inspector.'"></div>`,
  styles: `:host { display: block; } .cy { width: 100%; height: 100%; min-height: 420px; }`,
})
export class GraphCanvas {
  readonly nodes = input.required<GraphNode[]>();
  readonly edges = input.required<KeyedEdge[]>();
  readonly root = input<string | null>(null);
  /** Effective theme; Cytoscape draws on canvas and cannot read CSS variables itself, so it is restyled on change. */
  readonly theme = input<'light' | 'dark'>('light');
  readonly command = input<GraphCommand | null>(null);
  readonly fileName = input('fund-flow.png');

  readonly selected = output<GraphSelection>();
  readonly expandRequested = output<string>();
  readonly pathResult = output<string>();
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
    afterRenderEffect({
      write: () => {
        const c = this.command();
        if (!c || c.n === this.lastCommand) return;
        this.lastCommand = c.n;
        untracked(() => this.run(c));
      },
    });
    // Keep the canvas in step with its container (side panel collapsing, window resize).
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => { this.cy?.resize(); if (!this.interacted) this.fitCapped(); }) : null;
    afterNextRender(() => ro?.observe(this.el().nativeElement));
    inject(DestroyRef).onDestroy(() => { ro?.disconnect(); this.cy?.destroy(); });
  }

  /** Set once the user pans or zooms: automatic fitting then stops. */
  private interacted = false;

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
          short: n.id === root ? 'Root' : n.label ? n.label : `…${n.address.slice(-4)}`,
          full: n.label ? `${n.label}\n${shortHash(n.address, 6, 4)}` : shortHash(n.address, 6, 4),
        },
      })),
      ...edges.filter(({ edge }) => ids.has(edge.source) && ids.has(edge.target)).map(({ key, edge }) => ({
        data: {
          id: key, source: edge.source, target: edge.target, w: widths.get(key) ?? 1, label: `${formatUnits(edge.rawAmount, edge.token.decimals, 2)} ${edge.token.symbol}`,
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
      this.cy = cytoscape({ container: this.el().nativeElement, elements: els, style: this.stylesheet(), wheelSensitivity: 0.3, minZoom: 0.15, maxZoom: 3 });
      this.cy.on('tap', 'node', (ev: EventObject) => { this.clearPath(); this.selected.emit({ kind: 'node', id: ev.target.id() }); });
      this.cy.on('tap', 'edge', (ev: EventObject) => { this.clearPath(); this.selected.emit({ kind: 'edge', id: ev.target.id() }); });
      this.cy.on('dbltap', 'node', (ev: EventObject) => this.expandRequested.emit(ev.target.id()));
      this.cy.on('mouseover', 'node, edge', (ev: EventObject) => ev.target.addClass('hover'));
      this.cy.on('mouseout', 'node, edge', (ev: EventObject) => ev.target.removeClass('hover'));
      this.cy.on('tap', (ev: EventObject) => { if (ev.target === this.cy) { this.clearPath(); this.selected.emit(null); } });
      this.interacted = false;
      this.cy.on('dragpan scrollzoom pinchzoom', () => (this.interacted = true));
      this.layout();
      return;
    }
    const existing = new Set(this.cy.elements().map((e) => e.id()));
    const added = els.filter((e) => !existing.has(e.data.id!));
    // edge widths are relative to the whole graph: refresh them on existing edges too
    for (const e of els) if (existing.has(e.data.id!) && e.data['w'] !== undefined) this.cy.getElementById(e.data.id!).data('w', e.data['w']);
    if (added.length) {
      this.cy.add(added);
      this.layout();
    }
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
    } else if (c.kind === 'path' && c.target && this.root()) {
      this.clearPath();
      const res = this.cy.elements().aStar({ root: `#${CSS.escape(this.root()!)}`, goal: `#${CSS.escape(c.target)}`, directed: false });
      if (!res.found) {
        this.pathResult.emit('No path to the root within the loaded graph.');
        return;
      }
      this.cy.elements().addClass('dim');
      res.path.removeClass('dim').addClass('path');
      const hops = res.path.edges().length;
      this.pathResult.emit(`${hops} ${hops === 1 ? 'hop' : 'hops'} from the root, direction ignored.`);
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
      { selector: 'node[?root]', style: { 'border-color': this.css('--ink'), 'border-width': 3, width: 34, height: 34, 'font-weight': 600 } },
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
