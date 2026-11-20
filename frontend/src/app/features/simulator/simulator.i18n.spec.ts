import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { SimulatorPage } from './simulator';

const rule = { id: 'large-usdt-transfer', name: 'Large USDT transfer', enabled: true, severity: 'HIGH', yaml: 'id: x', version: 3, updatedAt: '2026-11-19T10:00:00Z',
  translations: { fr: { name: 'Gros transfert d’USDT' } } };
const result = { evaluatedEvents: 12000, matchCount: 4, estimatedTriggersPerDay: 2, permissive: false, warnings: ['Matches 0.02% of events.'],
  matches: [{ eventId: 'ethereum:0xabc:1', network: 'ethereum', eventTime: '2026-11-18T10:00:00Z', summary: '250000 USDT 0xaaaa… → 0xbbbb…', fields: { amount: '250000' } }] };

describe('Rule simulator in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ rules: () => of({ items: [rule], nextCursor: null }), testRule: () => of(result) } as Record<string, unknown>,
      { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), MATERIAL_DEFAULTS, { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes the dry run in French, the match summaries with lang="en", and switches back', async () => {
    const f = TestBed.createComponent(SimulatorPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Simulateur de règles');
    expect(el.textContent).toContain('Gros transfert d’USDT');
    expect(el.textContent).toContain('Choisissez une règle et une période');
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Lancer la simulation')) as HTMLButtonElement).click();
    await settle(f);
    expect(el.textContent).toContain('Événements évalués');
    expect(el.textContent).toContain('12 000');
    expect(el.textContent).toContain('Taux de correspondance');
    expect(el.textContent).toContain('0,03 %');
    expect(el.textContent).toContain('Événements correspondants');
    expect(el.querySelector('td [lang="en"]')?.textContent).toContain('250000 USDT');
    expect(el.querySelector('.note [lang="en"]')?.textContent).toContain('Matches 0.02% of events.');
    expect(el.textContent).toContain('Formulation du moteur');

    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Rule simulator');
    expect(el.textContent).toContain('Events evaluated');
    expect(el.textContent).toContain('12,000');
    expect(el.textContent).not.toContain("Engine's wording");
    f.destroy();
  });
});
