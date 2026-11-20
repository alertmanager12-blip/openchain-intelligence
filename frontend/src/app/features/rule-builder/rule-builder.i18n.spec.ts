import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { DEFAULT_FIELDS } from '../../core/rules/rule-model';
import { RuleBuilderPage } from './rule-builder';

describe('Rule builder in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ fieldCatalog: () => of(DEFAULT_FIELDS), eventTypes: () => of([]), rules: () => of({ items: [], nextCursor: null }) } as Record<string, unknown>,
      { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), MATERIAL_DEFAULTS, { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes the steps, the operators, the field descriptions and the validation errors in French, and switches back', async () => {
    const f = TestBed.createComponent(RuleBuilderPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    const page = f.componentInstance;
    expect(el.querySelector('h1')?.textContent).toContain('Créateur de règles');
    expect(el.textContent).toContain('Portée');
    expect(el.textContent).toContain('Fenêtre');
    expect(el.textContent).toContain('Aperçu YAML');

    page.patch({ name: '' });
    await settle(f);
    expect(el.textContent).toContain('le nom est obligatoire');
    expect(el.querySelector('[role="tab"][aria-selected="true"]')?.getAttribute('aria-label')).toMatch(/^1\. Portée, 1 problème$/);

    page.step.set('conditions');
    await settle(f);
    expect(el.textContent).toContain('égal à');
    expect(el.textContent).toContain('Symbole du jeton');
    expect(el.textContent).toContain('Ajouter une condition');

    page.step.set('window');
    page.patch({ window: { duration: 'x', aggregate: 'sum', field: 'transaction.amount', groupBy: ['transaction.from'], op: 'gte', value: '5' } });
    await settle(f);
    expect(el.textContent).toContain('Somme de transaction.amount');
    expect(el.textContent).toContain('d’au moins');

    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Rule builder');
    expect(el.textContent).toContain('Sum of transaction.amount');
    expect(el.textContent).toContain('at least');
    page.step.set('conditions');
    await settle(f);
    expect(el.textContent).toContain('equals');
    f.destroy();
  });
});
