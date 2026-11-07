import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Alert } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { shortHash } from '../../shared/format';
import { UI, sentence } from '../../shared/ui';
import { compareScale, isoDuration, opSymbol, parseCondition, parseEventId, parseStep } from './evidence';

/** "Why this alert fired": engine evidence as readouts, condition outcomes, transactions and the subject wallet's score. */
@Component({
  selector: 'oci-alert-why',
  imports: [DatePipe, JsonPipe, RouterLink, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h3 id="h-why">Why this alert fired</h3>
    <p class="lead">Rule <span class="mono">{{ alert().ruleId }}</span>@if (alert().ruleVersion) { version {{ alert().ruleVersion }} } matched the values below.
      This is not a finding of wrongdoing: record your conclusion with the actions above.</p>

    <h4>Measured values</h4>
    @if (alert().evidence; as ev) {
      @if (ev.kind === 'AGGREGATION') {
        <div class="well measure">
          <div class="m-label">{{ aggLabel(ev.function, ev.field) }} per {{ ev.groupBy.join(', ') || 'rule' }}, over {{ dur(ev.window) }}</div>
          <dl class="m-grid">
            <div class="readout readout--lg"><dt>Observed</dt><dd>{{ ev.value }}</dd></div>
            <div class="readout"><dt>Threshold</dt><dd>{{ op(ev.operator) }} {{ ev.threshold }}</dd></div>
            <div class="readout"><dt>Window</dt><dd>{{ dur(ev.window) }}</dd></div>
            <div class="readout"><dt>Events in window</dt><dd>{{ ev.eventCount }}</dd></div>
          </dl>
          @if (scale(ev.value, ev.threshold); as sc) {
            <div class="cmp" aria-hidden="true"><span class="cmp-bar" [style.width.%]="sc.observed"></span><span class="cmp-thr" [style.left.%]="sc.threshold"></span></div>
            <p class="small muted">Bar: observed value. Line: threshold. Window {{ ev.windowStartExclusive | date: 'MMM d, HH:mm' : 'UTC' }} to {{ ev.windowEnd | date: 'MMM d, HH:mm' : 'UTC' }} UTC.</p>
          }
        </div>
      }
      @if (ev.kind === 'SEQUENCE') {
        <div class="well">
          <p class="small">Steps completed in order for <span class="mono">{{ ev.key }}</span>, within {{ dur(ev.within) }} (actual span {{ dur(ev.span) }}).</p>
          <ol class="steps">
            @for (st of steps(ev.steps); track $index) {
              <li><strong>{{ st.name }}</strong><span class="small muted">&#32;{{ st.at | date: 'MMM d, HH:mm:ss' : 'UTC' }} UTC</span><div class="mono small">{{ st.eventId }}</div></li>
            }
          </ol>
        </div>
      }
      @if (conditions().length) {
        <div class="table-wrap"><table class="cond">
          <caption class="sr-only">Rule conditions with observed values</caption>
          <thead><tr><th scope="col">Field</th><th scope="col">Condition</th><th scope="col" class="right">Observed</th><th scope="col">Result</th></tr></thead>
          <tbody>
            @for (c of conditions(); track $index) {
              <tr><td class="mono">{{ c.field }}</td><td class="nowrap">{{ op(c.op) }} {{ c.expected }}</td><td class="right strong">{{ c.actual }}</td>
                <td><span class="res" [class.met]="c.matched">{{ c.matched ? 'Met' : 'Not met' }}</span></td></tr>
            }
          </tbody>
        </table></div>
      }
    } @else {
      <p class="muted small">The server returned no engine evidence for this alert. The raw matched event below is all that was recorded.</p>
    }

    <h4>Contributing transactions</h4>
    @if (txs().length) {
      <ul class="txs">
        @for (x of txs(); track x.key) {
          <li><a class="mono" [routerLink]="['/explorer']" [queryParams]="{ q: x.hash, network: x.network }" [attr.title]="x.hash">{{ short(x.hash) }}</a>@if (x.logIndex) { <span class="small muted">log {{ x.logIndex }}</span> }</li>
        }
      </ul>
    } @else { <p class="muted small">No transaction is attached to this alert.</p> }

    @if (alert().address; as addr) {
      <h4>Subject wallet</h4>
      <p class="small"><oci-addr [address]="addr" [network]="alert().network" [full]="true" /></p>
      <oci-state [loading]="risk.loading()" [error]="risk.error()" [hasData]="!!risk.value()" skeleton="readout" [skeletonRows]="1" loadingText="Loading the wallet score" (retry)="risk.reload()">
        @if (risk.value(); as r) {
          <oci-gauge size="lg" [score]="r.score" [level]="r.level" label="Subject wallet risk score" />
          <table class="contrib">
            <caption class="sr-only">Largest contributions to the wallet score</caption>
            <thead><tr><th scope="col">Top contributions</th><th scope="col">Evidence</th><th scope="col" class="right">Points</th></tr></thead>
            <tbody>@for (c of top(); track c.factor) {
              <tr><td>{{ sentence(c.factor) }}<div class="sub">{{ c.source }}</div></td><td><oci-evidence [value]="c.evidenceLevel" /></td><td class="right strong">{{ c.points > 0 ? '+' : '' }}{{ c.points }}</td></tr>
            }</tbody>
          </table>
          <a class="small" [routerLink]="['/wallets', alert().network, addr]">Open the wallet to see every contribution</a>
        }
      </oci-state>
    }

    <details class="raw">
      <summary>Raw matched event</summary>
      <pre class="code">{{ alert().matchedEvent | json }}</pre>
    </details>
  `,
  styles: `
    :host { display: grid; gap: 12px; align-content: start; min-width: 0; }
    .lead { color: var(--muted); margin: 0; font-size: 12.5px; }
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
  readonly alert = input.required<Alert>();
  readonly dur = isoDuration;
  readonly op = opSymbol;
  readonly scale = compareScale;
  readonly sentence = sentence;
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
    const f = fn.toLowerCase().replace(/_/g, ' ');
    return field ? `${sentence(f)} of ${field}` : f === 'count' ? 'Number of matching events' : sentence(f);
  }
}
