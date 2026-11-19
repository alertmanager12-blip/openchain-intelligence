import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { EngineText } from './engine-text';
import { I18nService } from '../core/i18n/i18n.service';
import { Level, levelWord } from './levels';

/**
 * One finding with its level of proof (spec 2026-11-18): a coloured left rule and the level word first, then the finding,
 * its detail, and the citation that leads to its evidence. Shared by the overview, wallet, investigation and alerts.
 */
@Component({
  selector: 'oci-evidence-line',
  imports: [EngineText],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="line lvl-{{ level().toLowerCase() }}">
      <span class="lvl">{{ word() }}</span>
      <span class="text">{{ text() }}</span>
      @if (detail()) { <span class="detail">{{ detail() }}</span> }
      @if (original()) { <oci-engine-text class="orig" mode="collapsed" [text]="original()!" /> }
      @if (cite()) { <button type="button" class="cite" (click)="citeClick.emit(cite()!)" [attr.aria-label]="citeLabel(cite()!)">{{ cite() }}</button> }
    </div>`,
  styles: `
    .line { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 10px; padding: 7px 12px; background: var(--surface);
      border-left: 3px solid var(--line); border-radius: 0 var(--r-control) var(--r-control) 0; color: var(--ink); overflow-wrap: anywhere; }
    .lvl { font-size: var(--fs-12); font-weight: 600; min-width: 72px; }
    .text { flex: 1 1 260px; }
    .detail { font-size: var(--fs-12); color: var(--ink-2); }
    .cite { font: inherit; font-size: var(--fs-12); color: var(--ink-2); background: none; border: 0; padding: 0; cursor: pointer; text-decoration: underline dotted; }
    .orig { display: block; flex-basis: 100%; font-size: var(--fs-12); color: var(--ink-2); }
    .cite:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
    .lvl-observed { border-left-color: var(--lvl-observed); } .lvl-observed .lvl { color: var(--lvl-observed); }
    .lvl-inferred { border-left-color: var(--lvl-inferred-rule); } .lvl-inferred .lvl { color: var(--lvl-inferred); }
    .lvl-confirmed { border-left-color: var(--lvl-confirmed); } .lvl-confirmed .lvl { color: var(--lvl-confirmed); }
    .lvl-predicted { border-left-color: var(--lvl-predicted); } .lvl-predicted .lvl { color: var(--lvl-predicted); }
    .lvl-simulated { border-left: 3px dashed var(--lvl-simulated); } .lvl-simulated .lvl { color: var(--lvl-simulated); }
  `,
})
export class EvidenceLine {
  readonly level = input.required<Level>();
  readonly text = input.required<string>();
  readonly detail = input<string | null>(null);
  readonly cite = input<string | null>(null);
  readonly citeClick = output<string>();
  protected readonly i18n = inject(I18nService);
  /** The engine's own wording (English), kept reachable when the line is written in the console's language. */
  readonly original = input<string | null>(null);
  readonly word = computed(() => levelWord(this.level(), this.i18n.lang()));
  protected citeLabel(c: string): string { return this.i18n.t('evidence.cite', { id: c }); }
}
