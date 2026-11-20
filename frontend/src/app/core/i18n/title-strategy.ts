import { Injectable, effect, inject, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { I18nService, Lang, hasKey, translate } from './i18n.service';

/**
 * Browser tab title in the console language. A route's `title` is a dictionary key: a page name (`nav.alerts`) reads
 * "Alerts – ChainSentinel" / "Alertes – ChainSentinel"; a key whose text already names ChainSentinel (the landing page)
 * is the whole title. A language switch rewrites the title of the current page without a navigation.
 */
@Injectable({ providedIn: 'root' })
export class I18nTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly i18n = inject(I18nService);
  private readonly key = signal<string | null>(null);

  constructor() {
    super();
    effect(() => { const key = this.key(), lang = this.i18n.lang(); if (key !== null) this.apply(key, lang); });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const key = this.buildTitle(snapshot);
    if (key === undefined) return;
    this.key.set(key);
    this.apply(key, this.i18n.lang());
  }

  private apply(key: string, lang: Lang): void {
    if (!hasKey(key)) { this.title.setTitle(key); return; }
    const text = translate(lang, key);
    this.title.setTitle(text.includes('ChainSentinel') ? text : translate(lang, 'shell.docTitle', { page: text }));
  }
}
