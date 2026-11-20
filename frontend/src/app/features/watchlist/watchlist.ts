import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiError, apiErrorText, errorText, toApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { EnabledNetworks } from '../../core/enabled-networks';
import { I18nService } from '../../core/i18n/i18n.service';
import { fmtStamp } from '../../core/i18n/format';
import { NETWORKS, NetworkId, WatchlistEntry } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { netLabel, timeAgo } from '../../shared/format';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';

/**
 * The organisation's own wallets (deposit addresses, treasury, hot wallets). As soon as the list is not empty, the
 * behavioural rules of the pack (fund-splitting, suspicious-wallet, suspicious-approval) only consider these wallets
 * and their direct counterparties, instead of the whole chain.
 */
@Component({
  selector: 'oci-watchlist',
  imports: [PageHeader, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .grid { display: grid; grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr); gap: 16px; align-items: start; }
    @media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
    form { display: grid; gap: 4px; }
    .scope { margin-bottom: 16px; }
  `,
  template: `
    <div class="page">
      <oci-page-header [heading]="i18n.t('watchlist.title')" [lede]="i18n.t('watchlist.lede')" />

      <p class="note scope" data-testid="scope" [class.note--warn]="!active()">
        @if (active()) {
          {{ i18n.t('watchlist.scopeActive', { n: entries().length }) }}
        } @else {
          {{ i18n.t('watchlist.scopeEmpty') }}
        }
      </p>

      <div class="grid">
        <section class="card card--flush" aria-labelledby="h-list">
          <div class="card-head"><h2 id="h-list">{{ i18n.t('watchlist.watched') }}</h2></div>
          <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.value()" [empty]="entries().length === 0" skeleton="table"
            [skeletonColumns]="[80, 50, 40, 20]" [emptyText]="i18n.t('watchlist.empty')" (retry)="list.reload()">
            <div class="table-wrap"><table mat-table [dataSource]="entries()" [trackBy]="byId">
              <ng-container matColumnDef="address">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('watchlist.wallet') }}</th>
                <td mat-cell *matCellDef="let e"><oci-addr [address]="e.address" [network]="e.network" /><div class="sub"><oci-net [value]="e.network" /></div></td>
              </ng-container>
              <ng-container matColumnDef="name">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('watchlist.name') }}</th>
                <td mat-cell *matCellDef="let e">{{ e.name ?? '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="added">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('watchlist.added') }}</th>
                <td mat-cell *matCellDef="let e" class="small nowrap" [title]="stamp(e.createdAt)">{{ ago(e.createdAt) }}@if (e.createdBy) { <div class="sub">{{ i18n.t('watchlist.by', { name: e.createdBy }) }}</div> }</td>
              </ng-container>
              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef scope="col"><span class="sr-only">{{ i18n.t('watchlist.actions') }}</span></th>
                <td mat-cell *matCellDef="let e" class="right"><button matButton type="button" [disabled]="busy() === e.id" (click)="remove(e)" [attr.aria-label]="i18n.t('watchlist.stopWatching', { address: e.address })">{{ i18n.t('watchlist.remove') }}</button></td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="cols"></tr>
              <tr mat-row *matRowDef="let e; columns: cols"></tr>
            </table></div>
          </oci-state>
        </section>

        <section class="card" aria-labelledby="h-add">
          <h2 id="h-add">{{ i18n.t('watchlist.watchA') }}</h2>
          <form (submit)="$event.preventDefault(); add()" novalidate>
            <mat-form-field>
              <mat-label>{{ i18n.t('watchlist.address') }}</mat-label>
              <input matInput class="mono" autocomplete="off" spellcheck="false" [value]="address()" (input)="onAddress($any($event.target).value)" data-testid="address" />
            </mat-form-field>
            <mat-form-field>
              <mat-label>{{ i18n.t('watchlist.network') }}</mat-label>
              <mat-select [value]="network()" (valueChange)="network.set($event)">@for (n of networks(); track n) { <mat-option [value]="n">{{ netLabel(n) }}</mat-option> }</mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>{{ i18n.t('watchlist.nameOptional') }}</mat-label>
              <input matInput maxlength="120" [value]="name()" (input)="name.set($any($event.target).value)" [placeholder]="i18n.t('watchlist.namePlaceholder')" />
            </mat-form-field>
            @if (error(); as e) { <p class="field-error" role="alert">{{ apiError(e) }}</p> }
            <div><button matButton="filled" type="submit" [disabled]="busy() === 'add' || !address().trim()">{{ busy() === 'add' ? i18n.t('watchlist.adding') : i18n.t('watchlist.watch') }}</button></div>
          </form>
        </section>
      </div>
    </div>
  `,
})
export class WatchlistPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);
  private readonly enabled = inject(EnabledNetworks);

  readonly list = apiResource({ params: () => ({}), stream: () => this.api.watchlist() });
  readonly entries = computed<WatchlistEntry[]>(() => this.list.value() ?? []);
  readonly active = computed(() => this.entries().length > 0);
  readonly cols = ['address', 'name', 'added', 'actions'];
  readonly networks = computed(() => this.enabled.enabledCandidates(NETWORKS));

  readonly address = signal('');
  readonly network = signal<NetworkId>('tron');
  readonly name = signal('');
  readonly busy = signal<string | null>(null);
  readonly error = signal<ApiError | null>(null);

  readonly netLabel = netLabel;
  readonly byId = (_: number, e: WatchlistEntry) => e.id;
  ago(iso: string): string { return timeAgo(iso, Date.now(), this.i18n.lang()); }
  protected stamp(iso: string): string { return `${fmtStamp(iso, this.i18n.lang())} UTC`; }
  protected apiError(e: ApiError): string { return apiErrorText(e, this.i18n.lang()); }

  /** Picks the network from the address format when it is unambiguous among the enabled networks. */
  onAddress(v: string): void {
    this.address.set(v);
    const d = detectQuery(v.trim());
    if (d.kind === 'address') {
      const usable = this.enabled.enabledCandidates(d.candidates);
      if (usable.length >= 1) this.network.set(usable[0]);
    }
  }

  async add(): Promise<void> {
    this.busy.set('add');
    this.error.set(null);
    try {
      const e = await firstValueFrom(this.api.addToWatchlist(this.network(), this.address().trim(), this.name().trim() || null));
      this.list.update((l) => [...(l ?? []), e]);
      this.address.set('');
      this.name.set('');
      this.toasts.success(this.i18n.t('watchlist.toast.added'), e.name ?? e.address);
    } catch (err) {
      this.error.set(toApiError(err));
    } finally {
      this.busy.set(null);
    }
  }

  async remove(e: WatchlistEntry): Promise<void> {
    this.busy.set(e.id);
    try {
      await firstValueFrom(this.api.removeFromWatchlist(e.id));
      this.list.update((l) => (l ?? []).filter((x) => x.id !== e.id));
      this.toasts.success(this.i18n.t('watchlist.toast.removed'), e.name ?? e.address);
    } catch (err) {
      const a = toApiError(err);
      this.toasts.error(this.i18n.t('watchlist.toast.removeFailed'), errorText(a, this.i18n.lang()));
    } finally {
      this.busy.set(null);
    }
  }
}
