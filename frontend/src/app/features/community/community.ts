import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormField, form, minLength, required, submit, validate } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { ApiError, apiErrorText, toApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { EvidenceLevel, Label, NETWORKS, NetworkId } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { I18nService, hasKey, type Key, type Lang } from '../../core/i18n/i18n.service';
import { fmtDate, fmtPercent } from '../../core/i18n/format';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { timeAgo, netLabel } from '../../shared/format';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { TableState } from '../../shared/table-state';
import { categoryName } from '../../shared/explain';
import { UI } from '../../shared/ui';

const EV_RANK: Record<EvidenceLevel, number> = { ANOMALY: 0, RISK_SIGNAL: 1, CONFIRMED_BY_SOURCE: 2 };
const CATEGORIES = ['scam-report', 'phishing', 'mixer', 'exchange', 'bridge', 'defi-protocol', 'gambling', 'other'];

interface ReportModel {
  address: string;
  network: NetworkId;
  category: string;
  description: string;
  reference: string;
}

function reliabilityKey(r: number): Key {
  return r >= 0.9 ? 'community.rel.high' : r >= 0.6 ? 'community.rel.mid' : 'community.rel.low';
}

@Component({
  selector: 'oci-community',
  imports: [FormField, PageHeader, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <oci-page-header [heading]="i18n.t('community.title')" [lede]="i18n.t('community.lede')" />

      <div class="split">
        <section class="card card--flush" aria-labelledby="h-labels">
          <div class="card-head"><h2 id="h-labels">{{ i18n.t('community.labels') }}</h2>
            <p class="status-line">
              <span><strong>{{ byEvidence().CONFIRMED_BY_SOURCE }}</strong> {{ i18n.t('community.confirmed', { n: byEvidence().CONFIRMED_BY_SOURCE }) }}</span>
              <span><strong>{{ byEvidence().RISK_SIGNAL }}</strong> {{ i18n.t('community.riskSignals', { n: byEvidence().RISK_SIGNAL }) }}</span>
              @if (pending()) { <span><strong>{{ pending() }}</strong> {{ i18n.t('community.pending', { n: pending() }) }}</span> }
            </p></div>
          <div class="toolbar">
            <mat-form-field class="grow">
              <mat-label>{{ i18n.t('community.filter') }}</mat-label>
              <input matInput type="search" [value]="t.filter()" (input)="t.filter.set($any($event.target).value)"  [placeholder]="i18n.t('community.filterPlaceholder')" />
            </mat-form-field>
            <mat-form-field class="netf">
              <mat-label>{{ i18n.t('community.network') }}</mat-label>
              <mat-select [value]="net()" (valueChange)="net.set($event)"><mat-option value="">{{ i18n.t('community.allNetworks') }}</mat-option>@for (n of networks; track n) { <mat-option [value]="n">{{ netLabel(n) }}</mat-option> }</mat-select>
            </mat-form-field>
            <span class="small muted count" aria-live="polite">{{ i18n.t('community.shown', { n: t.view().length }) }}</span>
          </div>
          <oci-state [loading]="labels.loading()" [error]="labels.error()" [hasData]="!!labels.value()" [empty]="t.view().length === 0" skeleton="table" [skeletonColumns]="[10, 80, 70, 50, 60, 50]"
            [emptyText]="i18n.t('community.empty')" (retry)="labels.reload()">
            <div class="table-wrap"><table mat-table [dataSource]="t.view()" multiTemplateDataRows [trackBy]="byId" matSort matSortActive="rel" matSortDirection="desc" matSortDisableClear (matSortChange)="t.onSort($event)">
              <ng-container matColumnDef="x">
                <th mat-header-cell *matHeaderCellDef scope="col" class="x"><span class="sr-only">{{ i18n.t('community.details') }}</span></th>
                <td mat-cell *matCellDef="let l" class="x"><button matIconButton class="sm expander" type="button" [attr.aria-expanded]="open() === l.id" [attr.aria-controls]="'ld-' + l.id" [attr.aria-label]="i18n.t('community.detailsOf', { name: l.name })" (click)="toggle(l.id)">
                  <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6"><path d="m6 4 4 4-4 4"/></svg></button></td>
              </ng-container>
              <ng-container matColumnDef="address">
                <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('community.address') }}</th>
                <td mat-cell *matCellDef="let l"><oci-addr [address]="l.address" [network]="l.network" /><div class="sub"><oci-net [value]="l.network" /></div></td>
              </ng-container>
              <ng-container matColumnDef="category">
                <th mat-header-cell *matHeaderCellDef scope="col" mat-sort-header="category" [sortActionDescription]="i18n.t('community.sortLabel')">{{ i18n.t('community.label') }}</th>
                <td mat-cell *matCellDef="let l"><span class="strong">{{ l.name }}</span><div class="sub"><span class="chip">{{ catName(l.category) }}</span></div></td>
              </ng-container>
              <ng-container matColumnDef="source">
                <th mat-header-cell *matHeaderCellDef scope="col" mat-sort-header="source" [sortActionDescription]="i18n.t('community.sortSource')">{{ i18n.t('community.source') }}</th>
                <td mat-cell *matCellDef="let l" class="small">{{ l.source }}</td>
              </ng-container>
              <ng-container matColumnDef="rel">
                <th mat-header-cell *matHeaderCellDef scope="col" class="right" mat-sort-header="rel" arrowPosition="before" [sortActionDescription]="i18n.t('community.sortReliability')">{{ i18n.t('community.reliability') }}</th>
                <td mat-cell *matCellDef="let l" class="right nowrap"><span class="rel" aria-hidden="true"><span [style.width.%]="l.reliability * 100"></span></span>{{ percent(l.reliability) }}</td>
              </ng-container>
              <ng-container matColumnDef="ev">
                <th mat-header-cell *matHeaderCellDef scope="col" mat-sort-header="ev" [sortActionDescription]="i18n.t('community.sortEvidence')">{{ i18n.t('community.evidence') }}</th>
                <td mat-cell *matCellDef="let l"><oci-evidence [value]="l.evidenceLevel" /></td>
              </ng-container>
              <ng-container matColumnDef="detail">
                <td mat-cell *matCellDef="let l" [attr.colspan]="cols.length" [id]="'ld-' + l.id">
                  @if (open() === l.id) {
                    <div class="detail">
                      <dl class="kv"><dt>{{ i18n.t('community.address') }}</dt><dd class="mono">{{ l.address }}</dd><dt>{{ i18n.t('community.labelId') }}</dt><dd class="mono">{{ l.id }}</dd><dt>{{ i18n.t('community.added') }}</dt><dd>{{ day(l.createdAt) }}, {{ ago(l.createdAt) }}</dd></dl>
                      <p class="ink-2">{{ i18n.t(relKey(l.reliability)) }}</p>
                    </div>
                  }
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="cols"></tr>
              <tr mat-row *matRowDef="let l; columns: cols"></tr>
              <tr mat-row *matRowDef="let l; columns: ['detail']" class="detail-row" [class.open]="open() === l.id"></tr>
            </table></div>
          </oci-state>
        </section>

        <div class="stack side">
          <section class="card" aria-labelledby="h-report">
            <h2 id="h-report">{{ i18n.t('community.report') }}</h2>
            <form class="stack" (submit)="$event.preventDefault(); send()" novalidate>
              <mat-form-field>
                <mat-label>{{ i18n.t('community.address') }}</mat-label>
                <input matInput class="mono" [formField]="report.address" autocomplete="off" spellcheck="false" [ociInvalid]="showErr(report.address)" />
                <mat-error>{{ errs(report.address).join(' ') }}</mat-error>
              </mat-form-field>
              <div class="pair">
                <mat-form-field>
                  <mat-label>{{ i18n.t('community.network') }}</mat-label>
                  <mat-select [formField]="report.network">@for (n of networks; track n) { <mat-option [value]="n">{{ netLabel(n) }}</mat-option> }</mat-select>
                </mat-form-field>
                <mat-form-field>
                  <mat-label>{{ i18n.t('community.category') }}</mat-label>
                  <mat-select [formField]="report.category">@for (c of categories; track c) { <mat-option [value]="c">{{ catName(c) }}</mat-option> }</mat-select>
                </mat-form-field>
              </div>
              <mat-form-field>
                <mat-label>{{ i18n.t('community.observed') }}</mat-label>
                <textarea matInput rows="4" [formField]="report.description" [placeholder]="i18n.t('community.observedPlaceholder')" [ociInvalid]="showErr(report.description)"></textarea>
                <mat-error>{{ errs(report.description).join(' ') }}</mat-error>
                <mat-hint>{{ i18n.t('community.observedHint', { n: model().description.trim().length }) }}</mat-hint>
              </mat-form-field>
              <mat-form-field>
                <mat-label>{{ i18n.t('community.reference') }}</mat-label>
                <input matInput [formField]="report.reference" [placeholder]="i18n.t('community.referencePlaceholder')" />
              </mat-form-field>
              <p class="small muted">{{ i18n.t('community.noPersonal') }}</p>
              @if (submitError(); as e) { <p class="field-error" role="alert">{{ apiError(e) }}</p> }
              <div><button matButton="filled" type="submit" [disabled]="busy()">{{ busy() ? i18n.t('community.submitting') : i18n.t('community.submit') }}</button></div>
            </form>
          </section>
          <section class="card" aria-labelledby="h-mine">
            <h2 id="h-mine">{{ i18n.t('community.mine') }}</h2>
            <oci-state [loading]="reports.loading()" [error]="reports.error()" [hasData]="!!reports.value()" [skeletonRows]="3" (retry)="reports.reload()">
              <ul class="reps">
                @for (r of reports.value()?.items ?? []; track r.id) {
                  <li><span class="status st-{{ repState(r.status) }}">{{ statusText(r.status) }}</span>
                    <span class="mono small" [attr.title]="r.address">{{ r.address.slice(0, 12) }}…</span><span class="small">{{ catName(r.category) }}</span><span class="small muted">{{ ago(r.createdAt) }}</span></li>
                } @empty { <li class="muted small">{{ i18n.t('community.mineEmpty') }}</li> }
              </ul>
            </oci-state>
          </section>
        </div>
      </div>
    </div>
  `,
  styles: `
    .card-head .status-line { flex-basis: 100%; }
    .rel { display: inline-block; width: 44px; height: 5px; border-radius: 3px; background: var(--well); box-shadow: var(--shadow-inset); vertical-align: middle; margin-right: 8px; overflow: hidden; }
    .rel span { display: block; height: 100%; background: var(--ink-2); }
    .pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .stack mat-form-field { width: 100%; }
    .netf { width: 180px; }
    .toolbar .grow { flex: 1 1 240px; }
    .reps { list-style: none; margin: 0; padding: 0; display: grid; }
    .reps li { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--line-soft); }
    .reps li:last-child { border-bottom: 0; }
  `,
})
export class CommunityPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);
  readonly networks = NETWORKS;
  readonly netLabel = netLabel;
  readonly categories = CATEGORIES;
  readonly net = signal<NetworkId | ''>('');
  readonly open = signal<string | null>(null);
  readonly cols = ['x', 'address', 'category', 'source', 'rel', 'ev'];
  readonly byId = (_: number, l: Label) => l.id;
  readonly busy = signal(false);
  readonly submitError = signal<ApiError | null>(null);
  readonly relKey = reliabilityKey;
  protected ago(iso: string | null | undefined): string { return timeAgo(iso, Date.now(), this.i18n.lang()); }
  protected day(iso: string): string { return fmtDate(iso, this.i18n.lang()); }
  protected percent(v: number): string { return fmtPercent(v, this.i18n.lang()); }
  /** Report categories have their own names; any other category goes through the shared category names. */
  protected catName(c: string): string { const k = `community.cat.${c}`; return hasKey(k) ? this.i18n.t(k) : categoryName(c, this.i18n.lang() as Lang); }
  protected statusText(s: string | undefined): string { const k = `community.status.${s ?? 'PENDING'}`; return hasKey(k) ? this.i18n.t(k) : (s ?? ''); }
  protected apiError(e: ApiError): string { return apiErrorText(e, this.i18n.lang()); }

  readonly labels = apiResource({ params: () => ({ network: this.net() }), stream: (p) => this.api.labels(p), keepPrevious: true });
  readonly reports = apiResource({ params: () => ({}), stream: () => this.api.labelReports() });

  readonly t = new TableState<Label>(
    computed(() => this.labels.value()?.items ?? []),
    { category: (l) => l.category, source: (l) => l.source, rel: (l) => l.reliability, ev: (l) => EV_RANK[l.evidenceLevel] },
    (l) => `${l.address} ${l.name} ${l.category} ${l.source}`,
    { key: 'rel', dir: 'desc' },
  );
  readonly byEvidence = computed(() => {
    const out: Record<EvidenceLevel, number> = { ANOMALY: 0, RISK_SIGNAL: 0, CONFIRMED_BY_SOURCE: 0 };
    for (const l of this.labels.value()?.items ?? []) out[l.evidenceLevel]++;
    return out;
  });
  readonly pending = computed(() => (this.reports.value()?.items ?? []).filter((r) => (r.status ?? 'PENDING') === 'PENDING').length);

  /** Report form (Signal Forms): the model is the single source of truth; rules derive validity from it. */
  readonly model = signal<ReportModel>({ address: '', network: 'ethereum', category: 'scam-report', description: '', reference: '' });
  readonly report = form(this.model, (s) => {
    required(s.address, { message: 'community.err.addressRequired' });
    validate(s.address, ({ value, valueOf }) => {
      const v = value().trim();
      if (!v) return undefined;
      const d = detectQuery(v);
      if (d.kind !== 'address') return { kind: 'format', message: 'community.err.addressFormat' };
      const n = valueOf(s.network);
      return d.candidates.includes(n) ? undefined : { kind: 'network', message: 'community.err.addressNetwork' };
    });
    required(s.description, { message: 'community.err.descriptionRequired' });
    minLength(s.description, 20, { message: 'community.err.descriptionShort' });
  });

  toggle(id: string): void {
    this.open.update((o) => (o === id ? null : id));
  }

  showErr(f: () => { touched: () => boolean; invalid: () => boolean }): boolean {
    return f().touched() && f().invalid();
  }

  errs(f: () => { touched: () => boolean; errors: () => readonly { message?: string }[] }): string[] {
    // validation messages are dictionary keys, so they follow a language switch
    return f().touched() ? f().errors().map((e) => (e.message && hasKey(e.message) ? this.i18n.t(e.message, { network: netLabel(this.model().network) }) : this.i18n.t('community.err.invalid'))) : [];
  }

  repState(s: string | undefined): string {
    return s === 'ACCEPTED' ? 'resolved' : s === 'REJECTED' ? 'false_positive' : 'acknowledged';
  }

  send(): void {
    void submit(this.report, async () => {
      const m = this.model();
      this.busy.set(true);
      this.submitError.set(null);
      try {
        const r = await firstValueFrom(this.api.reportLabel({
          address: detectQuery(m.address).normalized || m.address.trim(), network: m.network, category: m.category,
          description: m.description.trim(), reference: m.reference.trim(),
        }));
        this.reports.update((p) => ({ items: [r, ...(p?.items ?? [])], nextCursor: p?.nextCursor ?? null }));
        this.model.update((x) => ({ ...x, address: '', description: '', reference: '' }));
        this.report().reset();
        this.toasts.success(this.i18n.t('community.submitted'), this.i18n.t('community.submittedDetail'));
      } catch (e) {
        this.submitError.set(toApiError(e));
      } finally {
        this.busy.set(false);
      }
      return undefined;
    });
  }
}
