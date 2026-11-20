import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { ExplorerPage } from './explorer';

const HASH = '0x' + 'ab'.repeat(32);
const FROM = '0xfa4ede30000000000000000000000000000000cc';
const analysis = {
  transaction: { hash: HASH, network: 'ethereum', from: FROM, to: null, value: '1000000000000000000', fee: '21000', nativeSymbol: 'ETH', nativeDecimals: 18,
    blockNumber: 1234567, timestamp: new Date(Date.now() - 7200_000).toISOString(), success: true, methodSelector: null },
  transfers: [], confirmations: 12, finalized: false,
  signals: [{ id: 's1', type: 'RAPID_DRAIN', severity: 'HIGH', evidenceLevel: 'RISK_SIGNAL', title: 'Rapid drain', evidence: [{ description: 'Engine evidence sentence' }] }],
};

describe('Explorer screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ transactionAnalysis: () => of(analysis), graph: () => of({ root: FROM, nodes: [], edges: [], truncated: false }) } as Record<string, unknown>,
      { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes the search form and the formats in French', async () => {
    const f = TestBed.createComponent(ExplorerPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Explorateur');
    expect(el.querySelector('form')?.getAttribute('aria-label')).toBe('Rechercher une adresse ou une transaction');
    expect(el.textContent).toContain('Adresse ou hash de transaction');
    expect(el.textContent).toContain('Détecter d’après le format');
    expect(el.textContent).toContain('Ce que vous pouvez rechercher');
    expect(el.textContent).toContain('0x + 40 caractères hexadécimaux');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('form')?.getAttribute('aria-label')).toBe('Search an address or a transaction');
    expect(el.textContent).toContain('What you can search');
    f.destroy();
  });

  it('writes a transaction in French, the evidence sentence of the engine in English, and switches back', async () => {
    const f = TestBed.createComponent(ExplorerPage);
    f.componentRef.setInput('q', HASH);
    f.componentRef.setInput('network', 'ethereum');
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Réussie');
    expect(el.textContent).toContain('Création de contrat');
    expect(el.textContent).toContain('Pas encore définitive');
    expect(el.textContent).toContain('Cette transaction n’a déplacé aucun jeton.');
    expect(el.textContent).toContain('Flux de l’expéditeur, un saut');
    expect(el.textContent).toContain('il y a 2 h');
    expect(el.textContent).toContain('1\u202f234\u202f567');
    expect(el.querySelector('.sig [lang="en"]')?.textContent).toContain('Engine evidence sentence');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.textContent).toContain('Succeeded');
    expect(el.textContent).toContain('1,234,567');
    expect(el.querySelector('.sig [lang="en"]')).toBeNull();
    f.destroy();
  });
});
