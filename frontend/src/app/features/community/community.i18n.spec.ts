import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { CommunityPage } from './community';

const label = { id: 'l1', address: '0x00000000000000000000000000000000000000ab', network: 'ethereum', category: 'mixer', name: 'Mixer pool', source: 'community',
  reliability: 0.7, evidenceLevel: 'RISK_SIGNAL', createdAt: '2026-11-10T10:00:00Z' };
const mine = { id: 'r1', address: '0x00000000000000000000000000000000000000cd', network: 'ethereum', category: 'phishing', description: 'x', reference: '', status: 'PENDING', createdAt: '2026-11-18T10:00:00Z' };

describe('Community labels screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ labels: () => of({ items: [label], nextCursor: null }), labelReports: () => of({ items: [mine], nextCursor: null }) } as Record<string, unknown>,
      { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), MATERIAL_DEFAULTS, { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes labels, form and validation in French and switches back without navigation', async () => {
    const f = TestBed.createComponent(CommunityPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Étiquettes communautaires');
    expect(el.textContent).toContain('Signaler une adresse');
    expect(el.textContent).toContain('1 affichée');
    expect(el.textContent).toContain('signal de risque, un schéma et non une preuve');
    expect(el.textContent).toContain('En attente');
    expect(el.textContent).toContain('hameçonnage');
    expect(el.textContent).toContain('70 %'.replace(' ', ' '));
    f.componentInstance.send();
    await settle(f);
    expect(el.textContent).toContain('Saisissez l’adresse que vous signalez.');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Community labels');
    expect(el.textContent).toContain('Enter the address you are reporting.');
    expect(el.textContent).toContain('Pending');
    f.destroy();
  });
});
