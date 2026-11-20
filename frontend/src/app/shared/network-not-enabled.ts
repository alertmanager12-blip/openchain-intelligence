import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ApiError } from '../core/api-error';
import { EnabledNetworks } from '../core/enabled-networks';
import { NetworkId } from '../core/models';
import { detectQuery } from '../core/network-detect';
import { netLabel } from './format';
import { I18nService } from '../core/i18n/i18n.service';
import { fmtList } from '../core/i18n/format';
import type { Lang } from '../core/i18n/i18n.service';

/**
 * True when the API refused the request because the network is not enabled on this instance:
 * 422 problem+json with a field error on "/network" (backend InvestigationService.network).
 */
export function isNetworkNotEnabled(e: ApiError | null | undefined): boolean {
  return !!e && e.status === 422 && e.fieldErrors.some((f) => f.field === '/network' || f.field === 'network');
}

/** "a", "a and b", "a, b and c" (French: "a, b et c"). */
export function listOf(items: readonly string[], lang: Lang = 'en'): string {
  return fmtList(items, lang);
}

/**
 * Empty state shown in place of the raw 422 when a wallet is opened on a network this instance does not index:
 * which networks it does index, and where the same address can be opened instead.
 */
@Component({
  selector: 'oci-network-not-enabled',
  imports: [RouterLink, MatButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty nne" role="status">
      <strong>{{ i18n.t('nne.title', { network: label(network()) }) }}</strong>
      <span>@if (enabled(); as e) { @if (e.length) { {{ i18n.t('nne.only', { list: enabledText() }) }} } @else { {{ i18n.t('nne.none') }} } }
        @else { {{ i18n.t('nne.unknown') }} }</span>
      <div class="acts">
        @if (alternative(); as alt) {
          <a matButton="filled" [routerLink]="['/', base(), alt, address()]">{{ i18n.t('nne.openOn', { network: label(alt) }) }}</a>
        }
        <a matButton="outlined" [routerLink]="searchLink()">{{ i18n.t('nne.search') }}</a>
      </div>
    </div>
  `,
  styles: `.acts { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 4px; }`,
})
export class NetworkNotEnabled {
  private readonly nets = inject(EnabledNetworks);
  protected readonly i18n = inject(I18nService);
  readonly network = input.required<NetworkId>();
  readonly address = input.required<string>();
  /** Route prefix of the page: the alternative opens the same page on another network. */
  readonly base = input<'wallets' | 'risk'>('wallets');
  readonly searchLink = input('/explorer');
  readonly label = netLabel;
  readonly enabled = this.nets.enabled;
  readonly enabledText = computed(() => listOf((this.enabled() ?? []).map(netLabel), this.i18n.lang()));
  /** First enabled network the address format fits, other than the refused one (only once the enabled list is known). */
  readonly alternative = computed<NetworkId | null>(() => {
    if (!this.enabled()) return null;
    return this.nets.enabledCandidates(detectQuery(this.address()).candidates).find((n) => n !== this.network()) ?? null;
  });
}
