import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Dashboard, SanctionsList } from '../../core/models';
import { ReliabilityBand } from './reliability-band';

const NOW = Date.parse('2026-11-19T20:00:00Z');
const min = (m: number) => new Date(NOW - m * 60_000).toISOString();
const d = {
  generatedAt: new Date(NOW).toISOString(), omittedSections: [], coverage: { gaps: [] }, notifications: { unroutedRules: [], openAlertsUnrouted: 0 },
  networks: [{ network: 'tron', headBlock: 10, finalizedBlock: 9, healthy: true, providerId: 'p', latencyMillis: 1, checkedAt: min(0), detail: null,
    enabled: true, lastIndexedBlock: 9, indexerLag: 1, lastIndexedBlockTime: min(1) }],
} as unknown as Dashboard;
const list = { source: 'OFAC SDN', state: 'CURRENT', fetchEnabled: true, issued: '2026-11-19', fetchedAt: min(5), checkedAt: min(5), tron: 1, ethereum: 1, lastError: null } as SanctionsList;

/** Keyboard users keep their place when they open or close the passed checks (final review, 2026-11-19). */
describe('ReliabilityBand', () => {
  it('keeps the focus on the toggle and lets the passed checks be closed again', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const f = TestBed.createComponent(ReliabilityBand);
    f.componentRef.setInput('dashboard', d);
    f.componentRef.setInput('sanctions', list);
    document.body.appendChild(f.nativeElement);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    const btn = el.querySelector<HTMLButtonElement>('button.more')!;
    expect(btn.getAttribute('aria-expanded')).toBe('false');
    btn.focus();
    btn.click();
    await f.whenStable();
    expect(document.activeElement).toBe(btn);
    expect(btn.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelectorAll('li.s-pass').length).toBeGreaterThan(2);
    btn.click();
    await f.whenStable();
    expect(btn.getAttribute('aria-expanded')).toBe('false');
    f.nativeElement.remove();
  });
});
