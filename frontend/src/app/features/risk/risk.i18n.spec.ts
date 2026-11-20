import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { RiskPage } from './risk';

const ADDR = '0xfa4ede30000000000000000000000000000000cc';
const risk = { address: ADDR, network: 'ethereum', score: 40, level: 'MEDIUM', method: 'noisy-or', methodSummary: 'Method sentence of the engine', modelVersion: 't',
  computedAt: new Date().toISOString(), contributions: [{ factor: 'signal:RAPID_DRAIN', signalId: 's', source: 'rapid-drain@1', weight: 0.5, confidence: 0.7, ageSeconds: 7200, points: 15,
    explanation: 'x', evidenceLevel: 'RISK_SIGNAL', title: 'Rapid drain', summary: 'Contribution sentence of the engine' }] };

describe('Risk screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ walletRisk: () => of(risk) } as Record<string, unknown>, { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };
  async function mount() {
    const f = TestBed.createComponent(RiskPage);
    f.componentRef.setInput('network', 'ethereum');
    f.componentRef.setInput('address', ADDR);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    return f;
  }

  it('writes the headings, the table and the legend in French, the engine sentences with lang="en"', async () => {
    const f = await mount();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Risque du portefeuille');
    expect(el.querySelector('oci-gauge')?.getAttribute('aria-label')).toBe('Score de risque du portefeuille');
    expect(el.querySelector('table')?.textContent).toContain('Facteur');
    expect(el.querySelector('table')?.textContent).toContain('70 %');
    expect(el.querySelector('.method-summary')?.textContent).toContain('Formulation du moteur');
    expect(el.querySelector('.method-summary [lang="en"]')?.textContent).toContain('Method sentence of the engine');
    expect(el.querySelector('.c-summary [lang="en"]')?.textContent).toContain('Contribution sentence of the engine');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Wallet risk');
    expect(el.querySelector('.method-summary')?.textContent).not.toContain('Formulation');
    expect(el.querySelector('table')?.textContent).toContain('70%');
    f.destroy();
  });
});
