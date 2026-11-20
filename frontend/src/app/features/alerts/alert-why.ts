import { JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Alert } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { shortHash } from '../../shared/format';
import { contributionSummary, contributionTitle } from '../../shared/explain';
import { UI, sentence } from '../../shared/ui';
import { EngineText } from '../../shared/engine-text';
import { alertSentence } from '../../shared/alert-sentence';
import { fmtNumber } from '../../core/i18n/format';
import { I18nService, hasKey, type Key, type Params } from '../../core/i18n/i18n.service';
import { fmtStamp } from '../../core/i18n/format';
import { compareScale, isoDuration, opSymbol, parseCondition, parseEventId, parseStep } from './evidence';

/** "Why this alert fired": engine evidence as readouts, condition outcomes, transactions and the subject wallet's score. */
@Component({
  selector: 'oci-alert-why',
  imports: [JsonPipe, RouterLink, EngineText, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h3 id="h-why">{{ t('alerts.why.title') }}</h3>
    @if (alert().evidenceSummary) {
      @if (english()) { <p class="why-first"><oci-engine-text [text]="sentence()" /></p> } @else { <p class="why-first">{{ sentence() }}</p> }
      @if (original(); as o) { <oci-engine-text class="small" mode="collapsed" [text]="o" /> }
    }
    <dl class="subject">
      <div><dt>{{ t('alerts.why.wallet') }}</dt><dd>@if (alert().address; as addr) { <oci-addr [address]="addr" [network]="alert().network" [full]="true" /> } @else { <span class="muted">{{ t('alerts.why.notRecorded') }}</span> }</dd></div>
      @if (alert().counterparty; as cp) { <div><dt>{{ t('alerts.why.counterparty') }}</dt><dd><oci-addr [address]="cp" [network]="alert().network" [full]="true" /></dd></div> }
      @if (alert().txHash; as tx) { <div><dt>{{ t('alerts.why.transaction') }}</dt><dd><a class="mono tx" [routerLink]="['/explorer']" [queryParams]="{ q: tx, network: alert().network }" [attr.title]="tx">{{ short(tx) }}</a></dd></div> }
      @if (alert().blockNumber !== null && alert().blockNumber !== undefined) { <div><dt>{{ t('alerts.why.block') }}</dt><dd class="mono">{{ num(alert().blockNumber!) }}</dd></div> }
    </dl>
    <p class="lead">{{ t('alerts.why.rule') }} <span class="mono">{{ alert().ruleId }}</span>@if (alert().ruleVersion) { {{ t('alerts.why.version', { version: alert().ruleVersion! }) }} } {{ t('alerts.why.matched') }}
      {{ t('alerts.why.notFinding') }}</p>

    <h4>{{ t('alerts.why.measured') }}</h4>
    @if (alert().evidence; as ev) {
      @if (ev.kind === 'AGGREGATION') {
        <div class="well measure">
          <div class="m-label">{{ t('alerts.why.aggLine', { what: aggLabel(ev.function, ev.field), group: ev.groupBy.join(', ') || t('alerts.why.ruleGroup'), window: dur(ev.window) }) }}</div>
          <dl class="m-grid">
            <div class="readout readout--lg"><dt>{{ t('alerts.why.observed') }}</dt><dd>{{ ev.value }}</dd></div>
            <div class="readout"><dt>{{ t('alerts.why.threshold') }}</dt><dd>{{ op(ev.operator) }} {{ ev.threshold }}</dd></div>
            <div class="readout"><dt>{{ t('alerts.why.window') }}</dt><dd>{{ dur(ev.window) }}</dd></div>
            <div class="readout"><dt>{{ t('alerts.why.eventsInWindow') }}</dt><dd>{{ ev.eventCount }}</dd></div>
          </dl>
          @if (scale(ev.value, ev.threshold); as sc) {
            <div class="cmp" aria-hidden="true"><span class="cmp-bar" [style.width.%]="sc.observed"></span><span class="cmp-thr" [style.left.%]="sc.threshold"></span></div>
            <p class="small muted">{{ t('alerts.why.barNote', { from: stamp(ev.windowStartExclusive), to: stamp(ev.windowEnd) }) }}</p>
          }
        </div>
      }
      @if (ev.kind === 'SEQUENCE') {
        <div class="well">
          <p class="small">{{ t('alerts.why.steps', { key: ev.key, within: dur(ev.within), span: dur(ev.span) }) }}</p>
          <ol class="steps">
            @for (st of steps(ev.steps); track $index) {
              <li><strong>{{ st.name }}</strong><span class="small muted">&#32;{{ stamp(st.at, true) }} UTC</span><div class="mono small">{{ st.eventId }}</div></li>
            }
          </ol>
        </div>
      }
      @if (conditions().length) {
        <div class="table-wrap"><table class="cond">
          <caption class="sr-only">{{ t('alerts.why.condCaption') }}</caption>
          <thead><tr><th scope="col">{{ t('alerts.why.field') }}</th><th scope="col">{{ t('alerts.why.condition') }}</th><th scope="col" class="right">{{ t('alerts.why.observed') }}</th><th scope="col">{{ t('alerts.why.result') }}</th></tr></thead>
          <tbody>
            @for (c of conditions(); track $index) {
              <tr><td class="mono">{{ c.field }}</td><td class="nowrap">{{ op(c.op) }} {{ c.expected }}</td><td class="right strong">{{ c.actual }}</td>
                <td><span class="res" [class.met]="c.matched">{{ t(c.matched ? 'alerts.why.met' : 'alerts.why.notMet') }}</span></td></tr>
            }
          </tbody>
        </table></div>
      }
    } @else {
      <p class="muted small">{{ t('alerts.why.noEvidence') }}</p>
    }

    <h4>{{ t('alerts.why.txs') }}</h4>
    @if (txs().length) {
      <ul class="txs">
        @for (x of txs(); track x.key) {
          <li><a class="mono" [routerLink]="['/explorer']" [queryParams]="{ q: x.hash, network: x.network }" [attr.title]="x.hash">{{ short(x.hash) }}</a>@if (x.logIndex) { <span class="small muted">{{ t('alerts.why.log', { n: x.logIndex }) }}</span> }</li>
        }
      </ul>
    } @else { <p class="muted small">{{ t('alerts.why.noTx') }}</p> }

    @if (alert().address; as addr) {
      <h4>{{ t('alerts.why.score') }}</h4>
      <oci-state [loading]="risk.loading()" [error]="risk.error()" [hasData]="!!risk.value()" skeleton="readout" [skeletonRows]="1" [loadingText]="t('alerts.why.scoreLoading')" (retry)="risk.reload()">
        @if (risk.value(); as r) {
          <oci-gauge size="lg" [score]="r.score" [level]="r.level" [label]="t('alerts.why.gauge')" />
          <table class="contrib">
            <caption class="sr-only">{{ t('alerts.why.contribCaption') }}</caption>
            <thead><tr><th scope="col">{{ t('alerts.why.topContrib') }}</th><th scope="col">{{ t('alerts.why.evidence') }}</th><th scope="col" class="right">{{ t('alerts.why.points') }}</th></tr></thead>
            <tbody>@for (c of top(); track c.factor) {
              <tr><td><span class="strong">{{ cTitle(c) }}</span>@if (cSummary(c); as sm) { <div class="sub c-summary">@if (fr()) { <oci-engine-text [text]="sm" /> } @else { {{ sm }} }</div> }
                <oci-tech><span>{{ t('alerts.why.factor', { factor: c.factor, source: c.source }) }}</span>@if (c.explanation) { <span class="mono">{{ c.explanation }}</span> }</oci-tech></td>
                <td><oci-evidence [value]="c.evidenceLevel" /></td><td class="right strong">{{ c.points > 0 ? '+' : '' }}{{ c.points }}</td></tr>
            }</tbody>
          </table>
          <a class="small" [routerLink]="['/wallets', alert().network, addr]">{{ t('alerts.why.openWallet') }}</a>
        }
      </oci-state>
    }

    <details class="raw">
      <summary>{{ t('alerts.why.raw') }}</summary>
      <pre class="code">{{ alert().matchedEvent | json }}</pre>
    </details>
  `,
  styles: `
    :host { display: grid; gap: 12px; align-content: start; min-width: 0; }
    .lead { color: var(--muted); margin: 0; font-size: 12.5px; }
    .why-first { margin: 0; font-size: 14px; font-weight: 500; color: var(--ink); overflow-wrap: anywhere; }
    .subject { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 8px 20px; margin: 0; font-size: 12.5px; }
    .subject div { min-width: 0; }
    .subject dt { color: var(--muted); font-size: 11.5px; }
    .subject dd { margin: 2px 0 0; overflow-wrap: anywhere; }
    .c-summary { color: var(--ink-2); }
    h3 { font-size: 13px; margin-top: 8px; }
    h4 { margin: 14px 0 0; font-size: 12px; color: var(--muted); font-weight: 500; }
    .measure { display: grid; gap: 10px; padding: 16px 18px; }
    .m-label { font-size: 12.5px; color: var(--ink-2); }
    .m-grid { display: grid; grid-template-columns: 1.4fr repeat(3, minmax(0, 1fr)); gap: 12px 20px; margin: 0; align-items: end; }
    @media (max-width: 640px) { .m-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    .cmp { position: relative; height: 6px; border-radius: 3px; background: var(--line); margin: 6px 0 0; }
    .cmp-bar { position: absolute; inset: 0 auto 0 0; background: var(--ink-2); border-radius: 3px; }
    .cmp-thr { position: absolute; top: -4px; bottom: -4px; width: 2px; margin-left: -1px; border-radius: 1px; background: var(--risk-high); }
    .measure p { margin: 0; }
    .res { color: var(--muted); }
    .res.met { color: var(--ink); font-weight: 500; }
    .cond td.mono { font-size: 12px; }
    .steps { margin: 0; padding-left: 20px; display: grid; gap: 6px; font-size: 13px; }
    .txs { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 4px 16px; font-size: 13px; }
    .txs li { display: flex; gap: 8px; align-items: baseline; }
    .raw summary { cursor: pointer; font-size: 12.5px; color: var(--muted); padding: 4px 0; }
    .raw pre { margin-top: 8px; }
  `,
})
export class AlertWhy {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18nService);
  readonly alert = input.required<Alert>();
  /** Reads the language signal, so a switch re-renders the section. */
  readonly t = (k: Key, p?: Params) => this.i18n.t(k, p);
  readonly fr = computed(() => this.i18n.lang() === 'fr');
  readonly dur = (d: string | null | undefined) => isoDuration(d, this.i18n.lang());
  readonly op = (o: string) => opSymbol(o, this.i18n.lang());
  readonly num = (n: number) => fmtNumber(n, this.i18n.lang(), 0);
  readonly stamp = (iso: string, seconds = false) => fmtStamp(iso, this.i18n.lang(), seconds);
  readonly scale = compareScale;
  /** The alert's sentence in the console's language; in French the engine's own wording stays one click away. */
  readonly sentence = computed(() => alertSentence(this.alert(), this.i18n.lang()));
  private readonly engine = computed(() => this.alert().evidenceSummary ?? this.alert().summary);
  readonly english = computed(() => this.fr() && this.sentence() === this.engine());
  readonly original = computed(() => (this.fr() && this.sentence() !== this.engine() ? this.engine() : null));
  readonly cTitle = (c: Parameters<typeof contributionTitle>[0]) => contributionTitle(c, this.i18n.lang());
  readonly cSummary = contributionSummary;
  readonly short = (h: string) => shortHash(h, 10, 8);

  /** Score of the alert's subject wallet (GET /wallets/{a}/risk). */
  readonly risk = apiResource({
    params: () => { const a = this.alert(); return a.address ? { address: a.address, network: a.network } : undefined; },
    stream: (p) => this.api.walletRisk(p.address, p.network),
  });
  readonly top = computed(() => [...(this.risk.value()?.contributions ?? [])].sort((x, y) => Math.abs(y.points) - Math.abs(x.points)).slice(0, 3));
  readonly conditions = computed(() => (this.alert().evidence?.conditions ?? []).map(parseCondition).filter((c) => c !== null));

  /** Transactions behind the alert: matched event ids, plus the alert's own tx hash. */
  readonly txs = computed(() => {
    const a = this.alert();
    const out = new Map<string, { key: string; network: Alert['network']; hash: string; logIndex: string }>();
    for (const id of a.matchedEventIds ?? []) {
      const p = parseEventId(id);
      if (p) out.set(id, { key: id, ...p });
    }
    if (a.txHash && ![...out.values()].some((x) => x.hash === a.txHash)) out.set(a.txHash, { key: a.txHash, network: a.network, hash: a.txHash, logIndex: '' });
    return [...out.values()];
  });

  steps(s: string[]) {
    return s.map(parseStep);
  }

  aggLabel(fn: string, field?: string): string {
    const key = `alerts.fn.${fn.toUpperCase()}`;
    const name = hasKey(key) ? this.i18n.t(key) : sentence(fn.toLowerCase().replace(/_/g, ' '));
    if (field) return this.i18n.t('alerts.fn.of', { fn: name, field });
    return fn.toUpperCase() === 'COUNT' ? this.i18n.t('alerts.fn.countAll') : name;
  }
}
