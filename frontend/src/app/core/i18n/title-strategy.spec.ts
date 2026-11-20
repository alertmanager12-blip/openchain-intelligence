import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Route, Router, TitleStrategy, provideRouter } from '@angular/router';
import { routes } from '../../app.routes';
import { FEATURE_ROUTES } from '../../features/feature.routes';
import { I18nService, hasKey } from './i18n.service';
import { I18nTitleStrategy } from './title-strategy';

@Component({ template: '' })
class Blank {}

/** Final review: the browser tab title follows the console language, and switches with it. */
describe('document title', () => {
  afterEach(() => { TestBed.inject(I18nService).set('en'); localStorage.removeItem('oci.lang'); });

  const setup = () => {
    TestBed.configureTestingModule({ providers: [
      provideRouter([{ path: 'alerts', title: 'nav.alerts', component: Blank }, { path: 'home', title: 'landing.docTitle', component: Blank }]),
      { provide: TitleStrategy, useClass: I18nTitleStrategy },
    ] });
    return { router: TestBed.inject(Router), i18n: TestBed.inject(I18nService), doc: document };
  };

  it('writes the page title in French, then in English after set(en), without a navigation', async () => {
    const { router, i18n, doc } = setup();
    i18n.set('fr');
    await router.navigateByUrl('/alerts');
    expect(doc.title).toBe('Alertes – ChainSentinel');
    i18n.set('en');
    TestBed.tick();
    expect(doc.title).toBe('Alerts – ChainSentinel');
  });

  it('writes the landing title in the console language', async () => {
    const { router, i18n, doc } = setup();
    i18n.set('fr');
    await router.navigateByUrl('/home');
    expect(doc.title).toBe('ChainSentinel – Le moteur d’intelligence ouvert pour la blockchain');
    i18n.set('en');
    TestBed.tick();
    expect(doc.title).toBe('ChainSentinel – The open intelligence engine for blockchain');
  });

  it('gives every console route a dictionary key as its title', () => {
    const all = (rs: Route[]): Route[] => rs.flatMap((r) => [r, ...all(r.children ?? [])]);
    const titled = all([...routes, ...FEATURE_ROUTES]).filter((r) => r.title !== undefined);
    expect(titled.length).toBeGreaterThan(15);
    for (const r of titled) expect(typeof r.title === 'string' && hasKey(r.title), `${r.path}: ${String(r.title)}`).toBe(true);
  });
});
