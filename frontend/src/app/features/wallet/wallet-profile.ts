import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { fmtDate, fmtNumber } from '../../core/i18n/format';
import { I18nService } from '../../core/i18n/i18n.service';
import { WalletAnalysis } from '../../core/models';
import { formatUnits } from '../../shared/format';

/** Balances, lifetime flows, 30-day activity and score history. Rendered in a deferred block. */
@Component({
  selector: 'oci-wallet-profile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cols">
      <section aria-labelledby="h-bal">
        <h3 id="h-bal">{{ i18n.t('wallet.profile.balances') }}</h3>
        <table class="mini"><tbody>@for (b of a().balances; track b.token.symbol) {
          <tr><th scope="row">{{ b.token.symbol }}</th><td class="right strong">{{ units(b.rawAmount, b.token.decimals, 6) }}</td></tr>
        }</tbody></table>
        @if (a().balances[0]; as b0) { <p class="small muted">{{ i18n.t('wallet.profile.atBlock', { block: num(b0.atBlock), date: firstSeen() }) }}</p> }
      </section>
      <section aria-labelledby="h-flow">
        <h3 id="h-flow">{{ i18n.t('wallet.profile.flows') }}</h3>
        <table class="mini">
          <thead><tr><th scope="col">{{ i18n.t('wallet.profile.token') }}</th><th scope="col" class="right">{{ i18n.t('wallet.tx.in') }}</th><th scope="col" class="right">{{ i18n.t('wallet.tx.out') }}</th></tr></thead>
          <tbody>@for (f of a().flows; track f.token.symbol) {
            <tr><th scope="row">{{ f.token.symbol }}</th><td class="right">{{ units(f.rawIn, f.token.decimals, 2) }}</td><td class="right">{{ units(f.rawOut, f.token.decimals, 2) }}</td></tr>
          }</tbody>
        </table>
      </section>
      <section aria-labelledby="h-act">
        <h3 id="h-act">{{ i18n.t('wallet.profile.activity') }}</h3>
        <div class="chart">
          <svg viewBox="0 0 300 80" preserveAspectRatio="none" role="img" [attr.aria-label]="i18n.t('wallet.profile.chart', { max: actMax() })">
            @for (d of a().activity; track d.day; let i = $index) {
              <rect [attr.x]="i * 10 + 1" width="4" [attr.y]="80 - (d.txIn / actMax()) * 76" [attr.height]="(d.txIn / actMax()) * 76" class="in-bar"><title>{{ i18n.t('wallet.profile.dayIn', { day: d.day, n: d.txIn }) }}</title></rect>
              <rect [attr.x]="i * 10 + 5" width="4" [attr.y]="80 - (d.txOut / actMax()) * 76" [attr.height]="(d.txOut / actMax()) * 76" class="out-bar"><title>{{ i18n.t('wallet.profile.dayOut', { day: d.day, n: d.txOut }) }}</title></rect>
            }
          </svg>
        </div>
        <div class="row small legend"><span class="sw in-bg"></span>{{ i18n.t('wallet.profile.incoming') }} <span class="sw out-bg"></span>{{ i18n.t('wallet.profile.outgoing') }}</div>
      </section>
      <section aria-labelledby="h-rh">
        <h3 id="h-rh">{{ i18n.t('wallet.profile.history') }}</h3>
        @if (a().risk?.history?.length) {
          <div class="chart">
            <svg viewBox="0 0 300 80" preserveAspectRatio="none" role="img" [attr.aria-label]="i18n.t('wallet.profile.historyChart')">
              @for (t of [35, 65, 85]; track t) { <line x1="0" x2="300" [attr.y1]="80 - t * 0.8" [attr.y2]="80 - t * 0.8" class="thr-line" /> }
              <polyline [attr.points]="riskLine()" class="risk-line" />
            </svg>
          </div>
          <p class="small muted">{{ i18n.t('wallet.profile.thresholds') }}</p>
        } @else { <p class="muted">{{ i18n.t('wallet.profile.noHistory') }}</p> }
      </section>
    </div>
  `,
  styles: `
    .cols { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr)); gap: 28px 40px; }
    section { display: grid; gap: 10px; align-content: start; min-width: 0; }
    p { margin: 0; }
    .mini th[scope='row'] { font-weight: 500; }
    .chart { padding: 4px 0 0; }
    svg { width: 100%; height: 100px; display: block; }
    .in-bar { fill: var(--ink-2); } .out-bar { fill: var(--accent); }
    .in-bg { background: var(--ink-2); } .out-bg { background: var(--accent); }
    .legend { color: var(--ink-2); }
    .risk-line { fill: none; stroke: var(--ink); stroke-width: 1.75; vector-effect: non-scaling-stroke; }
    .thr-line { stroke: var(--muted); opacity: 0.6; stroke-dasharray: 2 3; vector-effect: non-scaling-stroke; }
  `,
})
export class WalletProfile {
  readonly a = input.required<WalletAnalysis>();
  protected readonly i18n = inject(I18nService);
  readonly units = (raw: string, decimals: number, max = 6) => formatUnits(raw, decimals, max, this.i18n.lang());
  readonly num = (n: number) => fmtNumber(n, this.i18n.lang());
  readonly firstSeen = computed(() => { const f = this.a().firstSeen; return f ? fmtDate(f, this.i18n.lang()) : '–'; });
  readonly actMax = computed(() => Math.max(1, ...this.a().activity.flatMap((d) => [d.txIn, d.txOut])));
  readonly riskLine = computed(() => {
    const h = this.a().risk?.history ?? [];
    return h.map((p, i) => `${(i / Math.max(1, h.length - 1)) * 300},${80 - (p.score / 100) * 80}`).join(' ');
  });
}
