import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Alert, RiskScore } from '../../core/models';
import { AlertDetail } from './alert-detail';

const WALLET = '0xfa4ede30000000000000000000000000000000aa';
const CP = '0xfa4ede30000000000000000000000000000000bb';
const TX = '0xfa4ede30' + 'ab'.repeat(28);

const alert = (over: Partial<Alert> = {}): Alert => ({
  id: 'al_t1', ruleId: 'rapid-drain', ruleVersion: '1', ruleName: 'Rapid drain', severity: 'HIGH', status: 'OPEN', network: 'ethereum',
  address: WALLET, txHash: TX, counterparty: CP, blockNumber: 18_500_123, summary: 'Rapid drain: USDT transfer',
  evidenceSummary: '0xfa4ede30… sent 92% of its USDT balance (46,000 USDT) within 2 minutes (3 transfers)', evidenceLevel: 'RISK_SIGNAL',
  assignee: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), matchedEvent: {}, evidence: null, comments: [], history: [], ...over,
});

const risk: RiskScore = {
  address: WALLET, network: 'ethereum', score: 40, level: 'MEDIUM', method: 'noisy-or', methodSummary: 'Plain.', modelVersion: 't', computedAt: new Date().toISOString(),
  contributions: [{ factor: 'signal:RAPID_DRAIN', signalId: null, source: 'rapid-drain@1', weight: 0.3, confidence: 0.7, ageSeconds: 60, points: 40,
    explanation: 'RAPID_DRAIN ... q=0.490', evidenceLevel: 'RISK_SIGNAL', title: 'Rapid drain', summary: 'Sent 95% of its USDT balance (35,000 USDT) to 2 addresses within 2 minutes' }],
};

describe('AlertDetail (why it fired)', () => {
  beforeEach(() => TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: ApiService, useValue: { walletRisk: () => of(risk) } }],
  }));

  async function render(a: Alert): Promise<HTMLElement> {
    const f = TestBed.createComponent(AlertDetail);
    f.componentRef.setInput('alert', a);
    await f.whenStable();
    return f.nativeElement as HTMLElement;
  }

  it('opens the why section with the evidence summary, then wallet, counterparty, transaction and block', async () => {
    const el = await render(alert());
    const why = el.querySelector('oci-alert-why')!;
    const h = why.querySelector('#h-why')!;
    expect(h.nextElementSibling?.classList).toContain('why-first');
    expect(h.nextElementSibling?.textContent).toContain('sent 92% of its USDT balance (46,000 USDT)');
    const subject = why.querySelector('dl.subject')!;
    expect(subject.textContent).toContain('Wallet');
    expect(subject.querySelector(`a[title="${WALLET}"]`)?.getAttribute('href')).toBe(`/wallets/ethereum/${WALLET}`);
    expect(subject.querySelector(`a[title="${CP}"]`)).not.toBeNull();
    expect(subject.querySelector(`a[title="${TX}"]`)?.getAttribute('href')).toContain('/explorer');
    expect(subject.textContent).toContain('18,500,123');
  });

  it('shows the subject wallet contributions in plain language, technical detail collapsed', async () => {
    const el = await render(alert());
    const row = el.querySelector('table.contrib tbody tr')!;
    expect(row.textContent).toContain('Rapid drain');
    expect(row.textContent).toContain('Sent 95% of its USDT balance');
    expect(row.querySelector('details')?.open).toBe(false);
  });

  it('copes with an alert without the new fields', async () => {
    const el = await render(alert({ evidenceSummary: null, counterparty: null, blockNumber: null, address: null }));
    expect(el.querySelector('.why-first')).toBeNull();
    expect(el.querySelector('dl.subject')?.textContent).toContain('Not recorded');
  });
});
