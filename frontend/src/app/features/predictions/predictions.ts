import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ApiError, errorText, statusTitle } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { EnabledNetworks } from '../../core/enabled-networks';
import { fmtList, fmtPercent, LOCALE } from '../../core/i18n/format';
import { hasKey, I18nService, Lang, translate } from '../../core/i18n/i18n.service';
import { NetworkId, PredictionItem, PredictionRate, WhatIfResult } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { EngineText } from '../../shared/engine-text';
import { categoryName } from '../../shared/explain';
import { levelWord } from '../../shared/levels';
import { MAT_BUTTONS } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';

/** Risky categories a what-if may assume (they create exposure). */
const CATEGORIES = ['SANCTIONED', 'MIXER', 'SCAM', 'PHISHING', 'RANSOMWARE', 'STOLEN_FUNDS'];

/**
 * Hypotheses (ADR-0023 amendment 2), each in its own section and never mixed with facts: PREDICTED (a wallet's future
 * activity, with an expiry and a measured realisation rate) and SIMULATED (who would be exposed if an address were listed).
 */
@Component({
  selector: 'oci-predictions',
  imports: [EngineText, PageHeader, ...MAT_BUTTONS, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; align-items: start; }
    @media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } }
    .lvl { font-size: 11px; letter-spacing: .04em; color: var(--ink-2); border: 1px solid var(--line, #ccc); border-radius: 4px; padding: 0 4px; text-transform: uppercase; }
    .item { border-top: 1px solid var(--line, #ddd); padding: 6px 0; }
    .form { display: grid; gap: 6px; max-width: 520px; }
    .form input, .form select { font: inherit; font-size: 12px; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 6px 0; }
  `,
  template: `
    <div class="page">
      <oci-page-header [heading]="i18n.t('predictions.title')" [lede]="i18n.t('predictions.lede')" />
      <div class="grid">
        <section class="card" aria-labelledby="h-pred">
          <div class="card-head"><h2 id="h-pred">{{ i18n.t('predictions.predictions') }} <span class="lvl">{{ level('PREDICTED') }}</span></h2></div>
          <oci-state [loading]="rates.loading()" [error]="rates.error()" [hasData]="!!rates.value()" [empty]="(rates.value() ?? []).length === 0" [emptyText]="i18n.t('predictions.noModel')" (retry)="rates.reload()">
            @for (r of rates.value() ?? []; track r.type) { <p class="small">{{ rateLine(r, i18n.lang()) }}</p> }
          </oci-state>
          <div class="actions">
            @for (o of outcomes; track o) {
              <button matButton="outlined" class="sm" type="button" [attr.aria-pressed]="outcome() === o" (click)="show(o)">{{ outcomeText(o) }}</button>
            }
          </div>
          @for (p of items(); track p.id) {
            <div class="item">
              <p class="small"><span class="mono">{{ p.subject }}</span> ({{ p.network }}) · {{ outcomeText(p.outcome) }}</p>
              <p class="small">{{ i18n.t('predictions.ifHolds') }} <oci-engine-text [text]="p.condition" [plainInEnglish]="true" />. {{ i18n.t('predictions.expires', { when: when(p.expiresAt), confidence: p.confidence === null ? i18n.t('predictions.uncalibrated') : percent(p.confidence) }) }}</p>
              @if (p.outcomeReason) { <p class="small muted"><oci-engine-text [text]="p.outcomeReason" [plainInEnglish]="true" />@if (p.outcomeEvidence.length) { {{ i18n.t('predictions.transfers', { count: p.outcomeEvidence.length }) }} }</p> }
              <details><summary class="small">{{ i18n.t('predictions.limits') }}</summary>@for (l of p.limits; track l) { <p class="small muted"><oci-engine-text [text]="l" [plainInEnglish]="true" /></p> }</details>
            </div>
          } @empty { <p class="small muted">{{ i18n.t('predictions.none') }}</p> }
        </section>

        <section class="card" aria-labelledby="h-sim">
          <div class="card-head"><h2 id="h-sim">{{ i18n.t('predictions.whatIf') }} <span class="lvl">{{ level('SIMULATED') }}</span></h2></div>
          <div class="form">
            <label class="small" for="sim-net">{{ i18n.t('predictions.network') }}</label>
            <select id="sim-net" [value]="network()" (change)="network.set($any($event.target).value)">
              @for (n of networks(); track n) { <option [value]="n">{{ n }}</option> }
            </select>
            <label class="small" for="sim-addr">{{ i18n.t('predictions.address') }}</label>
            <input id="sim-addr" class="mono" [value]="address()" (input)="address.set($any($event.target).value.trim())" />
            <label class="small" for="sim-cat">{{ i18n.t('predictions.as') }}</label>
            <select id="sim-cat" [value]="category()" (change)="category.set($any($event.target).value)">
              @for (c of categories; track c) { <option [value]="c">{{ categoryText(c) }}</option> }
            </select>
            <div class="actions"><button matButton="filled" class="sm" type="button" [disabled]="!address() || running()" (click)="simulate()">{{ i18n.t('predictions.simulate') }}</button></div>
          </div>
          @if (sim(); as s) {
            <p class="small strong"><oci-engine-text [text]="s.statement" [plainInEnglish]="true" /></p>
            <p class="small muted">{{ i18n.t('predictions.coverage', { coverage: coverageText(s.coverage) }) }}@if (s.truncated) { {{ i18n.t('predictions.truncated', { count: s.candidates }) }} } {{ i18n.t('predictions.stored') }}</p>
            @for (e of s.exposed; track e.address) {
              <p class="small"><span class="lvl">{{ level('SIMULATED') }}</span> <span class="mono">{{ e.address }}</span> · {{ e.hops === 1 ? i18n.t('predictions.direct') : i18n.t('predictions.hops', { count: e.hops }) }}@if (e.alreadyExposedTo.length) { · {{ i18n.t('predictions.alreadyExposed', { categories: categoriesText(e.alreadyExposedTo) }) }} }</p>
            }
          }
        </section>
      </div>
    </div>
  `,
})
export class PredictionsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  private readonly enabled = inject(EnabledNetworks);
  protected readonly i18n = inject(I18nService);

  readonly outcomes = ['OPEN', 'REALISED', 'NOT_REALISED', 'UNDETERMINED'];
  readonly categories = CATEGORIES;
  readonly rates = apiResource({ params: () => ({}), stream: () => this.api.predictionRates() });
  readonly outcome = signal('OPEN');
  readonly items = signal<PredictionItem[]>([]);
  readonly network = signal<NetworkId>('tron');
  readonly address = signal('');
  readonly category = signal('SANCTIONED');
  readonly sim = signal<WhatIfResult | null>(null);
  readonly running = signal(false);
  readonly rateLine = rateLine;
  readonly level = (l: 'PREDICTED' | 'SIMULATED') => levelWord(l, this.i18n.lang());
  readonly outcomeText = (o: string) => { const k = `predictions.outcome.${o}`; return hasKey(k) ? this.i18n.t(k) : o.toLowerCase(); };
  readonly categoryText = (c: string) => categoryName(c, this.i18n.lang());
  readonly categoriesText = (cs: string[]) => fmtList(cs.map((c) => categoryName(c, this.i18n.lang())), this.i18n.lang());
  readonly coverageText = (c: string) => { const k = `predictions.coverage.${c}`; return hasKey(k) ? this.i18n.t(k) : c.toLowerCase(); };
  readonly percent = (r: number) => fmtPercent(r, this.i18n.lang());
  /** Local date and time in the console's language (English keeps the US short form the date pipe gave). */
  readonly when = (iso: string) => new Date(iso).toLocaleString(this.i18n.lang() === 'en' ? 'en-US' : LOCALE.fr, { dateStyle: 'short', timeStyle: 'short' });

  networks(): NetworkId[] { return this.enabled.enabled() ?? ['tron', 'ethereum']; }

  constructor() { this.show('OPEN'); }

  show(o: string): void {
    this.outcome.set(o);
    this.api.predictions(o).subscribe({ next: (p) => this.items.set(p), error: (e: ApiError) => this.toasts.error(statusTitle(e, this.i18n.lang()), errorText(e, this.i18n.lang())) });
  }

  simulate(): void {
    this.running.set(true);
    this.api.whatIf(this.network(), this.address(), this.category(), 2).subscribe({
      next: (r) => { this.running.set(false); this.sim.set(r); },
      error: (e: ApiError) => { this.running.set(false); this.toasts.error(statusTitle(e, this.i18n.lang()), errorText(e, this.i18n.lang())); },
    });
  }
}

/** The measured rate with its counts; never presented as established below the threshold. */
export function rateLine(r: PredictionRate, lang: Lang = 'en'): string {
  const tk = `predictions.type.${r.type}`;
  const type = hasKey(tk) ? translate(lang, tk) : r.type;
  const counts = translate(lang, 'predictions.counts', { realised: r.realised, notRealised: r.notRealised, undetermined: r.undetermined });
  if (r.rate === null) return translate(lang, 'predictions.rateNone', { type, counts });
  const pct = fmtPercent(r.rate, lang);
  return translate(lang, r.established ? 'predictions.rateEstablished' : 'predictions.rateSoFar', { type, pct, min: r.minEvaluated, counts });
}
