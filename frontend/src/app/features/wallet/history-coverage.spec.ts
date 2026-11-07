import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { HistoryCoverage, HistoryStatus, WalletAnalysis } from '../../core/models';
import { HISTORY_POLL_MS, HistoryCoverageLine, coverageTone, historyLoading } from './history-coverage';
import { WalletPage } from './wallet';

const cov = (status: HistoryStatus, over: Partial<HistoryCoverage> = {}): HistoryCoverage => ({
  status, sources: status === 'NOT_CONFIGURED' ? [] : ['eth_getLogs'], coveredFromBlock: null, coveredToBlock: null, coveredFromTime: null,
  tokenTransfersCovered: true, approvalsCovered: true, nativeCovered: false, internalCovered: false, truncated: status === 'TRUNCATED', eventsFound: 0,
  message: `Message for ${status}.`, updatedAt: null, ...over,
});

describe('history coverage helpers', () => {
  it('polls only while the history is pending or running', () => {
    expect(historyLoading(cov('PENDING'))).toBe(true);
    expect(historyLoading(cov('RUNNING'))).toBe(true);
    for (const s of ['COMPLETE', 'TRUNCATED', 'FAILED', 'NOT_CONFIGURED'] as HistoryStatus[]) expect(historyLoading(cov(s))).toBe(false);
    expect(historyLoading(null)).toBe(false);
    expect(historyLoading(undefined)).toBe(false);
  });
  it('maps statuses to tones', () => {
    expect(coverageTone('TRUNCATED')).toBe('warn');
    expect(coverageTone('FAILED')).toBe('error');
    expect(coverageTone('COMPLETE')).toBe('ok');
    expect(coverageTone('NOT_CONFIGURED')).toBe('neutral');
  });
});

describe('HistoryCoverageLine', () => {
  async function render(h: HistoryCoverage): Promise<HTMLElement> {
    const f = TestBed.createComponent(HistoryCoverageLine);
    f.componentRef.setInput('history', h);
    await f.whenStable();
    return f.nativeElement as HTMLElement;
  }

  it('NOT_CONFIGURED: shows the server message, no loading line', async () => {
    const el = await render(cov('NOT_CONFIGURED', { message: 'Simulated demo chain: the indexed demo data is the whole history.' }));
    expect(el.textContent).toContain('Simulated demo chain: the indexed demo data is the whole history.');
    expect(el.textContent).toContain('Indexed data only');
    expect(el.textContent).not.toContain('Loading history');
    expect(el.querySelector('.cov')?.classList).toContain('tone-neutral');
  });

  it('RUNNING: announces "Loading history…" politely with a progress indicator', async () => {
    const el = await render(cov('RUNNING', { eventsFound: 1200 }));
    const live = el.querySelector('[aria-live="polite"]')!;
    expect(live.textContent).toContain('Loading history…');
    expect(live.textContent).toContain('1,200 events so far');
    expect(live.querySelector('mat-progress-spinner')).not.toBeNull();
  });

  it('TRUNCATED: warning tone and the caps in the technical detail', async () => {
    const el = await render(cov('TRUNCATED', { coveredFromBlock: 18_500_000, coveredToBlock: 21_000_000, eventsFound: 10_000 }));
    expect(el.querySelector('.cov')?.classList).toContain('tone-warn');
    expect(el.textContent).toContain('Partial');
    const details = el.querySelector('details')!;
    expect(details.open).toBe(false);
    expect(details.textContent).toContain('18,500,000');
    expect(details.textContent).toContain('caps reached');
  });

  it('FAILED: error tone, announced as an alert', async () => {
    const el = await render(cov('FAILED'));
    const line = el.querySelector('.cov')!;
    expect(line.classList).toContain('tone-error');
    expect(line.getAttribute('role')).toBe('alert');
  });
});

describe('WalletPage history polling', () => {
  const ADDR = '0xfa4ede30000000000000000000000000000000cc';
  const analysis = (h: HistoryCoverage | null): WalletAnalysis => ({
    address: ADDR, network: 'ethereum', firstSeen: null, lastSeen: null, txCount: 0, isContract: false, labels: [], balances: [], flows: [],
    counterparties: [], activity: [], signals: [], risk: null, history: h,
  });

  function setup(sequence: HistoryStatus[]) {
    const calls: string[] = [];
    const api = {
      walletAnalysis: (a: string) => {
        calls.push(a);
        return of(analysis(cov(sequence[Math.min(calls.length - 1, sequence.length - 1)])));
      },
      walletExposure: () => of({ address: ADDR, network: 'ethereum', direct: [], indirect: [] }),
      walletTransactions: () => of({ items: [], nextCursor: null }),
    };
    // Other calls (app shell, header) never answer: only the wallet calls matter here.
    const stub = new Proxy(api as Record<string, unknown>, { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: stub }, { provide: HISTORY_POLL_MS, useValue: 15 }] });
    return calls;
  }
  const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

  it('re-fetches while RUNNING and stops at a terminal status', async () => {
    const calls = setup(['PENDING', 'RUNNING', 'COMPLETE']);
    const f = TestBed.createComponent(WalletPage);
    f.componentRef.setInput('network', 'ethereum');
    f.componentRef.setInput('address', ADDR);
    await f.whenStable();
    for (let i = 0; i < 20 && calls.length < 3; i++) { await wait(20); await f.whenStable(); }
    expect(calls.length).toBe(3);
    expect((f.nativeElement as HTMLElement).textContent).toContain('Message for COMPLETE.');
    await wait(100);
    await f.whenStable();
    expect(calls.length).toBe(3);
  });

  it('shows the loading line and stops polling when the page is destroyed', async () => {
    const calls = setup(['RUNNING']);
    const f = TestBed.createComponent(WalletPage);
    f.componentRef.setInput('network', 'ethereum');
    f.componentRef.setInput('address', ADDR);
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).querySelector('oci-history-coverage [aria-live="polite"]')?.textContent).toContain('Loading history…');
    f.destroy();
    const n = calls.length;
    await wait(80);
    expect(calls.length).toBe(n);
  });
});
