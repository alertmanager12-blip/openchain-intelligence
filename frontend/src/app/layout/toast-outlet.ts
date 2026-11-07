import { ChangeDetectionStrategy, Component, DestroyRef, Injector, effect, inject, untracked } from '@angular/core';
import { MatIconButton } from '@angular/material/button';
import type { MatSnackBarRef } from '@angular/material/snack-bar';
import { Toasts } from '../core/toasts';

/**
 * The toast stack shown inside a Material snack bar. The snack bar provides the overlay, the bottom-end position and
 * the polite live region (role="status"); core/toasts.ts keeps the queue, the timers and pause-on-hover.
 */
@Component({
  selector: 'oci-toast-stack',
  imports: [MatIconButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="stack" aria-label="Notifications">
      @for (t of toasts.items(); track t.id) {
        <li class="toast t-{{ t.tone }}" (mouseenter)="toasts.pause(t.id)" (mouseleave)="toasts.resume(t.id)" (focusin)="toasts.pause(t.id)" (focusout)="toasts.resume(t.id)">
          <span class="ico" aria-hidden="true">
            @if (t.tone === 'error') { <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="8" cy="8" r="6.2"/><path d="M8 4.8v3.6M8 10.9v.3"/></svg> }
            @else { <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m3.5 8.4 3 3 6-6.6"/></svg> }
          </span>
          <div class="txt"><strong>{{ t.message }}</strong>@if (t.detail) { <span>{{ t.detail }}</span> }</div>
          <button matIconButton class="sm" type="button" (click)="toasts.dismiss(t.id)" aria-label="Dismiss notification">
            <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m4 4 8 8M12 4l-8 8"/></svg>
          </button>
        </li>
      }
    </ol>
  `,
  styles: `
    .stack { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; width: min(380px, calc(100vw - 32px)); }
    .toast { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 10px; align-items: start; padding: 8px 6px 8px 14px; border-radius: var(--r-panel);
      background: var(--surface); color: var(--ink); border: 1px solid var(--line); box-shadow: var(--shadow-overlay); animation: in 0.18s var(--ease); }
    .ico { width: 20px; height: 20px; display: grid; place-items: center; border-radius: 50%; margin-top: 3px; }
    .ico svg { width: 14px; height: 14px; }
    .t-success .ico { color: var(--accent-ink); background: var(--accent); }
    .t-info .ico { color: var(--ink-2); background: var(--well); }
    .t-error { border-color: color-mix(in srgb, var(--risk-critical) 50%, var(--line)); }
    .t-error .ico { color: var(--danger); }
    .txt { display: grid; gap: 2px; font: 400 14px/1.4 var(--font); padding-top: 4px; }
    .txt strong { font-weight: 600; }
    .txt span { color: var(--ink-2); font-size: 13px; }
    @keyframes in { from { opacity: 0; transform: translateY(6px); } }
  `,
})
export class ToastStack {
  protected readonly toasts = inject(Toasts);
}

/**
 * Opens the snack bar while there is at least one toast, and closes it when the queue is empty. The snack bar module
 * (and the CDK overlay) is imported on the first toast, so it stays out of the initial bundle.
 */
@Component({
  selector: 'oci-toast-outlet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: ``,
})
export class ToastOutlet {
  private readonly toasts = inject(Toasts);
  private readonly injector = inject(Injector);
  private ref: MatSnackBarRef<ToastStack> | null = null;
  private opening = false;
  private destroyed = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.ref?.dismiss();
    });
    effect(() => {
      if (this.toasts.items().length > 0) untracked(() => void this.show());
      else untracked(() => this.ref?.dismiss());
    });
  }

  private async show(): Promise<void> {
    if (this.ref || this.opening) return;
    this.opening = true;
    const { MatSnackBar } = await import('@angular/material/snack-bar');
    this.opening = false;
    if (this.destroyed || this.ref || this.toasts.items().length === 0) return;
    this.ref = this.injector.get(MatSnackBar).openFromComponent(ToastStack, { horizontalPosition: 'end', verticalPosition: 'bottom', politeness: 'polite', panelClass: 'oci-toasts' });
    this.ref.afterDismissed().subscribe(() => (this.ref = null));
  }
}
