import { ChangeDetectionStrategy, Component, DestroyRef, TemplateRef, effect, inject, model, untracked, viewChild } from '@angular/core';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { NAV_ITEMS } from './navigation';

export interface Shortcut {
  keys: string[];
  /** Keys pressed one after the other (G then A) rather than together. */
  sequence?: boolean;
  label: string;
}

const MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

/** Every keyboard shortcut of the console, grouped as shown in the help dialog. Shared with the tests. */
export const SHORTCUTS: { group: string; items: Shortcut[] }[] = [
  { group: 'General', items: [
    { keys: [MAC ? '⌘' : 'Ctrl', 'K'], label: 'Search an address, a transaction or a page' },
    { keys: ['?'], label: 'Show keyboard shortcuts' },
    { keys: ['['], label: 'Collapse or expand the sidebar' },
  ] },
  { group: 'Go to', items: NAV_ITEMS.filter((i) => i.key).map((i) => ({ keys: ['G', i.key!.toUpperCase()], sequence: true, label: i.label })) },
  { group: 'Alerts inbox', items: [
    { keys: ['J'], label: 'Next alert' },
    { keys: ['K'], label: 'Previous alert' },
    { keys: ['Enter'], label: 'Open the highlighted alert' },
    { keys: ['E'], label: 'Acknowledge the open alert' },
    { keys: ['Esc'], label: 'Close the alert pane' },
  ] },
];

/** The "?" dialog listing all shortcuts. Rendered in a MatDialog (focus trap, Escape, focus return). */
@Component({
  selector: 'oci-shortcut-help',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ng-template #dialogTpl>
      <div class="help">
        <header><h2 id="help-title">Keyboard shortcuts</h2><button type="button" class="x" (click)="close()" aria-label="Close"><kbd>Esc</kbd></button></header>
        <div class="groups">
          @for (g of groups; track g.group) {
            <section [attr.aria-labelledby]="'hg-' + $index">
              <h3 [id]="'hg-' + $index">{{ g.group }}</h3>
              <dl>
                @for (s of g.items; track s.label) {
                  <div class="r"><dt>{{ s.label }}</dt>
                    <dd>@for (k of s.keys; track $index) { @if (!$first && s.sequence) { <span class="then">then</span> }<kbd>{{ k }}</kbd> }</dd></div>
                }
              </dl>
            </section>
          }
        </div>
      </div>
    </ng-template>
  `,
  styles: `
    .help { background: var(--surface); animation: pop 0.14s var(--ease); }
    @keyframes pop { from { opacity: 0; transform: scale(0.98); } }
    header { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--line); }
    h2 { font-size: 14px; }
    .x { border: 0; background: transparent; padding: 2px; cursor: pointer; border-radius: 4px; }
    .groups { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 4px 24px; padding: 8px 16px 16px; max-height: min(70vh, 560px); overflow-y: auto; }
    h3 { font-size: 12px; font-weight: 500; color: var(--muted); margin: 10px 0 4px; }
    dl { margin: 0; }
    .r { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 30px; border-bottom: 1px solid var(--line-soft); }
    dt { font-size: 13px; color: var(--ink); }
    dd { margin: 0; display: inline-flex; align-items: center; gap: 3px; flex: none; }
    .then { font-size: 11px; color: var(--muted); margin: 0 2px; }
  `,
})
export class ShortcutHelp {
  readonly open = model(false);
  readonly groups = SHORTCUTS;
  private readonly dialog = inject(MatDialog);
  private readonly tpl = viewChild.required<TemplateRef<unknown>>('dialogTpl');
  private ref: MatDialogRef<unknown> | null = null;
  private destroyed = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.ref?.close();
    });
    effect(() => {
      const open = this.open();
      untracked(() => {
        if (open && !this.ref) {
          this.ref = this.dialog.open(this.tpl(), {
            ariaLabelledBy: 'help-title', panelClass: 'help-panel', width: '620px', maxWidth: 'calc(100vw - 24px)', autoFocus: 'dialog', restoreFocus: true, ariaModal: true,
          });
          this.ref.afterClosed().subscribe(() => {
            this.ref = null;
            if (!this.destroyed) this.open.set(false);
          });
        } else if (!open && this.ref) {
          this.ref.close();
        }
      });
    });
  }

  close(): void {
    this.ref?.close();
  }
}
