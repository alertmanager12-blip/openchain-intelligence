import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { Shell } from '../layout/shell';

export interface Crumb {
  label: string;
  /** Router link; the last crumb (the current page) never has one. */
  link?: string | unknown[];
  query?: Record<string, string>;
  /** Render in the monospace face (addresses, ids). */
  mono?: boolean;
}

/**
 * The top of every page: a sticky bar with the breadcrumbs, the page actions (projected) and the search trigger,
 * then the page title and one sentence of context. `titleHidden` keeps the h1 for assistive tech only (inbox views
 * whose breadcrumb already names the page).
 */
@Component({
  selector: 'oci-page-header',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page-header' },
  template: `
    <div class="bar">
      @if (!docked()) {
        <button class="ib" type="button" aria-label="Show navigation" aria-controls="sidebar" [attr.aria-expanded]="shell.drawerOpen()" (click)="shell.drawerOpen.set(true)">
          <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2.5 4.5h11M2.5 8h11M2.5 11.5h11"/></svg>
        </button>
      }
      <nav class="crumbs" aria-label="Breadcrumb">
        <ol>
          @for (c of trail(); track $index) {
            <li [class.cur]="$last">
              @if (c.link && !$last) { <a [routerLink]="c.link" [queryParams]="c.query" [class.mono]="c.mono">{{ c.label }}</a> }
              @else { <span [class.mono]="c.mono" [attr.aria-current]="$last ? 'page' : null">{{ c.label }}</span> }
            </li>
          }
        </ol>
      </nav>
      <div class="acts"><ng-content /></div>
      <button class="search" type="button" (click)="shell.palette.set(true)" aria-haspopup="dialog" aria-keyshortcuts="Control+K Meta+K" aria-label="Search">
        <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="7" cy="7" r="4.5"/><path d="M10.5 10.5 14 14"/></svg>
        <span class="ph">Search</span><kbd>{{ shell.mac ? '⌘' : 'Ctrl' }}</kbd><kbd>K</kbd>
      </button>
    </div>
    @if (heading()) {
      <div class="title" [class.sr-only]="titleHidden()">
        <h1>{{ heading() }}</h1>
        @if (lede()) { <p>{{ lede() }}</p> }
      </div>
    }
  `,
  styles: `
    :host { display: grid; gap: 20px; min-width: 0; }
    .bar {
      position: sticky; top: 0; z-index: 20; display: flex; align-items: center; gap: 8px; height: var(--topbar-h); margin: 0 calc(-1 * var(--gutter));
      padding: 0 var(--gutter); background: color-mix(in srgb, var(--bg) 88%, transparent); backdrop-filter: saturate(1.4) blur(8px); border-bottom: 1px solid var(--line);
    }
    .ib { display: grid; place-items: center; width: 30px; height: 30px; margin-left: -6px; border: 0; border-radius: var(--r-control); background: transparent; color: var(--ink-2); cursor: pointer; }
    .ib:hover { background: var(--hover); }
    .ib svg { width: 16px; height: 16px; }
    .crumbs { flex: 1; min-width: 0; }
    ol { display: flex; align-items: center; margin: 0; padding: 0; list-style: none; min-width: 0; font-size: 13px; }
    li { display: flex; align-items: center; min-width: 0; color: var(--muted); white-space: nowrap; }
    li + li::before { content: '/'; margin: 0 8px; color: var(--line-strong); flex: none; }
    li a { color: var(--muted); }
    li a:hover { color: var(--ink); text-decoration: none; }
    li.cur { color: var(--ink); font-weight: 500; overflow: hidden; }
    li.cur span { overflow: hidden; text-overflow: ellipsis; }
    li .mono { font-size: 12px; word-break: normal; }
    .acts { display: flex; gap: 6px; align-items: center; flex: none; }
    .acts:empty { display: none; }
    .acts ::ng-deep .mat-mdc-form-field { width: 220px; --mat-form-field-container-height: 30px; --mat-form-field-container-vertical-padding: 5px; }
    .search {
      display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 6px 0 8px; flex: none; cursor: pointer; font: inherit; font-size: 13px;
      color: var(--muted); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-control); transition: border-color var(--t-fast) var(--ease), color var(--t-fast);
    }
    .search:hover { border-color: var(--line-strong); color: var(--ink); }
    .search svg { width: 14px; height: 14px; }
    .ph { margin-right: 18px; }
    .title { display: grid; gap: 4px; }
    p { margin: 0; color: var(--muted); max-width: 80ch; font-size: 13px; }
    @media (max-width: 720px) {
      .ph, .search kbd { display: none; }
      .search { width: 30px; padding: 0; justify-content: center; }
      /* phones: the current page only; the earlier crumbs stay reachable through the browser's back button and the sidebar */
      .crumbs li:not(.cur) { display: none; }
      .crumbs li.cur::before { content: none; }
      .acts ::ng-deep .hide-sm { display: none; }
    }
  `,
})
export class PageHeader {
  protected readonly shell = inject(Shell);
  protected readonly docked = toSignal(inject(BreakpointObserver).observe('(min-width: 1000px)').pipe(map((s) => s.matches)), { initialValue: true });
  readonly heading = input<string>('');
  readonly lede = input('');
  /** Breadcrumb trail; defaults to the heading alone. */
  readonly crumbs = input<Crumb[] | null>(null);
  readonly titleHidden = input(false);
  protected readonly trail = computed(() => this.crumbs() ?? [{ label: this.heading() }]);
}
