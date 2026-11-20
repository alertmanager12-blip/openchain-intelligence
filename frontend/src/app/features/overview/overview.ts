import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { I18nService, Key, Params } from '../../core/i18n/i18n.service';
import { fmtDuration, fmtNumber, fmtRelative } from '../../core/i18n/format';
import { NetworkId, NetworkStatus } from '../../core/models';
import { apiResource } from '../../shared/api-resource';
import { ruleText } from '../../shared/rule-text';
import { networkCoverageSentence } from '../../shared/coverage-text';
import { netLabel } from '../../shared/format';
import { MAT_BUTTONS } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { sanctionsSentence } from '../../shared/sanctions-text';
import { UI } from '../../shared/ui';
import { VerdictPanel } from '../../shared/verdict-panel';
import { attention, emptyState } from './attention';
import { DELAY_WARN_MS, serverNow } from './reliability';
import { ReliabilityBand } from './reliability-band';

/**
 * The overview, "watch post" (spec 2026-11-19, direction A): first whether monitoring can be trusted right now, then the
 * most severe open alerts as verdicts, then a compact side column (last 24 hours, one line per network). Every word follows
 * the console language.
 */
@Component({
  selector: 'oci-overview',
  imports: [RouterLink, PageHeader, ReliabilityBand, VerdictPanel, ...MAT_BUTTONS, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class OverviewPage {
  private readonly api = inject(ApiService);
  protected readonly i18n = inject(I18nService);
  /** One call: GET /dashboard. */
  readonly dash = apiResource({ params: () => ({}), stream: () => this.api.dashboard() });
  /** Official sanctions lists in force (ADR-0027): the band and every verdict's coverage. */
  readonly sanctions = apiResource({ params: () => ({}), stream: () => this.api.sanctionsLists() });
  /** undefined while loading or failed: the band then says "unknown", never a pass. */
  readonly sanctionsList = computed(() => this.sanctions.value()?.items[0] ?? null);

  readonly needs = computed(() => { const d = this.dash.value(); return d ? attention(d, this.i18n.lang()) : []; });
  readonly empty = computed(() => { const d = this.dash.value(); return d ? emptyState(d, this.i18n.lang()) : { kind: 'none-open' as const, text: '' }; });
  readonly openAlerts = computed(() => this.dash.value()?.totals.openAlerts ?? null);
  readonly last24h = computed(() => {
    const l = this.dash.value()?.alerts?.last24h;
    if (!l) return null;
    const max = Math.max(1, ...l.byRule.map((r) => r.count));
    return { total: l.total, rules: l.byRule.map((r) => ({ ...r, name: ruleText({ name: r.ruleName, translations: r.translations }, this.i18n.lang()).name, pct: (r.count / max) * 100 })) };
  });
  readonly nets = computed(() => (this.dash.value()?.networks ?? []).filter((n) => n.enabled !== false));

  t(key: Key, params?: Params): string { return this.i18n.t(key, params); }
  n(v: number): string { return fmtNumber(v, this.i18n.lang()); }
  ago(iso: string): string { return fmtRelative(iso, this.i18n.lang()); }
  readonly netLabel = netLabel;

  coverageOf(n: NetworkId): string {
    const s = this.dash.value()?.networks.find((x) => x.network === n);
    // an unknown network coverage stays unknown ('' shows "Coverage unknown"): the sanctions sentence completes a known one
    const lang = this.i18n.lang();
    return s ? `${networkCoverageSentence(s, lang)} ${sanctionsSentence(this.sanctionsList() ?? undefined, lang)}` : '';
  }

  /** One network line: health and delay as time (blocks when the time of the last block is unknown). */
  netLine(x: NetworkStatus): { text: string; warn: boolean } {
    const lang = this.i18n.lang(), fr = lang === 'fr';
    if (!x.healthy || x.indexerLag === null || x.indexerLag === undefined) return { text: this.t('overview.net.unknown'), warn: true };
    if (x.simulated) return { text: this.t('overview.net.blocks', { n: x.indexerLag }), warn: false };
    if (!x.lastIndexedBlockTime) return { text: this.t('overview.net.blocks', { n: x.indexerLag }), warn: true };
    const d = this.dash.value();
    const ms = Math.max(0, (d ? serverNow(d) : Date.now()) - Date.parse(x.lastIndexedBlockTime));
    return { text: `${fmtDuration(ms, lang)} ${fr ? 'de retard' : 'behind'}`, warn: ms > DELAY_WARN_MS };
  }
}
