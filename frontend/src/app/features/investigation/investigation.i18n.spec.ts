import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { GraphInspector } from './graph-inspector';
import { InvestigationPage } from './investigation';

describe('Fund flow graph screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({} as Record<string, unknown>, { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes the form, the toolbar and the legend in French, and switches back', async () => {
    const f = TestBed.createComponent(InvestigationPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('.crumbs')?.textContent).toContain('Graphe des flux de fonds');
    expect(el.textContent).toContain('Adresse racine');
    expect(el.textContent).toContain('Charger le graphe');
    expect(el.textContent).toContain('Sortant');
    expect(el.textContent).toContain('2 sauts');
    expect(el.textContent).toContain('Tous les jetons');
    expect(el.textContent).toContain('Aucun graphe pour l’instant');
    const legend = el.querySelector('.legend');
    expect(legend?.getAttribute('aria-label')).toBe('Légende');
    expect(legend?.textContent).toContain('Critique');
    expect(legend?.textContent).toContain('Racine, les anneaux sont des sauts');
    expect(legend?.textContent).toContain('Vers la racine');
    expect(legend?.textContent).toContain('Épaisseur du trait :');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.textContent).toContain('Load graph');
    expect(el.querySelector('.legend')?.textContent).toContain('Root, rings are hops');
    expect(el.textContent).toContain('2 hops');
    f.destroy();
  });

  it('writes the inspector in French and the path sentence of the engine with lang="en"', async () => {
    const f = TestBed.createComponent(GraphInspector);
    f.componentRef.setInput('node', { id: 'n', address: '0xbb4ede30000000000000000000000000000000dd', network: 'ethereum', label: null, riskLevel: 'HIGH', isContract: true, depth: 2 });
    f.componentRef.setInput('pathInfo', { key: 'graph.path.found', params: { count: 2 }, engine: 'Path note of the engine' });
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Sauts depuis la racine');
    expect(el.textContent).toContain('Contrat intelligent');
    expect(el.textContent).toContain('Étendre les contreparties');
    expect(el.textContent).toContain('2 transferts');
    expect(el.querySelector('[lang="en"]')?.textContent).toContain('Path note of the engine');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.textContent).toContain('Expand counterparties');
    expect(el.textContent).toContain('Smart contract');
    f.destroy();
  });
});
