import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { I18nService, Lang } from '../core/i18n/i18n.service';

/** FR | EN switch of the console language (spec 2026-11-19 §1); in every page's top bar, also at phone width. */
@Component({
  selector: 'oci-lang-switch',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="sw" role="group" [attr.aria-label]="i18n.t('shell.language')">
      @for (l of langs; track l) {
        <button type="button" [attr.aria-pressed]="i18n.lang() === l" [attr.lang]="l" (click)="i18n.set(l)">{{ l.toUpperCase() }}</button>
      }
    </div>
  `,
  styles: `
    .sw { display: inline-flex; border: 1px solid var(--line); border-radius: 6px; overflow: hidden; }
    button { all: unset; cursor: pointer; padding: 3px 8px; font-size: 12px; font-weight: 600; color: var(--muted); line-height: 1.4; }
    button[aria-pressed='true'] { background: var(--accent-tint); color: var(--accent); }
    button:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
  `,
})
export class LangSwitch {
  protected readonly i18n = inject(I18nService);
  protected readonly langs: Lang[] = ['fr', 'en'];
}
