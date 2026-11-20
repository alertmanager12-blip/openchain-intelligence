import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { I18nService } from '../../core/i18n/i18n.service';
import { LandingPage } from './landing';

describe('Landing page in French', () => {
  let f: ComponentFixture<LandingPage>;
  let el: HTMLElement;

  beforeEach(async () => {
    localStorage.setItem('oci.lang', 'fr');
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    f = TestBed.createComponent(LandingPage);
    el = f.nativeElement;
    document.body.appendChild(el);
    await f.whenStable();
  });
  afterEach(() => { f.destroy(); localStorage.removeItem('oci.lang'); document.documentElement.lang = 'en'; });

  it('writes the hero, the capabilities, the steps, the developers and the closing in French', () => {
    expect(el.querySelector('h1')?.textContent).toContain('Voyez au-delà des transactions blockchain.');
    expect(el.querySelector('oci-cs-hero .cs-btn--primary')?.textContent).toContain('Explorer la plateforme');
    expect(el.querySelector('#cap-title')?.textContent).toContain('Comprendre. Prédire. Enquêter. Agir.');
    expect(el.querySelector('#panel-understand')?.textContent).toContain('Analyse de portefeuille');
    const steps = [...el.querySelectorAll('#how-it-works li h3')].map((h) => h.textContent?.trim());
    expect(steps).toEqual(['Connecter', 'Analyser', 'Détecter', 'Réagir']);
    expect(el.querySelector('#dev-title')?.textContent).toContain('Une intelligence qui s’intègre à votre écosystème.');
    expect(el.querySelector('#final-title')?.textContent).toContain('Rendre l’intelligence blockchain ouverte à tous.');
    expect(el.querySelector('.final .cs-btn--primary')?.textContent).toContain('Commencer');
    expect(el.textContent).toContain('pas la preuve d’un acte répréhensible');
  });

  it('puts the language switch in the landing header and re-renders in English', async () => {
    const sw = el.querySelector('.cs-top oci-lang-switch');
    expect(sw).not.toBeNull();
    TestBed.inject(I18nService).set('en');
    f.detectChanges();
    await f.whenStable();
    expect(el.querySelector('h1')?.textContent).toContain('See Beyond Blockchain Transactions.');
    expect(el.querySelector('#how-it-works li h3')?.textContent).toContain('Connect');
    expect(el.querySelector('.cs-top')?.textContent).toContain('Sign in');
  });
});
