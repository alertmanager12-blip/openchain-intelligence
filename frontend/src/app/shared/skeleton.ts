import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type SkeletonKind = 'table' | 'readout' | 'lines';

/**
 * Placeholder shaped like the content that is loading (table rows, readout wells or text lines), so the layout
 * does not jump when data arrives. Announced once as busy; the shapes themselves are hidden from assistive tech.
 */
@Component({
  selector: 'oci-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'status', 'aria-busy': 'true', '[attr.aria-label]': 'label()', '[class]': "'skeleton skeleton--' + kind()" },
  template: `
    @switch (kind()) {
      @case ('table') {
        <div class="t-head" aria-hidden="true">@for (c of cols(); track $index) { <span class="skel" [style.width.%]="c"></span> }</div>
        @for (r of rowList(); track r) {
          <div class="t-row" aria-hidden="true">@for (c of cols(); track $index) { <span class="skel" [style.width.%]="c - ((r * 7 + $index * 13) % 20)"></span> }</div>
        }
      }
      @case ('readout') {
        <div class="wells-s" aria-hidden="true">@for (r of rowList(); track r) { <div class="w"><span class="skel l"></span><span class="skel v"></span></div> }</div>
      }
      @default {
        @for (r of rowList(); track r) { <span class="skel line" aria-hidden="true" [style.width.%]="92 - ((r * 17) % 35)"></span> }
      }
    }
  `,
  styles: `
    :host { display: grid; gap: 10px; padding: 8px 0; }
    .t-head, .t-row { display: grid; grid-template-columns: var(--cols); gap: 16px; align-items: center; }
    .t-head { padding-bottom: 10px; border-bottom: 1px solid var(--line); }
    .t-head .skel { height: 10px; opacity: 0.7; }
    .t-row { padding: 8px 0; border-bottom: 1px solid var(--line-soft); }
    .t-row .skel { height: 14px; }
    .wells-s { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(150px, 100%), 1fr)); gap: 8px; }
    .w { display: grid; gap: 8px; padding: 14px 16px; border-radius: var(--r-well); background: var(--well); box-shadow: var(--shadow-inset); }
    .w .l { height: 10px; width: 55%; } .w .v { height: 24px; width: 75%; }
    .w .skel { background-image: linear-gradient(90deg, var(--line-soft) 0%, var(--surface-2) 45%, var(--line-soft) 90%); }
    .line { height: 12px; }
  `,
})
export class Skeleton {
  readonly kind = input<SkeletonKind>('lines');
  readonly rows = input(5);
  /** Relative column widths for the table shape (percent of each grid cell). */
  readonly columns = input<number[]>([70, 90, 60, 80, 50]);
  readonly label = input('Loading');
  readonly rowList = computed(() => Array.from({ length: this.rows() }, (_, i) => i));
  readonly cols = computed(() => this.columns());
}
