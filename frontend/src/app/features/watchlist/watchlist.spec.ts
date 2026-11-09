import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor, errorInterceptor } from '../../core/interceptors';
import { mockBackendInterceptor } from '../../core/mock/mock-backend.interceptor';
import { SettingsService } from '../../core/settings.service';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { WatchlistPage } from './watchlist';

/** Own wallets: add, see the scope note change, remove (mock backend through the real ApiService). */
describe('WatchlistPage', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), MATERIAL_DEFAULTS, provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))],
    });
    TestBed.inject(SettingsService).update({ mock: true });
  });

  const settle = async (f: { whenStable(): Promise<unknown>; detectChanges(): void }, ms = 450) => {
    await f.whenStable();
    await new Promise((r) => setTimeout(r, ms));
    await f.whenStable();
    f.detectChanges();
  };

  it('adds a wallet, scopes the behavioural rules to it, and removes it', async () => {
    const f = TestBed.createComponent(WatchlistPage);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="scope"]')?.textContent).toContain('watch the whole chain');

    f.componentInstance.onAddress('0x00000000000000000000000000000000000000AB');
    f.componentInstance.name.set('Hot wallet');
    await f.componentInstance.add();
    await settle(f);
    expect(f.componentInstance.network()).toBe('ethereum'); // detected from the 0x format
    expect(el.textContent).toContain('Hot wallet');
    expect(el.querySelector('[data-testid="scope"]')?.textContent).toContain('only look at these 1 wallet(s)');

    expect(f.componentInstance.address()).toBe(''); // the form is cleared after a successful add
    f.componentInstance.onAddress('0x00000000000000000000000000000000000000ab');
    await f.componentInstance.add(); // same wallet again (any case): the server refuses a duplicate
    expect(f.componentInstance.error()?.status).toBe(409);

    await f.componentInstance.remove(f.componentInstance.entries()[0]);
    await settle(f);
    expect(f.componentInstance.entries()).toEqual([]);
  });
});
