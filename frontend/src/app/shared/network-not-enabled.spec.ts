import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of, throwError } from 'rxjs';
import { ApiError } from '../core/api-error';
import { ApiService } from '../core/api.service';
import { NetworkId, NetworkStatus } from '../core/models';
import { fakeAddress } from '../core/mock/mock-db';
import { WalletPage } from '../features/wallet/wallet';
import { isNetworkNotEnabled, listOf } from './network-not-enabled';

const notEnabled = (n: string) => new ApiError(422, 'Request validation failed', `network '${n}' is not enabled on this instance`, 'about:blank', null,
  [{ field: '/network', message: 'network not enabled' }]);

describe('isNetworkNotEnabled', () => {
  it('recognises the 422 with a /network field error', () => {
    expect(isNetworkNotEnabled(notEnabled('bitcoin'))).toBe(true);
    expect(isNetworkNotEnabled(new ApiError(422, 'Validation failed', null, 'about:blank', null, [{ field: 'network', message: 'x' }]))).toBe(true);
  });
  it('ignores other errors', () => {
    expect(isNetworkNotEnabled(null)).toBe(false);
    expect(isNetworkNotEnabled(new ApiError(422, 'Validation failed', 'bad address', 'about:blank', null, [{ field: '/address', message: 'x' }]))).toBe(false);
    expect(isNetworkNotEnabled(new ApiError(400, 'Invalid request', null, 'about:blank', null, [{ field: '/network', message: 'unknown' }]))).toBe(false);
    expect(isNetworkNotEnabled(new ApiError(503, 'Provider unavailable', null))).toBe(false);
  });
  it('lists names in plain English', () => {
    expect(listOf(['Ethereum'])).toBe('Ethereum');
    expect(listOf(['Ethereum', 'TRON', 'BSC'])).toBe('Ethereum, TRON and BSC');
  });
});

describe('WalletPage on a network this instance does not index', () => {
  function setup(enabled: NetworkId[]) {
    const api: Record<string, unknown> = {
      networks: () => of(enabled.map((network) => ({ network, enabled: true }) as NetworkStatus)),
      walletAnalysis: (_: string, n: string) => throwError(() => notEnabled(n)),
      walletExposure: (_: string, n: string) => throwError(() => notEnabled(n)),
      walletTransactions: (_: string, n: string) => throwError(() => notEnabled(n)),
      watchlist: () => of([]),
    };
    const stub = new Proxy(api, { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: stub }] });
  }

  async function render(network: NetworkId, address: string): Promise<HTMLElement> {
    const f = TestBed.createComponent(WalletPage);
    f.componentRef.setInput('network', network);
    f.componentRef.setInput('address', address);
    await f.whenStable();
    return f.nativeElement as HTMLElement;
  }

  it('explains it and offers the same address on a compatible enabled network', async () => {
    setup(['ethereum', 'tron']);
    const addr = fakeAddress('ethereum', 5);
    const el = await render('polygon', addr);
    const box = el.querySelector('oci-network-not-enabled')!;
    expect(box.textContent).toContain('Polygon is not indexed by this instance');
    expect(box.textContent).toContain('This instance indexes only Ethereum and TRON.');
    const open = [...box.querySelectorAll('a')].find((a) => a.textContent?.includes('Open on'))!;
    expect(open.textContent?.trim()).toBe('Open on Ethereum');
    expect(open.getAttribute('href')).toBe(`/wallets/ethereum/${addr}`);
    expect([...box.querySelectorAll('a')].find((a) => a.textContent?.includes('Search another address'))?.getAttribute('href')).toBe('/explorer');
    expect(el.textContent).not.toContain('Request validation failed');
    expect(el.querySelector('.state--error')).toBeNull();
  });

  it('offers only a new search when no enabled network fits the address format', async () => {
    setup(['ethereum', 'tron', 'bsc']);
    const el = await render('bitcoin', fakeAddress('bitcoin', 1));
    const box = el.querySelector('oci-network-not-enabled')!;
    expect(box.textContent).toContain('Bitcoin is not indexed by this instance');
    expect(box.textContent).toContain('This instance indexes only Ethereum, TRON and BSC.');
    expect(box.textContent).not.toContain('Open on');
    expect(box.textContent).toContain('Search another address');
    expect(el.querySelector('.state--error')).toBeNull();
  });
});
