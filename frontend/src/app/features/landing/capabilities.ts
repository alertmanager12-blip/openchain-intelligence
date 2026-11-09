import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';
import { Availability, LANDING_COPY } from './landing-content';

const STATUS: Record<Availability, string> = { available: 'Available', partial: 'Partial', planned: 'Planned' };

/** The four capabilities as tabs (WAI-ARIA tabs pattern: arrow keys, Home/End); every item states its availability. */
@Component({
  selector: 'oci-cs-capabilities',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    .tabs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin-top: 48px; border: 1px solid var(--cs-line); border-radius: var(--cs-r) var(--cs-r) 0 0; overflow: hidden; }
    .tab {
      display: grid; align-content: start; gap: 2px; padding: 18px 20px; text-align: left; background: var(--cs-bg); border: 0; border-right: 1px solid var(--cs-line);
      color: var(--cs-muted); font: inherit; cursor: pointer; position: relative;
    }
    .tab:last-child { border-right: 0; }
    .tab b { font-size: 20px; font-weight: 600; letter-spacing: -0.02em; color: var(--cs-text); opacity: .7; }
    .tab span { font-size: 14px; }
    .tab:hover b { opacity: 1; }
    .tab[aria-selected='true'] { background: var(--cs-surface); }
    .tab[aria-selected='true'] b { opacity: 1; }
    .tab[aria-selected='true']::after { content: ''; position: absolute; left: 0; right: 0; top: 0; height: 2px; background: var(--cs-accent); }
    .panel { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr); gap: 48px; padding: 36px; border: 1px solid var(--cs-line); border-top: 0; border-radius: 0 0 var(--cs-r) var(--cs-r); background: var(--cs-surface); }
    .panel[hidden] { display: none; }
    .summary { font-size: 18px; line-height: 1.55; max-width: 48ch; }
    ul { list-style: none; margin: 28px 0 0; padding: 0; display: grid; }
    li { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 4px 16px; padding: 14px 0; border-top: 1px solid var(--cs-line); }
    li strong { font-weight: 600; font-size: 15px; }
    li p { grid-column: 1 / -1; color: var(--cs-muted); font-size: 14px; line-height: 1.5; }
    .visual { border: 1px solid var(--cs-line); border-radius: var(--cs-r); background: var(--cs-bg-2); padding: 20px; display: grid; align-content: center; min-height: 280px; }
    .visual svg { width: 100%; height: auto; }
    .visual figcaption { margin-top: 12px; color: var(--cs-faint); font-size: 13px; }
    .v-line { fill: none; stroke: var(--cs-line-2); stroke-width: 1.5; }
    .v-flow { fill: none; stroke: var(--cs-flow); stroke-width: 1.8; }
    .v-risk { fill: var(--cs-risk); }
    .v-acc { fill: var(--cs-accent); }
    .v-node { fill: var(--cs-raised); stroke: var(--cs-line-2); stroke-width: 1.5; }
    .v-txt { fill: var(--cs-muted); font: 500 12px var(--cs-font); }
    @media (max-width: 900px) {
      .tabs { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .tab:nth-child(2) { border-right: 0; }
      .tab:nth-child(-n + 2) { border-bottom: 1px solid var(--cs-line); }
      .panel { grid-template-columns: 1fr; padding: 24px; gap: 28px; }
    }
  `,
  template: `
    <section class="cs-section" id="capabilities" aria-labelledby="cap-title">
      <div class="cs-wrap">
        <h2 class="cs-h2" id="cap-title">Understand. Predict. Investigate. Act.</h2>
        <p class="cs-lede">Each capability below is labeled with what works today, what is partial and what is planned.</p>

        <div class="tabs" role="tablist" aria-label="Capabilities" (keydown)="onKey($event)">
          @for (p of pillars; track p.id; let i = $index) {
            <button class="tab" type="button" role="tab" [id]="'tab-' + p.id" [attr.aria-controls]="'panel-' + p.id"
              [attr.aria-selected]="active() === i" [attr.tabindex]="active() === i ? 0 : -1" (click)="active.set(i)">
              <b>{{ p.verb }}</b><span>{{ p.name }}</span>
            </button>
          }
        </div>
        @for (p of pillars; track p.id; let i = $index) {
          <div class="panel" role="tabpanel" [id]="'panel-' + p.id" [attr.aria-labelledby]="'tab-' + p.id" [hidden]="active() !== i">
            <div>
              <p class="summary">{{ p.summary }}</p>
              <ul>
                @for (c of p.items; track c.title) {
                  <li><strong>{{ c.title }}</strong><span class="cs-status cs-status--{{ c.status }}">{{ status[c.status] }}</span><p>{{ c.detail }}</p></li>
                }
              </ul>
            </div>
            <figure class="visual" style="margin: 0">
              @switch (p.id) {
                @case ('understand') {
                  <svg viewBox="0 0 320 200" aria-hidden="true">
                    <text class="v-txt" x="0" y="14">Activity by day</text>
                    @for (h of bars; track $index) { <rect [attr.x]="$index * 22" [attr.y]="150 - h" width="14" [attr.height]="h" rx="2" [class.v-acc]="$index === 9" fill="#2a3542" /> }
                    <path class="v-line" d="M0 160 H300" />
                    <text class="v-txt" x="0" y="190">Score 41, medium: 2 contributions</text>
                  </svg>
                  <figcaption>Wallet profile and score (illustrative)</figcaption>
                }
                @case ('predict') {
                  <svg viewBox="0 0 320 200" aria-hidden="true">
                    <path class="v-line" d="M10 110 H310" />
                    @for (x of ticks; track x) { <circle [attr.cx]="x" cy="110" r="4" fill="#2a3542" /> }
                    <circle class="v-acc" cx="196" cy="110" r="6" /><text class="v-txt" x="196" y="86" text-anchor="middle">unusual amount</text>
                    <circle class="v-risk" cx="262" cy="110" r="7" /><text class="v-txt" x="262" y="146" text-anchor="middle">rapid drain</text>
                    <text class="v-txt" x="10" y="190">Signals on one wallet over 24 h</text>
                  </svg>
                  <figcaption>Each signal keeps its evidence (illustrative)</figcaption>
                }
                @case ('investigate') {
                  <svg viewBox="0 0 320 200" aria-hidden="true">
                    @for (n of graph; track $index) { <path class="v-line" [attr.d]="'M160 100 L' + n[0] + ' ' + n[1]" /> }
                    <path class="v-flow" d="M160 100 L262 46" /><path class="v-flow" d="M262 46 L300 110" />
                    @for (n of graph; track $index) { <circle class="v-node" [attr.cx]="n[0]" [attr.cy]="n[1]" r="9" /> }
                    <circle class="v-node" cx="160" cy="100" r="14" style="stroke: var(--cs-flow)" />
                    <circle class="v-risk" cx="300" cy="110" r="8" />
                    <text class="v-txt" x="300" y="138" text-anchor="middle">2 hops</text>
                  </svg>
                  <figcaption>Exposure through an intermediary wallet (illustrative)</figcaption>
                }
                @case ('act') {
                  <svg viewBox="0 0 320 200" aria-hidden="true">
                    <rect x="8" y="78" width="96" height="44" rx="8" fill="#2a1416" stroke="#f0645c" />
                    <text class="v-txt" x="56" y="105" text-anchor="middle" style="fill: #e8edf3">Alert</text>
                    @for (c of channels; track c; let i = $index) {
                      <path class="v-line" [attr.d]="'M104 100 C 160 100, 160 ' + (28 + i * 48) + ', 210 ' + (28 + i * 48)" />
                      <rect x="210" [attr.y]="12 + i * 48" width="102" height="32" rx="6" class="v-node" />
                      <text class="v-txt" x="261" [attr.y]="33 + i * 48" text-anchor="middle">{{ c }}</text>
                    }
                  </svg>
                  <figcaption>One alert, routed per rule</figcaption>
                }
              }
            </figure>
          </div>
        }
      </div>
    </section>
  `,
})
export class CapabilitiesSection {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly pillars = LANDING_COPY.pillars;
  protected readonly status = STATUS;
  protected readonly active = signal(0);
  protected readonly bars = [22, 30, 18, 40, 26, 34, 20, 28, 46, 92, 38, 30, 24, 36];
  protected readonly ticks = [30, 62, 98, 130, 160, 228, 290];
  protected readonly graph: readonly [number, number][] = [[60, 40], [40, 120], [96, 176], [230, 168], [262, 46], [120, 20]];
  protected readonly channels = ['Telegram', 'Slack', 'Discord', 'Webhook'];

  protected onKey(e: KeyboardEvent): void {
    const n = this.pillars.length;
    const next = { ArrowRight: this.active() + 1, ArrowLeft: this.active() - 1, Home: 0, End: n - 1 }[e.key];
    if (next === undefined) return;
    e.preventDefault();
    const i = (next + n) % n;
    this.active.set(i);
    this.host.nativeElement.querySelector<HTMLElement>(`#tab-${this.pillars[i].id}`)?.focus();
  }
}
