import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The ChainSentinel mark: a fund flow traced over two hops to a watched wallet (the ring). Same drawing as the console
 * sidebar mark and public/favicon.svg.
 */
@Component({
  selector: 'oci-cs-mark',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: inline-flex; flex: none; }
    .bg { fill: var(--cs-accent, #3ddc97); }
    .ln { fill: none; stroke: var(--cs-accent-ink, #03150d); stroke-width: 1.4; stroke-linecap: round; stroke-linejoin: round; }
    .mk-dot { fill: var(--cs-accent-ink, #03150d); }
    .ring { fill: none; stroke: var(--cs-accent-ink, #03150d); stroke-width: 1.6; }
  `,
  template: `
    <svg viewBox="0 0 20 20" [attr.width]="size()" [attr.height]="size()" aria-hidden="true">
      <rect x="1" y="1" width="18" height="18" rx="5" class="bg" />
      <path class="ln" d="M5.4 13.6 8.8 6.6l5.2 3.6" />
      <circle class="mk-dot" cx="5.4" cy="13.6" r="1.5" />
      <circle class="mk-dot" cx="8.8" cy="6.6" r="1.5" />
      <circle class="ring" cx="14" cy="10.2" r="2.3" />
    </svg>
  `,
})
export class CsMark {
  readonly size = input(24);
}
