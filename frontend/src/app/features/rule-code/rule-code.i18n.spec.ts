import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of, throwError } from 'rxjs';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { RuleCodePage } from './rule-code';

const rule = { id: 'large-usdt-transfer', name: 'Large USDT transfer', description: 'Flags a single large USDT transfer.', enabled: true, severity: 'HIGH', yaml: '', version: 3,
  updatedAt: '2026-11-19T10:00:00Z', translations: { fr: { name: 'Gros transfert d’USDT', description: 'Signale un seul gros transfert d’USDT.' } } };
const inert = { id: 'contract-exploit', name: 'Contract exploit', enabled: false, severity: 'CRITICAL', yaml: '', version: 1, updatedAt: '2026-11-19T10:00:00Z',
  unreachableReasons: ["field 'contract.outflow_ratio' is never populated"], unreachableCodes: [{ code: 'unreachable.field_not_produced', params: { field: 'contract.outflow_ratio' } }] };

describe('Rules as code in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const bad = new ApiError(422, 'Validation failed', null, 'about:blank', null,
      [{ field: '/conditions/rules/0/field', message: 'unknown field "transaction.nope"', code: 'rule.unknown_field', params: { field: 'transaction.nope' } }]);
    const stub = new Proxy({ rules: () => of({ items: [rule, inert], nextCursor: null }), ruleNoise: () => of([]), validateRule: () => throwError(() => bad) } as Record<string, unknown>,
      { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), MATERIAL_DEFAULTS, { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes the editor, the server error and the stored rules in French, and switches back', async () => {
    const f = TestBed.createComponent(RuleCodePage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Règles sous forme de code');
    expect(el.textContent).toContain('Valider sur le serveur');
    expect(el.textContent).toContain('Contrôle local');
    expect(el.textContent).toContain('Règles enregistrées');
    expect(el.textContent).toContain('Gros transfert d’USDT');
    expect(el.textContent).toContain('Signale un seul gros transfert d’USDT.');
    expect(el.querySelector('[data-testid="unreachable"]')?.textContent).toMatch(/Ne peut jamais se déclencher sur cette instance\u202f: le champ .{1,3}contract\.outflow_ratio.{1,3} n'est jamais renseigné/);

    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Valider sur le serveur')) as HTMLButtonElement).click();
    await settle(f);
    expect(el.querySelector('[role="alert"]')?.textContent).toMatch(/\/conditions\/rules\/0\/field\u202f: champ inconnu .{1,3}transaction\.nope.{1,3}/);

    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Rules as code');
    expect(el.textContent).toContain('Large USDT transfer');
    expect(el.textContent).toContain('Flags a single large USDT transfer.');
    expect(el.querySelector('[data-testid="unreachable"]')?.textContent).toContain("field 'contract.outflow_ratio' is never populated");
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('/conditions/rules/0/field: unknown field');
    f.destroy();
  });
});
