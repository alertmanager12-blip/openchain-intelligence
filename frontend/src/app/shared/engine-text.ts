import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { I18nService } from '../core/i18n/i18n.service';

/**
 * A sentence composed by the engine, which stays in English whatever the console's language (spec 2026-11-19, option C):
 * introduced by a label in the console's language and marked lang="en" so screen readers pronounce it correctly.
 * "collapsed" tucks it behind the label (a details element); "visible" shows label and text inline.
 */
@Component({
  selector: 'oci-engine-text',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (plainInEnglish() && i18n.lang() === 'en') {
      {{ text() }}
    } @else if (mode() === 'collapsed') {
      <details class="engine"><summary>{{ i18n.t('engine.label') }}</summary><span lang="en">{{ text() }}</span></details>
    } @else {
      <span class="engine"><span class="engine-label">{{ i18n.t('engine.label') }}</span> <span lang="en">{{ text() }}</span></span>
    }
  `,
  styles: `
    summary { cursor: pointer; width: fit-content; }
    .engine-label { color: var(--ink-2); }
  `,
})
export class EngineText {
  protected readonly i18n = inject(I18nService);
  readonly text = input.required<string>();
  readonly mode = input<'visible' | 'collapsed'>('visible');
  /** In English the text is the page's own language: show it plain, without the label. */
  readonly plainInEnglish = input(false);
}
