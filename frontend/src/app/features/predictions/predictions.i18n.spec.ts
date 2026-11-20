import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NEVER, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { PredictionsPage } from './predictions';

const item = { id: 'p1', level: 'PREDICTED', type: 'WAKE_CONTINUED_OUTFLOW', network: 'ethereum', subject: '0xabc', confidence: null,
  condition: 'it sends more than 1000 USDT onward', limits: ['Based on 3 past events'], signalIds: [], issuedAt: '2026-11-18T10:00:00Z', expiresAt: '2026-11-25T10:00:00Z',
  model: 'm', outcome: 'OPEN', outcomeEvidence: [], outcomeReason: null, evaluatedAt: null };
const rate = { type: 'WAKE_CONTINUED_OUTFLOW', realised: 6, notRealised: 4, undetermined: 2, rate: 0.6, established: false, minEvaluated: 30 };
const sim = { level: 'SIMULATED', hypothesis: { network: 'tron', address: 'T1', category: 'SANCTIONED' }, statement: 'Simulation: 1 wallet(s) would be exposed.',
  coverage: 'PARTIAL', coverageReasons: [], candidates: 50, truncated: true,
  exposed: [{ address: 'T2', hops: 2, level: 'SIMULATED', txHashes: [], confidence: 0.5, alreadyExposedTo: ['MIXER'] }] };

describe('Predictions screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ predictionRates: () => of([rate]), predictions: () => of([item]), whatIf: () => of(sim) } as Record<string, unknown>,
      { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes predictions and the what-if in French, the explanations with lang="en", and switches back', async () => {
    const f = TestBed.createComponent(PredictionsPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Prédictions et scénarios');
    expect(el.textContent).toContain('60 % jusqu’ici, non établi');
    expect(el.textContent).toContain('Si l’hypothèse se vérifie');
    expect(el.textContent).toContain('non calibrée');
    expect(el.textContent).toContain('Adresse supposée listée');
    const engine = () => Array.from(el.querySelectorAll('[lang="en"]')).map((n) => n.textContent);
    expect(engine().some((t) => t?.includes('it sends more than 1000 USDT onward'))).toBe(true);
    (el.querySelector('#sim-addr') as HTMLInputElement).value = 'T1';
    (el.querySelector('#sim-addr') as HTMLInputElement).dispatchEvent(new Event('input'));
    f.detectChanges();
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Simuler')) as HTMLButtonElement).click();
    await settle(f);
    expect(engine().some((t) => t?.includes('Simulation: 1 wallet(s) would be exposed.'))).toBe(true);
    expect(el.textContent).toContain('Couverture de l’adresse : partielle');
    expect(el.textContent).toContain('2 sauts');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Predictions and what-if');
    expect(el.textContent).toContain('60% so far, not established');
    expect(el.textContent).toContain('Coverage of the address: partial');
    expect(el.textContent).toContain('2 hops');
    expect(el.querySelector('.grid [lang="en"]')).toBeNull();
    f.destroy();
  });
});
