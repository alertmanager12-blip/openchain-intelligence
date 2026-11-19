import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Dashboard, SanctionsList } from '../../core/models';
import { I18nService } from '../../core/i18n/i18n.service';
import { fmtDate, fmtNumber } from '../../core/i18n/format';
import { netLabel } from '../../shared/format';
import { BandLine, reliability } from './reliability';

/**
 * "Is monitoring reliable?" (spec 2026-11-19 §4): the verdict word, then every problem with a link to where it is fixed;
 * more than two passing checks collapse into one line that can be opened. Older gaps stay reachable.
 */
@Component({
  selector: 'oci-reliability-band',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="band v-{{ r().verdict }}" aria-labelledby="h-rel">
      <h2 id="h-rel"><span>{{ i18n.t('band.title') }}</span> <strong class="verdict">{{ i18n.t('band.' + r().verdict) }}</strong></h2>
      <ul class="lines">
        @for (l of shown(); track l.check + l.text) {
          <li class="s-{{ l.state }}"><span class="mark" aria-hidden="true">{{ mark(l) }}</span>
            <span class="sr-only">{{ i18n.t('band.state.' + l.state) }} : </span>{{ l.text }}
            @if (l.link) { <a [routerLink]="l.link.path" [queryParams]="l.link.query">{{ i18n.t('band.fix') }}</a> }
          </li>
        }
        @if (collapsed()) {
          <li class="s-pass"><span class="mark" aria-hidden="true">✓</span>
            <button type="button" class="more" [attr.aria-expanded]="open()" (click)="open.set(!open())">{{ i18n.t('band.passed', { count: passes().length }) }}</button>
          </li>
        }
      </ul>
      @if (r().olderGaps.length) {
        <details class="older"><summary>{{ i18n.t('band.olderGaps', { count: r().olderGaps.length }) }}</summary>
          <ul>@for (g of r().olderGaps; track g.network + g.fromBlock) {
            <li>{{ netLabel(g.network) }} {{ fmt(g.fromBlock) }}–{{ fmt(g.toBlock) }} ({{ fmt(g.blocks) }}){{ g.detectedAt ? ', ' + date(g.detectedAt) : '' }}</li>
          }</ul>
        </details>
      }
    </section>`,
  styles: `
    .band { border: 1px solid var(--line); border-left: 4px solid var(--ok); border-radius: var(--r-panel); background: var(--surface); padding: 14px 18px; display: grid; gap: 8px; }
    .band.v-partly { border-left-color: var(--warn); }
    .band.v-no { border-left-color: var(--danger); }
    h2 { margin: 0; font-size: 15px; display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; }
    .verdict { font-size: 15px; }
    .v-yes .verdict { color: var(--ok); } .v-partly .verdict { color: var(--warn); } .v-no .verdict { color: var(--danger); }
    .lines { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; }
    li { display: flex; flex-wrap: wrap; gap: 4px 8px; align-items: baseline; color: var(--ink); overflow-wrap: anywhere; min-width: 0; }
    .mark { width: 1.2em; flex: none; font-weight: 700; }
    .s-fail .mark { color: var(--danger); } .s-warn .mark { color: var(--warn); } .s-unknown .mark { color: var(--ink-2); } .s-pass .mark { color: var(--ok); }
    a, .more { font-size: var(--fs-12); }
    .more { font: inherit; font-size: var(--fs-12); background: none; border: 0; padding: 0; color: var(--ink-2); cursor: pointer; text-decoration: underline dotted; }
    .more:focus-visible, a:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
    .older { font-size: var(--fs-12); color: var(--ink-2); }
    .older ul { margin: 4px 0 0; padding-left: 18px; }
  `,
})
export class ReliabilityBand {
  protected readonly i18n = inject(I18nService);
  readonly dashboard = input.required<Dashboard>();
  readonly sanctions = input<SanctionsList | null | undefined>(undefined);
  /** Fixed clock for tests; null = now. */
  readonly now = input<number | null>(null);
  protected readonly open = signal(false);

  protected readonly r = computed(() => reliability(this.dashboard(), this.sanctions(), this.i18n.lang(), this.now() ?? Date.now()));
  protected readonly passes = computed(() => this.r().lines.filter((l) => l.state === 'pass'));
  protected readonly collapsed = computed(() => this.passes().length > 2 && !this.open());
  protected readonly shown = computed(() => (this.collapsed() ? this.r().lines.filter((l) => l.state !== 'pass') : this.r().lines));

  protected mark(l: BandLine): string { return l.state === 'pass' ? '✓' : l.state === 'unknown' ? '?' : l.state === 'warn' ? '!' : '✕'; }
  protected readonly netLabel = netLabel;
  protected fmt(n: number): string { return fmtNumber(n, this.i18n.lang()); }
  protected date(iso: string): string { return fmtDate(iso, this.i18n.lang()); }
}
