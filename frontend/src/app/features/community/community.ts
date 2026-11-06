import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { EvidenceLevel, Label, LabelReport, NETWORKS, NetworkId, Page } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { timeAgo } from '../../shared/format';
import { TableState, query } from '../../shared/query';
import { UI, sentence } from '../../shared/ui';

const EV_RANK: Record<EvidenceLevel, number> = { ANOMALY: 0, RISK_SIGNAL: 1, CONFIRMED_BY_SOURCE: 2 };
const CATEGORIES = ['scam-report', 'phishing', 'mixer', 'exchange', 'bridge', 'defi-protocol', 'gambling', 'other'];

@Component({
  selector: 'oci-community',
  imports: [FormsModule, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head"><div>
        <h1>Community labels</h1>
        <p>Labels come from sources of different reliability. Community reports are reviewed before they influence any score, and a label describes an address, never a person.</p>
      </div></div>

      <div class="grid layout">
        <section class="card card--flush">
          <h2>Labels</h2>
          <div class="toolbar">
            <label class="grow">Filter<input type="search" [ngModel]="t.filter()" (ngModelChange)="t.filter.set($event)" placeholder="address, name, category, source" /></label>
            <label>Network<select [ngModel]="net()" (ngModelChange)="net.set($event); reload()"><option value="">All</option>@for (n of networks; track n) { <option [value]="n">{{ n }}</option> }</select></label>
          </div>
          <oci-state [loading]="labels.loading()" [error]="labels.error()" [hasData]="!!labels.data()" [empty]="t.view().length === 0" emptyText="No label matches. Clear the filter or choose all networks." (retry)="labels.retry()">
            <div class="table-wrap"><table>
              <thead><tr>
                <th scope="col">Address</th>
                <th scope="col" [attr.aria-sort]="t.aria('category')"><button type="button" (click)="t.sort('category')">Label {{ t.icon('category') }}</button></th>
                <th scope="col" [attr.aria-sort]="t.aria('source')"><button type="button" (click)="t.sort('source')">Source {{ t.icon('source') }}</button></th>
                <th scope="col" class="right" [attr.aria-sort]="t.aria('rel')"><button type="button" (click)="t.sort('rel')">Reliability {{ t.icon('rel') }}</button></th>
                <th scope="col" [attr.aria-sort]="t.aria('ev')"><button type="button" (click)="t.sort('ev')">Evidence {{ t.icon('ev') }}</button></th>
              </tr></thead>
              <tbody>@for (l of t.view(); track l.id) {
                <tr>
                  <td><oci-addr [address]="l.address" [network]="l.network" /><div><oci-net [value]="l.network" /></div></td>
                  <td><span class="chip">{{ l.category }}</span><div class="small">{{ l.name }}</div></td>
                  <td class="small">{{ l.source }}</td>
                  <td class="right nowrap"><span class="rel" aria-hidden="true"><span [style.width.%]="l.reliability * 100"></span></span> {{ (l.reliability * 100).toFixed(0) }}%</td>
                  <td><oci-evidence [value]="l.evidenceLevel" /></td>
                </tr>
              }</tbody>
            </table></div>
          </oci-state>
        </section>

        <div class="stack">
          <section class="card">
            <h2>Report an address</h2>
            <form class="stack" (submit)="$event.preventDefault(); submit()">
              <label>Address<input name="addr" class="mono" [ngModel]="addr()" (ngModelChange)="addr.set($event)" required /></label>
              <div class="row">
                <label>Network<select name="net" [ngModel]="rnet()" (ngModelChange)="rnet.set($event)">@for (n of networks; track n) { <option [value]="n">{{ n }}</option> }</select></label>
                <label>Category<select name="cat" [ngModel]="cat()" (ngModelChange)="cat.set($event)">@for (c of categories; track c) { <option [value]="c">{{ c }}</option> }</select></label>
              </div>
              @if (netMismatch()) { <p class="field-error">This address format does not match {{ rnet() }}.</p> }
              <label>What did you observe?<textarea name="desc" rows="4" [ngModel]="desc()" (ngModelChange)="desc.set($event)" placeholder="Facts only: what happened, when, which transactions. At least 20 characters."></textarea></label>
              <label>Reference (URL, tx hash, case id)<input name="ref" [ngModel]="ref()" (ngModelChange)="ref.set($event)" /></label>
              <p class="small muted">Do not include personal data about individuals. Reports are reviewed and may be rejected.</p>
              @if (submitError(); as e) { <p class="field-error" role="alert">{{ e.title }}: {{ e.detail }}</p> }
              @if (submitted()) { <p class="note" role="status">Report received. It stays pending until a reviewer accepts or rejects it.</p> }
              <div><button class="btn btn--primary" type="submit" [disabled]="busy() || !addr().trim() || desc().trim().length < 20 || netMismatch()">Submit report</button></div>
            </form>
          </section>
          <section class="card">
            <h2>My reports</h2>
            @for (r of reports.data()?.items ?? []; track r.id) {
              <p class="small rep"><span class="chip">{{ sentence(r.status ?? 'PENDING') }}</span> <span class="mono">{{ r.address.slice(0, 14) }}…</span> <span>{{ r.category }}</span> <span class="muted">{{ ago(r.createdAt) }}</span></p>
            } @empty { <p class="small muted">You have not reported an address yet.</p> }
          </section>
        </div>
      </div>
    </div>
  `,
  styles: `
    .layout { grid-template-columns: minmax(0, 1.7fr) minmax(300px, 1fr); align-items: start; }
    @media (max-width: 1000px) { .layout { grid-template-columns: minmax(0, 1fr); } }
    .grow { flex: 1; min-width: 200px; }
    .rel { display: inline-block; width: 48px; height: 4px; background: var(--line-soft); vertical-align: middle; margin-right: 4px; }
    .rel span { display: block; height: 100%; background: var(--ink-2); }
    .rep { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
  `,
})
export class CommunityPage {
  private readonly api = inject(ApiService);
  readonly networks = NETWORKS;
  readonly categories = CATEGORIES;
  readonly labels = query<Page<Label>>();
  readonly reports = query<Page<LabelReport>>();
  readonly net = signal<NetworkId | ''>('');
  readonly addr = signal('');
  readonly rnet = signal<NetworkId>('ethereum');
  readonly cat = signal('scam-report');
  readonly desc = signal('');
  readonly ref = signal('');
  readonly busy = signal(false);
  readonly submitted = signal(false);
  readonly submitError = signal<ApiError | null>(null);
  readonly ago = timeAgo;
  readonly sentence = sentence;
  readonly netMismatch = computed(() => {
    const d = detectQuery(this.addr());
    return !!this.addr().trim() && d.kind === 'address' && !d.candidates.includes(this.rnet());
  });

  readonly t = new TableState<Label>(
    computed(() => this.labels.data()?.items ?? []),
    { category: (l) => l.category, source: (l) => l.source, rel: (l) => l.reliability, ev: (l) => EV_RANK[l.evidenceLevel] },
    (l) => `${l.address} ${l.name} ${l.category} ${l.source}`,
    { key: 'rel', dir: 'desc' },
  );

  constructor() {
    this.reload();
    this.reports.run(() => this.api.labelReports());
  }

  reload(): void {
    this.labels.run(() => this.api.labels({ network: this.net() }));
  }

  submit(): void {
    const d = detectQuery(this.addr());
    this.busy.set(true);
    this.submitError.set(null);
    this.submitted.set(false);
    this.api.reportLabel({ address: d.normalized || this.addr().trim(), network: this.rnet(), category: this.cat(), description: this.desc().trim(), reference: this.ref().trim() }).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.submitted.set(true);
        this.desc.set('');
        this.ref.set('');
        this.reports.data.update((p) => ({ items: [r, ...(p?.items ?? [])], nextCursor: p?.nextCursor ?? null }));
      },
      error: (e: ApiError) => {
        this.busy.set(false);
        this.submitError.set(e);
      },
    });
  }
}
