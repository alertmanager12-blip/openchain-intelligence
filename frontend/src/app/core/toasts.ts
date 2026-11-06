import { Injectable, signal } from '@angular/core';

export type ToastTone = 'success' | 'error' | 'info';

export interface Toast {
  id: number;
  /** Uses the verb of the action that triggered it: "Alert acknowledged" after "Acknowledge alert". */
  message: string;
  detail?: string;
  tone: ToastTone;
}

/** Short confirmations of completed actions. Rendered by layout/toast-outlet.ts in a polite live region. */
@Injectable({ providedIn: 'root' })
export class Toasts {
  private seq = 0;
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();
  readonly items = signal<Toast[]>([]);
  /** Visible time; errors stay twice as long. Paused while the pointer or focus is on the toast. */
  readonly duration = 4500;

  show(message: string, opts: { detail?: string; tone?: ToastTone } = {}): number {
    const t: Toast = { id: ++this.seq, message, detail: opts.detail, tone: opts.tone ?? 'success' };
    this.items.update((l) => [...l.slice(-3), t]);
    this.schedule(t);
    return t.id;
  }

  success(message: string, detail?: string): number {
    return this.show(message, { detail, tone: 'success' });
  }

  error(message: string, detail?: string): number {
    return this.show(message, { detail, tone: 'error' });
  }

  dismiss(id: number): void {
    clearTimeout(this.timers.get(id));
    this.timers.delete(id);
    this.items.update((l) => l.filter((t) => t.id !== id));
  }

  pause(id: number): void {
    clearTimeout(this.timers.get(id));
    this.timers.delete(id);
  }

  resume(id: number): void {
    const t = this.items().find((x) => x.id === id);
    if (t) this.schedule(t);
  }

  private schedule(t: Toast): void {
    clearTimeout(this.timers.get(t.id));
    this.timers.set(t.id, setTimeout(() => this.dismiss(t.id), t.tone === 'error' ? this.duration * 2 : this.duration));
  }
}
