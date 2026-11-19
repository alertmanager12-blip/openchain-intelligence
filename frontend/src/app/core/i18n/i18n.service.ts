import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';
import { en } from './en';
import { fr } from './fr';
import { fmtNumber } from './format';

export type Lang = 'en' | 'fr';
export type Params = Record<string, string | number>;
/** A dictionary entry: a string with {named} parameters, or a function for grammar a template cannot do. */
export type Entry = string | ((p: Params, lang: Lang) => string);
export type Key = keyof typeof en;
export type Dictionary = { readonly [K in Key]: Entry };

const KEY = 'oci.lang';
const DICTS: Record<Lang, Dictionary> = { en, fr };

/**
 * Console language (spec 2026-11-19 §1): the stored choice, else French for a French browser, else English. Switching
 * re-renders every template that reads {@link t} (it reads the {@link lang} signal), without a reload.
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly doc = inject(DOCUMENT);
  private readonly current = signal<Lang>(this.initial());
  readonly lang = this.current.asReadonly();

  constructor() { this.doc.documentElement.lang = this.current(); }

  set(l: Lang): void {
    this.current.set(l);
    this.doc.documentElement.lang = l;
    try { localStorage.setItem(KEY, l); } catch { /* not persisted */ }
  }

  t(key: Key, params?: Params): string {
    const lang = this.current();
    const e = DICTS[lang][key];
    if (typeof e === 'function') return e(params ?? {}, lang);
    if (!params) return e;
    return e.replace(/\{(\w+)\}/g, (m, p: string) => {
      const v = params[p];
      return v === undefined ? m : typeof v === 'number' ? fmtNumber(v, lang) : v;
    });
  }

  private initial(): Lang {
    try {
      const v = localStorage.getItem(KEY);
      if (v === 'en' || v === 'fr') return v;
    } catch { /* fall through to the browser */ }
    return (this.doc.defaultView?.navigator?.language ?? '').toLowerCase().startsWith('fr') ? 'fr' : 'en';
  }
}
