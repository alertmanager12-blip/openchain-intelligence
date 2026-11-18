import { DatePipe, LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { EnabledNetworks } from '../../core/enabled-networks';
import { NetworkId, PredictionItem, PredictionRate, WhatIfResult } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
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
  imports: [DatePipe, LowerCasePipe, PageHeader, ...MAT_BUTTONS, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; align-items: start; }
    @media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } }
    .lvl { font-size: 11px; letter-spacing: .04em; color: var(--ink-2); border: 1px solid var(--line, #ccc); border-radius: 4px; padding: 0 4px; }
    .item { border-top: 1px solid var(--line, #ddd); padding: 6px 0; }
    .form { display: grid; gap: 6px; max-width: 520px; }
    .form input, .form select { font: inherit; font-size: 12px; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 6px 0; }
  `,
  template: `
    <div class="page">
      <oci-page-header heading="Predictions and what-if" lede="Hypotheses, not findings. A prediction says what a wallet may do before an expiry and is checked afterwards; a simulation says what would follow from an assumption. Neither changes a score, an alert or a label." />
      <div class="grid">
        <section class="card" aria-labelledby="h-pred">
          <div class="card-head"><h2 id="h-pred">Predictions <span class="lvl">PREDICTED</span></h2></div>
          <oci-state [loading]="rates.loading()" [error]="rates.error()" [hasData]="!!rates.value()" [empty]="(rates.value() ?? []).length === 0" emptyText="No prediction model." (retry)="rates.reload()">
            @for (r of rates.value() ?? []; track r.type) { <p class="small">{{ rateLine(r) }}</p> }
          </oci-state>
          <div class="actions">
            @for (o of outcomes; track o) {
              <button matButton="outlined" class="sm" type="button" [attr.aria-pressed]="outcome() === o" (click)="show(o)">{{ o | lowercase }}</button>
            }
          </div>
          @for (p of items(); track p.id) {
            <div class="item">
              <p class="small"><span class="mono">{{ p.subject }}</span> ({{ p.network }}) · {{ p.outcome | lowercase }}</p>
              <p class="small">If the hypothesis holds: {{ p.condition }}. Expires {{ p.expiresAt | date: 'short' }}. Confidence: {{ p.confidence === null ? 'uncalibrated' : (p.confidence * 100).toFixed(0) + '%' }}.</p>
              @if (p.outcomeReason) { <p class="small muted">{{ p.outcomeReason }}@if (p.outcomeEvidence.length) { ({{ p.outcomeEvidence.length }} transfer(s)) }</p> }
              <details><summary class="small">Limits</summary>@for (l of p.limits; track l) { <p class="small muted">{{ l }}</p> }</details>
            </div>
          } @empty { <p class="small muted">No prediction in this state.</p> }
        </section>

        <section class="card" aria-labelledby="h-sim">
          <div class="card-head"><h2 id="h-sim">What-if exposure <span class="lvl">SIMULATED</span></h2></div>
          <div class="form">
            <label class="small" for="sim-net">Network</label>
            <select id="sim-net" [value]="network()" (change)="network.set($any($event.target).value)">
              @for (n of networks(); track n) { <option [value]="n">{{ n }}</option> }
            </select>
            <label class="small" for="sim-addr">Address assumed listed</label>
            <input id="sim-addr" class="mono" [value]="address()" (input)="address.set($any($event.target).value.trim())" />
            <label class="small" for="sim-cat">As</label>
            <select id="sim-cat" [value]="category()" (change)="category.set($any($event.target).value)">
              @for (c of categories; track c) { <option [value]="c">{{ c | lowercase }}</option> }
            </select>
            <div class="actions"><button matButton="filled" class="sm" type="button" [disabled]="!address() || running()" (click)="simulate()">Simulate</button></div>
          </div>
          @if (sim(); as s) {
            <p class="small strong">{{ s.statement }}</p>
            <p class="small muted">Coverage of the address: {{ s.coverage | lowercase }}@if (s.truncated) {; only the first {{ s.candidates }} wallets were examined}. Nothing was stored.</p>
            @for (e of s.exposed; track e.address) {
              <p class="small"><span class="lvl">SIMULATED</span> <span class="mono">{{ e.address }}</span> · {{ e.hops === 1 ? 'direct' : e.hops + ' hops' }}@if (e.alreadyExposedTo.length) { · already exposed to it as {{ e.alreadyExposedTo.join(', ') | lowercase }} }</p>
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

  networks(): NetworkId[] { return this.enabled.enabled() ?? ['tron', 'ethereum']; }

  constructor() { this.show('OPEN'); }

  show(o: string): void {
    this.outcome.set(o);
    this.api.predictions(o).subscribe({ next: (p) => this.items.set(p), error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined) });
  }

  simulate(): void {
    this.running.set(true);
    this.api.whatIf(this.network(), this.address(), this.category(), 2).subscribe({
      next: (r) => { this.running.set(false); this.sim.set(r); },
      error: (e: ApiError) => { this.running.set(false); this.toasts.error(e.title, e.detail ?? undefined); },
    });
  }
}

/** The measured rate with its counts; never presented as established below the threshold. */
export function rateLine(r: PredictionRate): string {
  const counts = `${r.realised} realised, ${r.notRealised} not realised, ${r.undetermined} undetermined`;
  if (r.rate === null) return `${r.type}: no evaluated prediction yet (${counts}).`;
  const pct = `${(r.rate * 100).toFixed(0)}%`;
  return r.established ? `${r.type}: realised ${pct} of the time (${counts}).`
    : `${r.type}: ${pct} so far, not established (fewer than ${r.minEvaluated} evaluated; ${counts}).`;
}
