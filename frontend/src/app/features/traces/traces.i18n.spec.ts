import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of, throwError } from 'rxjs';
import { ApiError } from '../../core/api-error';
import { Toasts } from '../../core/toasts';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { TracesPage } from './traces';

const A = '0xfa4ede30000000000000000000000000000000cc';
const B = '0xbb4ede30000000000000000000000000000000dd';
const token = { symbol: 'USDT', decimals: 6, network: 'ethereum', contract: '0xc', native: false };
const summary = { id: 't1', lineageId: 'l', version: 2, previousId: 't0', network: 'ethereum', seed: A, direction: 'FORWARD', status: 'DONE', resultStatus: 'PARTIAL',
  stale: true, createdBy: null, createdAt: '2026-11-19T10:00:00Z', completedAt: null };
const detail = { ...summary, staleEventIds: ['e1'], engineVersion: 'trace-1', error: null, frozenFlows: 3, request: { method: 'PROPORTIONAL' },
  diff: { evidence: [{ kind: 'ADDED', beforeEventId: null, afterEventId: 'e2', txHash: 'h' }], attributions: [] },
  result: { status: 'PARTIAL', nodes: [], edges: [{ eventId: 'e1', aliases: [], txHash: 'h', timestamp: '2026-11-19T10:00:00Z', from: { value: A }, to: { value: B }, tokenKey: 'k', rawAmount: 5_000_000, token }],
    attributions: [{ address: { value: B }, tokenKey: 'k', depth: 1, low: { num: 5_000_000, den: 1 }, high: { num: 5_000_000, den: 1 }, partial: true }],
    stops: [{ address: { value: B }, tokenKey: 'k', reason: 'COVERAGE_GAP', detail: 'History is missing before block 5' }],
    findings: [{ type: 'DISPERSION', level: 'INFERRED', addresses: [], eventIds: ['e1'], detail: 'Value fanned out to many wallets' }] } };

describe('Stored traces screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ traces: () => of([summary]), trace: () => of(detail) } as Record<string, unknown>, { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes the list and a trace in French, the engine sentences with lang="en", and switches back', async () => {
    const f = TestBed.createComponent(TracesPage);
    f.componentRef.setInput('id', 't1');
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Traçages enregistrés');
    expect(el.textContent).toContain('Vos traçages');
    expect(el.querySelector('table')?.textContent).toContain('terminé, partiel');
    expect(el.textContent).toContain('3 transferts figés');
    expect(el.textContent).toContain('Relancer (nouvelle version)');
    expect(el.textContent).toContain('Périmé');
    expect(el.textContent).toContain('Où il s’est arrêté');
    expect(el.textContent).toContain('Trou dans l’historique indexé');
    expect(el.textContent).toContain('Formulation du moteur');
    const engine = Array.from(el.querySelectorAll('[lang="en"]')).map((n) => n.textContent);
    expect(engine.some((t) => t?.includes('History is missing before block 5'))).toBe(true);
    expect(engine.some((t) => t?.includes('Value fanned out to many wallets'))).toBe(true);
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Stored traces');
    expect(el.textContent).toContain('3 frozen transfers');
    expect(el.textContent).toContain('History is missing before block 5');
    expect(el.querySelector('.grid [lang="en"]')).toBeNull();
    f.destroy();
  });

  it('titles an error toast in French by its status', async () => {
    TestBed.overrideProvider(ApiService, { useValue: new Proxy({ traces: () => of([summary]), trace: () => throwError(() => new ApiError(404, 'Resource not found', 'server english')) } as Record<string, unknown>,
      { get: (t, k) => (k in t ? t[k as string] : () => NEVER) }) });
    const f = TestBed.createComponent(TracesPage);
    f.componentRef.setInput('id', 't1');
    await settle(f);
    const toast = TestBed.inject(Toasts).items().at(-1);
    expect(toast?.message).toBe('Introuvable');
    expect(toast?.detail).toBe('server english');
    f.destroy();
  });
});
