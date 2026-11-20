import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { I18nService, translate } from '../../core/i18n/i18n.service';
import { WalletAnalysis } from '../../core/models';
import { formatUnits } from '../../shared/format';
import { WalletPage } from './wallet';
import { walletVerdict } from './wallet-verdict';

const ADDR = '0xfa4ede30000000000000000000000000000000cc';
const analysis = {
  address: ADDR, network: 'tron', firstSeen: '2026-01-02T00:00:00Z', lastSeen: '2026-11-10T00:00:00Z', txCount: 12345, isContract: false,
  labels: [{ id: 'l1', category: 'MIXER', name: 'Some mixer', source: 'community', reliability: 0.8, evidenceLevel: 'RISK_SIGNAL' }],
  balances: [], flows: [], activity: [],
  counterparties: [{ address: '0xaaa0000000000000000000000000000000000001', label: null, direction: 'IN', txCount: 3, token: { symbol: 'USDT', decimals: 6 }, rawIn: '1500000', rawOut: '0', riskLevel: 'LOW' }],
  signals: [{ id: 's1', type: 'RAPID_DRAIN', severity: 'HIGH', evidenceLevel: 'RISK_SIGNAL', confidence: 0.7, detectorId: 'rapid-drain@1', detectedAt: '2026-11-10T00:00:00Z',
    title: 'Rapid drain', summary: 'Sent 95% of its balance within 2 minutes', evidence: [{ kind: 'TRANSFER', description: 'Evidence description words', source: 'tron' }] }],
  risk: { address: ADDR, network: 'tron', score: 40, level: 'MEDIUM', method: 'noisy-or', methodSummary: 'Method sentence of the engine', modelVersion: 't', computedAt: new Date().toISOString(), contributions: [] },
  history: null,
  limitations: ['No historical balance from TronGrid.'],
  limitationCodes: [{ code: 'limitation.tron_no_balance' }],
} as unknown as WalletAnalysis;

describe('Wallet screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const api = {
      walletAnalysis: () => of(analysis),
      walletExposure: () => of({ address: ADDR, network: 'tron', direct: [], indirect: [] }),
      walletRelations: () => of({ address: ADDR, network: 'tron', relations: [], limits: [] }),
      walletTransactions: () => of({ items: [], nextCursor: null }),
      watchlist: () => of([]),
      sanctionsLists: () => of({ items: [] }),
    };
    const stub = new Proxy(api as Record<string, unknown>, { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));

  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };
  async function mount() {
    const f = TestBed.createComponent(WalletPage);
    f.componentRef.setInput('network', 'tron');
    f.componentRef.setInput('address', ADDR);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    return f;
  }

  it('writes the headings, the tabs and the limitation from its code in French', async () => {
    const f = await mount();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('oci-gauge')?.getAttribute('aria-label')).toContain('Score de risque');
    expect(el.textContent).toContain('Contreparties');
    expect(el.querySelector('[data-testid="limitations"]')?.textContent).toContain('Non visible sur ce réseau');
    expect(el.querySelector('[data-testid="limitations"]')?.textContent).toContain('Aucun solde historique');
    expect(el.querySelector('[data-testid="limitations"]')?.textContent).not.toContain('No historical balance');
    expect(el.querySelector('.stats')?.textContent).toContain('12 345');
    f.destroy();
  });

  it('shows the engine method sentence inside its label with lang="en", and re-renders in English', async () => {
    const f = await mount();
    const el = f.nativeElement as HTMLElement;
    const engine = el.querySelector('.score-tab [lang="en"]');
    expect(engine?.textContent).toContain('Method sentence of the engine');
    expect(el.querySelector('.score-tab')?.textContent).toContain('Formulation du moteur');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.textContent).toContain('Counterparties');
    expect(el.textContent).not.toContain('Contreparties');
    expect(el.querySelector('[data-testid="limitations"]')?.textContent).toContain('Not visible on this network');
    expect(el.querySelector('[data-testid="limitations"]')?.textContent).toContain('No historical balance');
    f.destroy();
  });

  it('writes the verdict in French and keeps the engine summaries English', () => {
    const v = walletVerdict(analysis, 'fr');
    expect(v.sentence).toBe('1 signal de risque sur ce portefeuille ; le plus fort : vidage rapide.');
    expect(v.lines[0]).toMatchObject({ text: 'Répertorié comme un mixeur par community', detail: 'signal de risque' });
    expect(v.lines[1]).toMatchObject({ text: 'Sent 95% of its balance within 2 minutes', textLang: 'en' });
  });

  it('formats amounts and plurals in French', () => {
    expect(formatUnits('1234567890', 6, 2, 'fr')).toBe('1 234,56');
    expect(translate('fr', 'wallet.transfers', { n: 0 })).toBe('0 transfert');
    expect(translate('fr', 'wallet.transfers', { n: 2 })).toBe('2 transferts');
  });
});
