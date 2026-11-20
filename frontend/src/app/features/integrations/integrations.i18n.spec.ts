import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { IntegrationsPage } from './integrations';

const hook = { id: 'wh_1', type: 'SLACK', name: 'SOC', target: 'https://hooks.slack.com/services/T000/B000/abcd1234', createdAt: '2026-11-10T10:00:00Z', lastDeliveryStatus: 'FAILED', lastDeliveryAt: '2026-11-19T10:00:00Z' };

describe('Integrations screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ webhooks: () => of([hook]) } as Record<string, unknown>, { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), MATERIAL_DEFAULTS, { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes connectors, form errors and the delete dialog in French and switches back', async () => {
    const f = TestBed.createComponent(IntegrationsPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Intégrations');
    expect(el.textContent).toContain('Ajouter un connecteur');
    expect(el.textContent).toContain('Point d’accès HTTPS');
    expect(el.textContent).toContain('en échec à la dernière livraison');
    f.componentInstance.model.set({ name: '', target: 'http://x' });
    f.componentInstance.create(new Event('submit'));
    await settle(f);
    expect(el.textContent).toContain('Donnez un nom au connecteur.');
    expect(el.textContent).toContain('Utilisez une URL en https://.');
    void f.componentInstance.remove(hook as never);
    await settle(f);
    const dlg = () => document.querySelector('oci-confirm-dialog')?.textContent ?? '';
    expect(dlg()).toContain('Supprimer le connecteur « SOC » ?');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(dlg()).toContain('Delete connector "SOC"?');
    expect(el.querySelector('h1')?.textContent).toContain('Integrations');
    expect(el.textContent).toContain('Give the connector a name.');
    (document.querySelector('oci-confirm-dialog button') as HTMLButtonElement).click();
    f.destroy();
  });
});
