import { TestBed } from '@angular/core/testing';
import { en } from './en';
import { fr } from './fr';
import { isWellFormedIcu } from './icu';
import { I18nService, Key, Lang, translate } from './i18n.service';

/** Spec 2026-11-19 §1: language from the stored choice, else the browser; switched live; every key in both languages. */
describe('I18nService', () => {
  const nav = Object.getOwnPropertyDescriptor(navigator, 'language');
  function browser(lang: string) { Object.defineProperty(navigator, 'language', { value: lang, configurable: true }); }
  beforeEach(() => { try { localStorage.clear(); } catch { /* no storage */ } });
  afterEach(() => { if (nav) Object.defineProperty(navigator, 'language', nav); else delete (navigator as { language?: string }).language; });

  it('follows a French browser for a new visitor', () => {
    browser('fr-FR');
    expect(TestBed.inject(I18nService).lang()).toBe('fr');
  });

  it('defaults to English for any other browser language', () => {
    browser('de-DE');
    expect(TestBed.inject(I18nService).lang()).toBe('en');
  });

  it('keeps a stored choice over the browser', () => {
    browser('fr-FR');
    localStorage.setItem('oci.lang', 'en');
    expect(TestBed.inject(I18nService).lang()).toBe('en');
  });

  it('switches live, stores the choice and sets the document language', () => {
    browser('en-US');
    const i = TestBed.inject(I18nService);
    expect(i.t('overview.title')).toBe('Overview');
    i.set('fr');
    expect(i.lang()).toBe('fr');
    expect(i.t('overview.title')).toBe("Vue d'ensemble");
    expect(localStorage.getItem('oci.lang')).toBe('fr');
    expect(document.documentElement.lang).toBe('fr');
  });

  it('fills named parameters, formatting numbers for the language', () => {
    browser('fr-FR');
    const i = TestBed.inject(I18nService);
    expect(i.t('overview.allOpen', { count: 1385 })).toBe('Voir les 1 385 alertes ouvertes');
    i.set('en');
    expect(i.t('overview.allOpen', { count: 1385 })).toBe('See all 1,385 open alerts');
  });

  it('survives a browser that refuses storage', () => {
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    const put = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    browser('fr-CA');
    const i = TestBed.inject(I18nService);
    expect(i.lang()).toBe('fr');
    expect(() => i.set('en')).not.toThrow();
    get.mockRestore();
    put.mockRestore();
  });
});

describe('dictionaries', () => {
  const keys = Object.keys(en) as Key[];
  const sample = { count: 2, n: 2, name: 'x', network: 'TRON', when: 'x', what: 'x', value: 'x', id: 'x', label: 'x', host: 'x', sim: 1, total: 3 };
  const render = (e: unknown, lang: Lang) => (typeof e === 'function' ? (e as (p: object, l: Lang) => string)(sample, lang) : String(e));

  it('has every English key in French, never empty', () => {
    for (const k of keys) {
      expect(fr[k], k).toBeDefined();
      expect(render(fr[k], 'fr').trim().length, k).toBeGreaterThan(0);
    }
  });

  it('leaves no parameter unfilled once rendered', () => {
    for (const k of keys) {
      for (const lang of ['en', 'fr'] as const) {
        const filled = translate(lang, k, sample);
        expect(filled, `${lang} ${k}`).not.toMatch(/\{\w+\}/);
      }
    }
  });

  it('every entry is a well-formed message', () => {
    for (const [lang, d] of [['en', en], ['fr', fr]] as const) {
      for (const k of keys) {
        const e = d[k];
        if (typeof e === 'string') expect(isWellFormedIcu(e), `${lang} ${k}`).toBe(true);
      }
    }
  });
});
