import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ApiError, errorText, fieldLine, statusTitle } from '../../core/api-error';
import { I18nService, Key, Params } from '../../core/i18n/i18n.service';
import { GraphEdge, GraphNode } from '../../core/models';
import { EngineText } from '../../shared/engine-text';
import { formatUnits, shortHash } from '../../shared/format';
import { UI } from '../../shared/ui';

/** The line under the actions about a path: a console sentence (key and parameters, so it follows the language), a server note kept in English, or an error. */
export interface PathInfo { key?: Key; params?: Params; engine?: string; error?: ApiError }

/** Details of the selected wallet or transfer, with the actions available on it. */
@Component({
  selector: 'oci-graph-inspector',
  imports: [RouterLink, DatePipe, MatButton, EngineText, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-live': 'polite' },
  template: `
    @if (node(); as n) {
      <div class="head"><h2>{{ n.label ?? i18n.t('graph.insp.wallet') }}</h2><oci-risk [value]="n.riskLevel" /></div>
      <p class="addr"><span class="mono">{{ n.address }}</span><oci-copy [value]="n.address" labelKey="state.copyAddress" /></p>
      <dl class="kv">
        <dt>{{ i18n.t('graph.insp.hops') }}</dt><dd>{{ n.depth === 0 ? i18n.t('graph.insp.root') : n.depth }}</dd>
        <dt>{{ i18n.t('graph.insp.type') }}</dt><dd>{{ i18n.t(n.isContract ? 'graph.insp.contract' : 'graph.insp.wallet') }}</dd>
        <dt>{{ i18n.t('graph.insp.label') }}</dt><dd>{{ n.label ?? i18n.t('graph.insp.none') }}</dd>
      </dl>
      <div class="acts">
        <button matButton="filled" class="sm" type="button" (click)="expand.emit(n)" [disabled]="expanding()">{{ i18n.t(expanding() ? 'graph.insp.expanding' : 'graph.insp.expand') }}</button>
        @if (n.depth > 0) { <button matButton="outlined" class="sm" type="button" (click)="highlight.emit(n)">{{ i18n.t('graph.insp.highlight') }}</button> }
        <a matButton="outlined" class="sm" [routerLink]="['/wallets', n.network, n.address]">{{ i18n.t('graph.insp.open') }}</a>
      </div>
      @if (pathInfo(); as p) {
        <p class="small ink-2" role="status">@if (p.error) { {{ errLine(p.error) }} } @else {
          @if (p.key) { {{ i18n.t(p.key, p.params) }} } @if (p.engine) { <oci-engine-text [text]="p.engine" [plainInEnglish]="true" /> } }</p>
      }
      <p class="small muted">{{ i18n.t('graph.insp.intent') }}</p>
    } @else if (edge(); as e) {
      <div class="head"><h2>{{ i18n.t('graph.insp.transfer') }}</h2></div>
      <div class="well readout"><span class="label">{{ i18n.t('graph.insp.amount') }}</span><span class="value">{{ units(e.rawAmount, e.token.decimals) }}<span class="unit">{{ e.token.symbol }}</span></span></div>
      <dl class="kv">
        <dt>{{ i18n.t('graph.insp.from') }}</dt><dd>@if (from(); as f) { <oci-addr [address]="f.address" [network]="f.network" /> @if (f.label) { <span class="small muted">{{ f.label }}</span> } }</dd>
        <dt>{{ i18n.t('graph.insp.to') }}</dt><dd>@if (to(); as t) { <oci-addr [address]="t.address" [network]="t.network" /> @if (t.label) { <span class="small muted">{{ t.label }}</span> } }</dd>
        <dt>{{ i18n.t('graph.insp.when') }}</dt><dd>{{ e.timestamp | date: 'yyyy-MM-dd HH:mm' : 'UTC' }} UTC</dd>
        <dt>{{ i18n.t('graph.insp.tx') }}</dt><dd><a class="mono" [routerLink]="['/explorer']" [queryParams]="{ q: e.txHash, network: e.token.network }" [attr.title]="e.txHash">{{ short(e.txHash) }}</a></dd>
      </dl>
    } @else {
      <h2>{{ i18n.t('graph.insp.title') }}</h2>
      <p class="ink-2">{{ i18n.t('graph.insp.select') }}</p>
      <ul class="tips small muted">
        <li>{{ i18n.t('graph.insp.tip1') }}</li>
        <li>{{ i18n.t('graph.insp.tip2') }}</li>
        <li>{{ i18n.t('graph.insp.tip3') }}</li>
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
  readonly pathInfo = input<PathInfo | null>(null);
  readonly expand = output<GraphNode>();
  readonly highlight = output<GraphNode>();
  protected readonly i18n = inject(I18nService);
  readonly units = (raw: string | null | undefined, decimals: number) => formatUnits(raw, decimals, 6, this.i18n.lang());
  /** "Title: message" in the console's language (French sets U+202F before the colon). */
  readonly errLine = (e: ApiError) => fieldLine(statusTitle(e, this.i18n.lang()), errorText(e, this.i18n.lang()), this.i18n.lang());
  readonly short = (h: string) => shortHash(h, 10, 8);
}
