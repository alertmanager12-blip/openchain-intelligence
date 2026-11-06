import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Toasts } from '../core/toasts';

/** Toast stack, bottom right. A single polite live region announces each confirmation once. */
@Component({
  selector: 'oci-toast-outlet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="stack" role="status" aria-live="polite" aria-label="Notifications">
      @for (t of toasts.items(); track t.id) {
        <li class="toast t-{{ t.tone }}" (mouseenter)="toasts.pause(t.id)" (mouseleave)="toasts.resume(t.id)" (focusin)="toasts.pause(t.id)" (focusout)="toasts.resume(t.id)">
          <span class="ico" aria-hidden="true">
            @if (t.tone === 'error') { <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="8" cy="8" r="6.2"/><path d="M8 4.8v3.6M8 10.9v.3"/></svg> }
            @else { <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m3.5 8.4 3 3 6-6.6"/></svg> }
          </span>
          <div class="txt"><strong>{{ t.message }}</strong>@if (t.detail) { <span>{{ t.detail }}</span> }</div>
          <button class="btn btn--ghost btn--sm btn--icon" type="button" (click)="toasts.dismiss(t.id)" aria-label="Dismiss notification">
            <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m4 4 8 8M12 4l-8 8"/></svg>
          </button>
        </li>
      }
    </ol>
  `,
  styles: `
    .stack { position: fixed; right: 16px; bottom: 16px; z-index: 90; list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; width: min(380px, calc(100vw - 32px)); }
    .toast { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 10px; align-items: start; padding: 10px 8px 10px 14px; border-radius: var(--r-panel);
      background: var(--surface); border: 1px solid var(--line); box-shadow: var(--shadow-overlay); animation: in 0.18s var(--ease); }
    .ico { width: 20px; height: 20px; display: grid; place-items: center; border-radius: 50%; margin-top: 1px; }
    .ico svg { width: 14px; height: 14px; }
    .t-success .ico { color: var(--accent-ink); background: var(--accent); }
    .t-info .ico { color: var(--ink-2); background: var(--well); }
    .t-error { border-color: color-mix(in srgb, var(--risk-critical) 50%, var(--line)); }
    .t-error .ico { color: var(--danger); }
    .txt { display: grid; gap: 2px; font-size: 14px; padding-top: 2px; }
    .txt span { color: var(--ink-2); font-size: 13px; }
    @keyframes in { from { opacity: 0; transform: translateY(6px); } }
  `,
})
export class ToastOutlet {
  protected readonly toasts = inject(Toasts);
}
