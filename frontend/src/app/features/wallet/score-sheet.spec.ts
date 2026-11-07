import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RiskContribution, RiskScore } from '../../core/models';
import { ScoreSheet } from './score-sheet';

const contribution = (over: Partial<RiskContribution> = {}): RiskContribution => ({
  factor: 'signal:RAPID_DRAIN', signalId: 'sig_1', source: 'rapid-drain@1', weight: 0.3, confidence: 0.7, ageSeconds: 360, points: 15,
  explanation: 'RAPID_DRAIN MEDIUM RISK_SIGNAL by rapid-drain@1; confidence 0.70, severity x0.70, age 6m, decay 1.000 -> q=0.490; factor weight 0.30, factor p=0.147',
  evidenceLevel: 'RISK_SIGNAL', title: 'Rapid drain', summary: 'Sent 95% of its USDT balance (35,000 USDT) to 2 addresses within 2 minutes', ...over,
});

const risk = (contributions: RiskContribution[]): RiskScore => ({
  address: '0xabc', network: 'ethereum', score: 15, level: 'LOW', contributions, method: 'noisy-or', methodSummary: 'Plain method.',
  modelVersion: 't', computedAt: new Date().toISOString(),
});

describe('ScoreSheet', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  async function render(r: RiskScore): Promise<HTMLElement> {
    const f = TestBed.createComponent(ScoreSheet);
    f.componentRef.setInput('risk', r);
    await f.whenStable();
    return f.nativeElement as HTMLElement;
  }

  it('shows the title and the plain summary first, the technical detail collapsed', async () => {
    const el = await render(risk([contribution()]));
    const cell = el.querySelector('tbody tr td')!;
    expect(cell.querySelector('.c-title')?.textContent).toBe('Rapid drain');
    expect(cell.querySelector('.c-summary')?.textContent).toContain('Sent 95% of its USDT balance');
    // title and summary come before the disclosure
    const order = [...cell.children].map((c) => c.className || c.tagName.toLowerCase());
    expect(order.findIndex((c) => c.includes('c-title'))).toBeLessThan(order.indexOf('oci-tech'));
    const details = cell.querySelector('details')!;
    expect(details.open).toBe(false);
    const summary = details.querySelector('summary')!;
    expect(summary.textContent?.trim()).toBe('Technical detail');
    expect(summary.getAttribute('aria-expanded')).toBe('false');
    expect(details.textContent).toContain('signal:RAPID_DRAIN');
    expect(details.textContent).toContain('q=0.490');
  });

  it('opens the technical detail and reflects it in aria-expanded', async () => {
    const f = TestBed.createComponent(ScoreSheet);
    f.componentRef.setInput('risk', risk([contribution()]));
    await f.whenStable();
    const details = (f.nativeElement as HTMLElement).querySelector('details')!;
    details.open = true;
    details.dispatchEvent(new Event('toggle'));
    await f.whenStable();
    expect(details.querySelector('summary')!.getAttribute('aria-expanded')).toBe('true');
  });

  it('falls back to the factor name when an older payload has no title or summary', async () => {
    const el = await render(risk([contribution({ factor: 'label:SANCTIONED', title: undefined as unknown as string, summary: undefined as unknown as string })]));
    expect(el.querySelector('.c-title')?.textContent).toBe('Listed as sanctioned');
    expect(el.querySelector('.c-summary')).toBeNull();
  });
});
