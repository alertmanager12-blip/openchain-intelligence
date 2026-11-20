import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { WatchlistPage } from './watchlist';

const entry = { id: 'w1', network: 'ethereum', address: '0x00000000000000000000000000000000000000ab', name: 'Hot wallet', createdAt: '2026-11-10T10:00:00Z', createdBy: 'ana' };

describe('Watchlist screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ watchlist: () => of([entry]) } as Record<string, unknown>, { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), MATERIAL_DEFAULTS, { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes the watchlist in French and switches back', async () => {
    const f = TestBed.createComponent(WatchlistPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Liste de surveillance');
    expect(el.querySelector('[data-testid="scope"]')?.textContent).toContain('ne regardent que ce portefeuille');
    expect(el.textContent).toContain('Surveiller un portefeuille');
    expect(el.textContent).toContain('par ana');
    expect(el.querySelector('button[aria-label^="Ne plus surveiller"]')).not.toBeNull();
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Watchlist');
    expect(el.querySelector('[data-testid="scope"]')?.textContent).toContain('only look at this wallet');
    expect(el.textContent).toContain('by ana');
    f.destroy();
  });
});
