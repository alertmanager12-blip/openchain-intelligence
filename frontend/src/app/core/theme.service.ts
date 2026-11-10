import { DOCUMENT } from '@angular/common';
import { Injectable, computed, effect, inject, signal } from '@angular/core';

export type ThemePref = 'system' | 'light' | 'dark';
const KEY = 'oci.theme';

/** Theme preference persisted per browser; dark by default, "system" follows prefers-color-scheme live. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  private readonly media = this.doc.defaultView?.matchMedia?.('(prefers-color-scheme: dark)') ?? null;
  private readonly systemDark = signal(this.media?.matches ?? false);
  readonly pref = signal<ThemePref>(this.read());
  readonly effective = computed<'light' | 'dark'>(() => (this.pref() === 'system' ? (this.systemDark() ? 'dark' : 'light') : (this.pref() as 'light' | 'dark')));

  constructor() {
    this.media?.addEventListener?.('change', (e) => this.systemDark.set(e.matches));
    effect(() => {
      this.doc.documentElement.dataset['theme'] = this.effective();
      try {
        localStorage.setItem(KEY, this.pref());
      } catch {
        /* not persisted */
      }
    });
  }

  toggle(): void {
    this.pref.set(this.effective() === 'dark' ? 'light' : 'dark');
  }

  private read(): ThemePref {
    try {
      const v = localStorage.getItem(KEY);
      return v === 'light' || v === 'dark' || v === 'system' ? v : 'dark';
    } catch {
      return 'dark';
    }
  }
}
