import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ApiError } from '../core/api-error';
import { EnabledNetworks } from '../core/enabled-networks';
import { NetworkId } from '../core/models';
import { detectQuery } from '../core/network-detect';
import { netLabel } from './format';

/**
 * True when the API refused the request because the network is not enabled on this instance:
 * 422 problem+json with a field error on "/network" (backend InvestigationService.network).
 */
export function isNetworkNotEnabled(e: ApiError | null | undefined): boolean {
  return !!e && e.status === 422 && e.fieldErrors.some((f) => f.field === '/network' || f.field === 'network');
}

/** "a", "a and b", "a, b and c". */
export function listOf(items: readonly string[]): string {
  return items.length <= 1 ? (items[0] ?? '') : `${items.slice(0, -1).join(', ')} and ${items.at(-1)}`;
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
      <strong>{{ label(network()) }} is not indexed by this instance</strong>
      <span>@if (enabled(); as e) { @if (e.length) { This instance indexes only {{ enabledText() }}. } @else { This instance indexes no network yet. } }
        @else { Its operator has not enabled this network. }</span>
      <div class="acts">
        @if (alternative(); as alt) {
          <a matButton="filled" [routerLink]="['/', base(), alt, address()]">Open on {{ label(alt) }}</a>
        }
        <a matButton="outlined" [routerLink]="searchLink()">Search another address</a>
      </div>
    </div>
  `,
  styles: `.acts { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 4px; }`,
})
export class NetworkNotEnabled {
  private readonly nets = inject(EnabledNetworks);
  readonly network = input.required<NetworkId>();
  readonly address = input.required<string>();
  /** Route prefix of the page: the alternative opens the same page on another network. */
  readonly base = input<'wallets' | 'risk'>('wallets');
  readonly searchLink = input('/explorer');
  readonly label = netLabel;
  readonly enabled = this.nets.enabled;
  readonly enabledText = computed(() => listOf((this.enabled() ?? []).map(netLabel)));
  /** First enabled network the address format fits, other than the refused one (only once the enabled list is known). */
  readonly alternative = computed<NetworkId | null>(() => {
    if (!this.enabled()) return null;
    return this.nets.enabledCandidates(detectQuery(this.address()).candidates).find((n) => n !== this.network()) ?? null;
  });
}
