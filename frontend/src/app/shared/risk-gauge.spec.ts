import { TestBed } from '@angular/core/testing';
import { RiskGauge, clampScore, riskBand } from './risk-gauge';

describe('riskBand (engine thresholds 35 / 65 / 85)', () => {
  it('bands the boundaries exactly like RiskLevel.fromScore', () => {
    expect(riskBand(0)).toBe('LOW');
    expect(riskBand(34)).toBe('LOW');
    expect(riskBand(35)).toBe('MEDIUM');
    expect(riskBand(64)).toBe('MEDIUM');
    expect(riskBand(65)).toBe('HIGH');
    expect(riskBand(84)).toBe('HIGH');
    expect(riskBand(85)).toBe('CRITICAL');
    expect(riskBand(100)).toBe('CRITICAL');
  });

  it('clamps out-of-range scores and treats a missing score as UNKNOWN', () => {
    expect(clampScore(-12)).toBe(0);
    expect(clampScore(140)).toBe(100);
    expect(clampScore(64.6)).toBe(65);
    expect(riskBand(-5)).toBe('LOW');
    expect(riskBand(250)).toBe('CRITICAL');
    expect(riskBand(null)).toBe('UNKNOWN');
    expect(riskBand(undefined)).toBe('UNKNOWN');
    expect(riskBand(Number.NaN)).toBe('UNKNOWN');
  });
});

describe('RiskGauge', () => {
  async function render(inputs: Record<string, unknown>): Promise<HTMLElement> {
    const f = TestBed.createComponent(RiskGauge);
    for (const [k, v] of Object.entries(inputs)) f.componentRef.setInput(k, v);
    await f.whenStable();
    return f.nativeElement as HTMLElement;
  }

  it('exposes a meter with value, range and a spoken value', async () => {
    const el = await render({ score: 72, size: 'lg', label: 'Wallet risk score' });
    expect(el.getAttribute('role')).toBe('meter');
    expect(el.getAttribute('aria-valuemin')).toBe('0');
    expect(el.getAttribute('aria-valuemax')).toBe('100');
    expect(el.getAttribute('aria-valuenow')).toBe('72');
    expect(el.getAttribute('aria-valuetext')).toBe('72 of 100, high risk');
    expect(el.getAttribute('aria-label')).toBe('Wallet risk score');
    expect(el.classList).toContain('lv-high');
    expect(el.querySelector('.score')?.textContent?.trim()).toBe('72');
  });

  it('places the boundaries in the right band and fills segments up to the score', async () => {
    for (const [score, cls] of [[34, 'lv-low'], [35, 'lv-medium'], [64, 'lv-medium'], [65, 'lv-high'], [84, 'lv-high'], [85, 'lv-critical']] as const) {
      const el = await render({ score });
      expect(el.classList).toContain(cls);
    }
    const el = await render({ score: 50 });
    const fills = [...el.querySelectorAll<HTMLElement>('.fill')].map((f) => f.style.width);
    expect(fills).toEqual(['100%', '50%', '0%', '0%']);
    expect(el.querySelector<HTMLElement>('.marker')?.style.left).toBe('50%');
  });

  it('clamps the displayed value and the marker to 0..100', async () => {
    const hi = await render({ score: 130 });
    expect(hi.getAttribute('aria-valuenow')).toBe('100');
    expect(hi.querySelector<HTMLElement>('.marker')?.style.left).toBe('100%');
    const lo = await render({ score: -4 });
    expect(lo.getAttribute('aria-valuenow')).toBe('0');
    expect(lo.classList).toContain('lv-low');
  });

  it('shows "Not scored" without a marker when there is no score', async () => {
    const el = await render({ score: null, size: 'lg' });
    expect(el.getAttribute('aria-valuenow')).toBeNull();
    expect(el.getAttribute('aria-valuetext')).toBe('Not scored');
    expect(el.classList).toContain('lv-unknown');
    expect(el.querySelector('.marker')).toBeNull();
  });

  it('draws tick labels at the engine thresholds on the large gauge', async () => {
    const el = await render({ score: 10, size: 'lg' });
    expect([...el.querySelectorAll('.tick')].map((t) => t.textContent?.trim())).toEqual(['0', '35', '65', '85', '100']);
  });

  it('prefers the level sent by the backend', async () => {
    const el = await render({ score: 40, level: 'HIGH' });
    expect(el.classList).toContain('lv-high');
  });
});
