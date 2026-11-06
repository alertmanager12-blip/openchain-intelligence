import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, input, model, signal, viewChildren } from '@angular/core';

export interface TabDef<T extends string = string> {
  id: T;
  label: string;
  /** Shown after the label in tabular figures; omitted when undefined or ''. */
  count?: string | number;
}

/**
 * WAI-ARIA tabs (automatic activation): arrow keys, Home and End move between tabs. Panels live in the page
 * and must use id="panel-{prefix}-{id}" and aria-labelledby="tab-{prefix}-{id}". The selection indicator
 * slides to the active tab.
 */
@Component({
  selector: 'oci-tab-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tabs" role="tablist" [attr.aria-label]="label()" [attr.aria-orientation]="vertical() ? 'vertical' : null" (keydown)="key($event)">
      @for (t of tabs(); track t.id) {
        <button #tab type="button" role="tab" [id]="'tab-' + prefix() + '-' + t.id" [attr.aria-controls]="'panel-' + prefix() + '-' + t.id"
          [attr.aria-selected]="selected() === t.id" [attr.tabindex]="selected() === t.id ? 0 : -1" (click)="selected.set(t.id)">
          {{ t.label }}@if (t.count !== undefined && t.count !== '') { <span class="count">{{ t.count }}</span> }
        </button>
      }
      <span class="ink" aria-hidden="true" [style.transform]="ink().transform" [style.width.px]="ink().w" [style.height.px]="ink().h"></span>
    </div>
  `,
  styles: `
    .tabs { position: relative; display: flex; gap: 4px; border-bottom: 1px solid var(--line); overflow-x: auto; scrollbar-width: none; }
    [role='tab'] { font: 500 15px/1.2 var(--font); color: var(--ink-2); background: none; border: 0; padding: 10px 12px 12px; cursor: pointer; white-space: nowrap; border-radius: var(--r-control) var(--r-control) 0 0; }
    [role='tab']:hover { color: var(--ink); background: var(--surface-2); }
    [role='tab'][aria-selected='true'] { color: var(--ink); }
    [role='tab']:focus-visible { outline-offset: -2px; }
    .count { margin-left: 6px; font-size: 12px; font-weight: 600; color: var(--muted); background: var(--well); padding: 1px 6px; border-radius: 10px; font-variant-numeric: tabular-nums; }
    [aria-selected='true'] .count { color: var(--accent); background: var(--accent-tint); }
    .ink { position: absolute; left: 0; bottom: 0; height: 2px; background: var(--accent); border-radius: 2px 2px 0 0; transition: transform 0.22s var(--ease), width 0.22s var(--ease); pointer-events: none; }
    :host(.vertical) .tabs { flex-direction: column; border-bottom: 0; border-left: 1px solid var(--line); gap: 2px; }
    :host(.vertical) [role='tab'] { text-align: left; border-radius: 0 var(--r-control) var(--r-control) 0; padding: 8px 14px; }
    :host(.vertical) .ink { width: 2px; top: 0; bottom: auto; border-radius: 0 2px 2px 0; transition-property: transform, height; }
  `,
  host: { '[class.vertical]': 'vertical()' },
})
export class TabList<T extends string = string> {
  readonly tabs = input.required<TabDef<T>[]>();
  readonly selected = model.required<T>();
  readonly prefix = input('tabs');
  readonly label = input.required<string>();
  readonly vertical = input(false);

  private readonly buttons = viewChildren<ElementRef<HTMLButtonElement>>('tab');
  protected readonly ink = signal({ transform: 'none', w: 0, h: 2 });

  constructor() {
    // Measure the active tab after render to slide the indicator under it (DOM read, so afterRenderEffect).
    afterRenderEffect({
      read: () => {
        const i = this.tabs().findIndex((t) => t.id === this.selected());
        const el = this.buttons()[i]?.nativeElement;
        if (!el) return;
        this.ink.set(this.vertical()
          ? { transform: `translateY(${el.offsetTop}px)`, w: 2, h: el.offsetHeight }
          : { transform: `translateX(${el.offsetLeft}px)`, w: el.offsetWidth, h: 2 });
      },
    });
  }

  protected key(e: KeyboardEvent): void {
    const ids = this.tabs().map((t) => t.id);
    const i = ids.indexOf(this.selected());
    const fwd = this.vertical() ? 'ArrowDown' : 'ArrowRight';
    const back = this.vertical() ? 'ArrowUp' : 'ArrowLeft';
    const next = e.key === fwd ? (i + 1) % ids.length : e.key === back ? (i - 1 + ids.length) % ids.length : e.key === 'Home' ? 0 : e.key === 'End' ? ids.length - 1 : -1;
    if (next < 0) return;
    e.preventDefault();
    this.selected.set(ids[next]);
    this.buttons()[next]?.nativeElement.focus();
  }
}
