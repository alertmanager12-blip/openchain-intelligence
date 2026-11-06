import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Page title, one subdued sentence of context, and the page-level actions on the right. */
@Component({
  selector: 'oci-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page-header' },
  template: `
    <div class="t">
      <h1>{{ heading() }}</h1>
      @if (lede()) { <p>{{ lede() }}</p> }
    </div>
    <div class="a"><ng-content /></div>
  `,
  styles: `
    :host { display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 12px 24px; }
    .t { min-width: 0; flex: 1 1 420px; }
    p { margin: 6px 0 0; color: var(--ink-2); max-width: 75ch; }
    .a { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
    .a:empty { display: none; }
  `,
})
export class PageHeader {
  readonly heading = input.required<string>();
  readonly lede = input('');
}
