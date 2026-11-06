import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FormField, form, minLength, required, submit, validate } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { ApiError, toApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { EvidenceLevel, Label, NETWORKS, NetworkId } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { timeAgo } from '../../shared/format';
import { PageHeader } from '../../shared/page-header';
import { TableState } from '../../shared/table-state';
import { UI, sentence } from '../../shared/ui';

const EV_RANK: Record<EvidenceLevel, number> = { ANOMALY: 0, RISK_SIGNAL: 1, CONFIRMED_BY_SOURCE: 2 };
const CATEGORIES = ['scam-report', 'phishing', 'mixer', 'exchange', 'bridge', 'defi-protocol', 'gambling', 'other'];

interface ReportModel {
  address: string;
  network: NetworkId;
  category: string;
  description: string;
  reference: string;
}

function reliabilityText(r: number): string {
  if (r >= 0.9) return 'Highly reliable source, such as an official list. Weighs fully in scores.';
  if (r >= 0.6) return 'Generally reliable source. Weighs in scores, with less weight than official lists.';
  return 'Low reliability, for example an unreviewed community report. Treat as a lead, not a fact.';
}

@Component({
  selector: 'oci-community',
  imports: [FormsModule, FormField, DatePipe, PageHeader, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <oci-page-header heading="Community labels" lede="Labels come from sources of different reliability. Community reports are reviewed before they influence any score, and a label describes an address, never a person." />

      <div class="split">
        <section class="card card--flush" aria-labelledby="h-labels">
          <div class="card-head"><h2 id="h-labels">Labels</h2>
            <p class="status-line">
              <span><strong>{{ byEvidence().CONFIRMED_BY_SOURCE }}</strong> confirmed by a cited source</span>
              <span><strong>{{ byEvidence().RISK_SIGNAL }}</strong> risk signals, a pattern rather than proof</span>
              @if (pending()) { <span><strong>{{ pending() }}</strong> of your reports waiting for review</span> }
            </p></div>
          <div class="toolbar">
            <label class="grow">Filter<input type="search" [ngModel]="t.filter()" (ngModelChange)="t.filter.set($event)" placeholder="Address, name, category or source" /></label>
            <label>Network<select [ngModel]="net()" (ngModelChange)="net.set($event)"><option value="">All networks</option>@for (n of networks; track n) { <option [value]="n" class="cap">{{ n }}</option> }</select></label>
            <span class="small muted count" aria-live="polite">{{ t.view().length }} shown</span>
          </div>
          <oci-state [loading]="labels.loading()" [error]="labels.error()" [hasData]="!!labels.value()" [empty]="t.view().length === 0" skeleton="table" [skeletonColumns]="[10, 80, 70, 50, 60, 50]"
            emptyText="No label matches. Clear the filter or choose all networks." (retry)="labels.reload()">
            <div class="table-wrap"><table>
              <thead><tr>
                <th scope="col" class="x"><span class="sr-only">Details</span></th>
                <th scope="col">Address</th>
                <th scope="col" [attr.aria-sort]="t.aria('category')"><button type="button" (click)="t.sort('category')">Label {{ t.icon('category') }}</button></th>
                <th scope="col" [attr.aria-sort]="t.aria('source')"><button type="button" (click)="t.sort('source')">Source {{ t.icon('source') }}</button></th>
                <th scope="col" class="right" [attr.aria-sort]="t.aria('rel')"><button type="button" (click)="t.sort('rel')">Reliability {{ t.icon('rel') }}</button></th>
                <th scope="col" [attr.aria-sort]="t.aria('ev')"><button type="button" (click)="t.sort('ev')">Evidence {{ t.icon('ev') }}</button></th>
              </tr></thead>
              <tbody>
                @for (l of t.view(); track l.id) {
                  <tr>
                    <td class="x"><button class="expander" type="button" [attr.aria-expanded]="open() === l.id" [attr.aria-controls]="'ld-' + l.id" [attr.aria-label]="'Details of ' + l.name" (click)="toggle(l.id)">
                      <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6"><path d="m6 4 4 4-4 4"/></svg></button></td>
                    <td><oci-addr [address]="l.address" [network]="l.network" /><div class="sub"><oci-net [value]="l.network" /></div></td>
                    <td><span class="strong">{{ l.name }}</span><div class="sub"><span class="chip">{{ l.category }}</span></div></td>
                    <td class="small">{{ l.source }}</td>
                    <td class="right nowrap"><span class="rel" aria-hidden="true"><span [style.width.%]="l.reliability * 100"></span></span>{{ pct(l.reliability) }}%</td>
                    <td><oci-evidence [value]="l.evidenceLevel" /></td>
                  </tr>
                  @if (open() === l.id) {
                    <tr class="detail-row" [id]="'ld-' + l.id"><td colspan="6">
                      <div class="detail">
                        <dl class="kv"><dt>Address</dt><dd class="mono">{{ l.address }}</dd><dt>Label id</dt><dd class="mono">{{ l.id }}</dd><dt>Added</dt><dd>{{ l.createdAt | date: 'yyyy-MM-dd' : 'UTC' }}, {{ ago(l.createdAt) }}</dd></dl>
                        <p class="ink-2">{{ relText(l.reliability) }}</p>
                      </div>
                    </td></tr>
                  }
                }
              </tbody>
            </table></div>
          </oci-state>
        </section>

        <div class="stack side">
          <section class="card" aria-labelledby="h-report">
            <h2 id="h-report">Report an address</h2>
            <form class="stack" (submit)="$event.preventDefault(); send()" novalidate>
              <label>Address<input class="mono" [formField]="report.address" autocomplete="off" spellcheck="false" [attr.aria-invalid]="showErr(report.address) || null" />
                @for (e of errs(report.address); track $index) { <span class="field-error">{{ e }}</span> }</label>
              <div class="pair">
                <label>Network<select [formField]="report.network">@for (n of networks; track n) { <option [value]="n">{{ n }}</option> }</select></label>
                <label>Category<select [formField]="report.category">@for (c of categories; track c) { <option [value]="c">{{ c }}</option> }</select></label>
              </div>
              <label>What did you observe?<textarea rows="4" [formField]="report.description" placeholder="Facts only: what happened, when, which transactions." [attr.aria-invalid]="showErr(report.description) || null"></textarea>
                @for (e of errs(report.description); track $index) { <span class="field-error">{{ e }}</span> } @empty { <span class="hint">At least 20 characters. {{ model().description.trim().length }} so far.</span> }</label>
              <label>Reference<input [formField]="report.reference" placeholder="URL, transaction hash or case id" /></label>
              <p class="small muted">Do not include personal data about individuals. Reports are reviewed and may be rejected.</p>
              @if (submitError(); as e) { <p class="field-error" role="alert">{{ e.title }}: {{ e.detail }}</p> }
              <div><button class="btn btn--primary" type="submit" [disabled]="busy()">{{ busy() ? 'Submitting…' : 'Submit report' }}</button></div>
            </form>
          </section>
          <section class="card" aria-labelledby="h-mine">
            <h2 id="h-mine">My reports</h2>
            <oci-state [loading]="reports.loading()" [error]="reports.error()" [hasData]="!!reports.value()" [skeletonRows]="3" (retry)="reports.reload()">
              <ul class="reps">
                @for (r of reports.value()?.items ?? []; track r.id) {
                  <li><span class="status st-{{ repState(r.status) }}">{{ sentence(r.status ?? 'PENDING') }}</span>
                    <span class="mono small" [attr.title]="r.address">{{ r.address.slice(0, 12) }}…</span><span class="small">{{ r.category }}</span><span class="small muted">{{ ago(r.createdAt) }}</span></li>
                } @empty { <li class="muted small">You have not reported an address yet.</li> }
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
    .reps { list-style: none; margin: 0; padding: 0; display: grid; }
    .reps li { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--line-soft); }
    .reps li:last-child { border-bottom: 0; }
    textarea { resize: vertical; }
  `,
})
export class CommunityPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  readonly networks = NETWORKS;
  readonly categories = CATEGORIES;
  readonly net = signal<NetworkId | ''>('');
  readonly open = signal<string | null>(null);
  readonly busy = signal(false);
  readonly submitError = signal<ApiError | null>(null);
  readonly ago = timeAgo;
  readonly sentence = sentence;
  readonly relText = reliabilityText;
  readonly pct = (v: number) => (v * 100).toFixed(0);

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
    required(s.address, { message: 'Enter the address you are reporting.' });
    validate(s.address, ({ value, valueOf }) => {
      const v = value().trim();
      if (!v) return undefined;
      const d = detectQuery(v);
      if (d.kind !== 'address') return { kind: 'format', message: 'This is not a recognised address format.' };
      const n = valueOf(s.network);
      return d.candidates.includes(n) ? undefined : { kind: 'network', message: `This address format does not match ${n}.` };
    });
    required(s.description, { message: 'Describe what you observed.' });
    minLength(s.description, 20, { message: 'Write at least 20 characters so a reviewer can check it.' });
  });

  toggle(id: string): void {
    this.open.update((o) => (o === id ? null : id));
  }

  showErr(f: () => { touched: () => boolean; invalid: () => boolean }): boolean {
    return f().touched() && f().invalid();
  }

  errs(f: () => { touched: () => boolean; errors: () => readonly { message?: string }[] }): string[] {
    return f().touched() ? f().errors().map((e) => e.message ?? 'Invalid value') : [];
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
        this.toasts.success('Report submitted', 'It stays pending until a reviewer accepts or rejects it.');
      } catch (e) {
        this.submitError.set(toApiError(e));
      } finally {
        this.busy.set(false);
      }
      return undefined;
    });
  }
}
