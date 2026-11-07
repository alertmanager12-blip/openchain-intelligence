import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { GraphEdge, GraphNode } from '../../core/models';
import { formatUnits, shortHash } from '../../shared/format';
import { UI } from '../../shared/ui';

/** Details of the selected wallet or transfer, with the actions available on it. */
@Component({
  selector: 'oci-graph-inspector',
  imports: [RouterLink, DatePipe, MatButton, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-live': 'polite' },
  template: `
    @if (node(); as n) {
      <div class="head"><h2>{{ n.label ?? 'Wallet' }}</h2><oci-risk [value]="n.riskLevel" /></div>
      <p class="addr"><span class="mono">{{ n.address }}</span><oci-copy [value]="n.address" label="Copy address" /></p>
      <dl class="kv">
        <dt>Hops from root</dt><dd>{{ n.depth === 0 ? 'Root' : n.depth }}</dd>
        <dt>Type</dt><dd>{{ n.isContract ? 'Smart contract' : 'Wallet' }}</dd>
        <dt>Label</dt><dd>{{ n.label ?? 'None' }}</dd>
      </dl>
      <div class="acts">
        <button matButton="filled" class="sm" type="button" (click)="expand.emit(n)" [disabled]="expanding()">{{ expanding() ? 'Expanding…' : 'Expand counterparties' }}</button>
        @if (n.depth > 0) { <button matButton="outlined" class="sm" type="button" (click)="highlight.emit(n)">Highlight path from root</button> }
        <a matButton="outlined" class="sm" [routerLink]="['/wallets', n.network, n.address]">Open wallet</a>
      </div>
      @if (pathInfo()) { <p class="small ink-2" role="status">{{ pathInfo() }}</p> }
      <p class="small muted">A connection to a flagged wallet does not mean shared intent.</p>
    } @else if (edge(); as e) {
      <div class="head"><h2>Transfer</h2></div>
      <div class="well readout"><span class="label">Amount</span><span class="value">{{ units(e.rawAmount, e.token.decimals) }}<span class="unit">{{ e.token.symbol }}</span></span></div>
      <dl class="kv">
        <dt>From</dt><dd>@if (from(); as f) { <oci-addr [address]="f.address" [network]="f.network" /> @if (f.label) { <span class="small muted">{{ f.label }}</span> } }</dd>
        <dt>To</dt><dd>@if (to(); as t) { <oci-addr [address]="t.address" [network]="t.network" /> @if (t.label) { <span class="small muted">{{ t.label }}</span> } }</dd>
        <dt>When</dt><dd>{{ e.timestamp | date: 'yyyy-MM-dd HH:mm' : 'UTC' }} UTC</dd>
        <dt>Transaction</dt><dd><a class="mono" [routerLink]="['/explorer']" [queryParams]="{ q: e.txHash, network: e.token.network }" [attr.title]="e.txHash">{{ short(e.txHash) }}</a></dd>
      </dl>
    } @else {
      <h2>Inspector</h2>
      <p class="ink-2">Select a wallet or a transfer in the graph to see its details here.</p>
      <ul class="tips small muted">
        <li>Double-click a wallet to load its own counterparties.</li>
        <li>Zoom in to read addresses and amounts.</li>
        <li>Drag the background to pan.</li>
      </ul>
    }
  `,
  styles: `
    :host { display: grid; gap: 14px; align-content: start; min-width: 0; }
    .head { display: flex; gap: 10px; align-items: center; justify-content: space-between; flex-wrap: wrap; }
    .head h2 { font-size: 18px; }
    .addr { display: flex; gap: 6px; align-items: center; margin: 0; min-width: 0; }
    .addr .mono { min-width: 0; }
    .acts { display: grid; gap: 8px; }
    .tips { margin: 0; padding-left: 18px; display: grid; gap: 4px; }
  `,
})
export class GraphInspector {
  readonly node = input<GraphNode | null>(null);
  readonly edge = input<GraphEdge | null>(null);
  readonly from = input<GraphNode | null>(null);
  readonly to = input<GraphNode | null>(null);
  readonly expanding = input(false);
  readonly pathInfo = input<string | null>(null);
  readonly expand = output<GraphNode>();
  readonly highlight = output<GraphNode>();
  readonly units = formatUnits;
  readonly short = (h: string) => shortHash(h, 10, 8);
}
