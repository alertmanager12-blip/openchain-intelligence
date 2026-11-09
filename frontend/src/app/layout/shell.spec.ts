import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { App } from '../app';
import { authInterceptor, errorInterceptor } from '../core/interceptors';
import { mockBackendInterceptor } from '../core/mock/mock-backend.interceptor';
import { ApiService } from '../core/api.service';
import { SettingsService } from '../core/settings.service';
import { NAV_ITEMS } from './navigation';
import { Shell } from './shell';
import { SHORTCUTS } from './shortcut-help';

const SECRET = 'oci_demo_7f3a_9c1d2e3f4a5b6c7d8e9f';

describe('App shell', () => {
  let router: Router;
  let settings: SettingsService;
  let initialUrl: string;
  beforeEach(() => {
    // the shell (sidebar, shortcuts, palette) belongs to the console pages; '/' is the public landing page
    initialUrl = location.pathname + location.search;
    history.replaceState(null, '', '/overview');
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))],
    });
    settings = TestBed.inject(SettingsService);
    settings.update({ mock: true, authMode: 'apiKey', token: SECRET });
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((c) => c.replaceChildren());
    history.replaceState(null, '', initialUrl);
  });

  const settle = async (f: ComponentFixture<unknown>, ms = 300) => {
    await f.whenStable();
    await new Promise((r) => setTimeout(r, ms));
    await f.whenStable();
  };

  async function mount() {
    const f = TestBed.createComponent(App);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    return f;
  }

  const key = (k: string) => document.body.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));

  it('opens the shortcut help with "?" and lists every shortcut', async () => {
    const f = await mount();
    key('?');
    await settle(f, 500);
    const dialog = document.querySelector('mat-dialog-container[role="dialog"]');
    expect(dialog).not.toBeNull();
    expect(document.getElementById(dialog!.getAttribute('aria-labelledby')!)?.textContent).toBe('Keyboard shortcuts');
    const text = dialog!.textContent ?? '';
    for (const g of SHORTCUTS) for (const s of g.items) expect(text).toContain(s.label);
    // every page with a go-to key is listed, and the inbox keys are there
    expect(NAV_ITEMS.filter((i) => i.key).every((i) => text.includes(i.label))).toBe(true);
    for (const label of ['Next alert', 'Previous alert', 'Open the highlighted alert', 'Acknowledge the open alert', 'Close the alert pane']) expect(text).toContain(label);
    f.destroy();
  });

  it('navigates with "G then key" sequences', async () => {
    const f = await mount();
    key('g');
    key('a');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/alerts');
    f.destroy();
  });

  it('shows the active credential by mode and prefix only, never the secret', async () => {
    const f = await mount();
    const el = f.nativeElement as HTMLElement;
    const btn = el.querySelector<HTMLButtonElement>('button.user')!;
    expect(btn.textContent).toContain('API key');
    btn.click();
    await settle(f, 50);
    const pop = el.querySelector('#user-pop')!;
    expect(btn.getAttribute('aria-expanded')).toBe('true');
    expect(pop.textContent).toContain(SECRET.slice(0, 8));
    expect(el.textContent).not.toContain(SECRET);
    expect(el.textContent).not.toContain(SECRET.slice(0, 12));
    const links = [...pop.querySelectorAll('a')].map((a) => a.textContent?.trim());
    expect(links).toEqual(['Settings', 'API keys']);
    expect(pop.textContent).toContain('Sign out');
    f.destroy();
  });

  it('signs out by clearing the stored credential and opening Settings, Connection', async () => {
    const f = await mount();
    const el = f.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('button.user')!.click();
    await settle(f, 50);
    const signOut = [...el.querySelectorAll<HTMLButtonElement>('#user-pop button')].find((b) => b.textContent?.trim() === 'Sign out')!;
    signOut.click();
    await settle(f, 50);
    expect(settings.connection().token).toBe('');
    expect(JSON.parse(localStorage.getItem('oci.settings.v1') ?? '{}').token).toBe('');
    expect(router.navigate).toHaveBeenCalledWith(['/settings'], { queryParams: { section: 'connection' } });
    expect(el.querySelector('#user-pop')).toBeNull();
    expect(el.querySelector('button.user')?.textContent).toContain('Set one in Settings');
    f.destroy();
  });

  it('describes the real environment instead of a workspace', async () => {
    const f = await mount();
    const el = f.nativeElement as HTMLElement;
    expect(TestBed.inject(Shell).env().kind).toBe('mock');
    el.querySelector<HTMLButtonElement>('button.env')!.click();
    await settle(f, 50);
    const pop = el.querySelector('#env-pop')!;
    expect(pop.textContent).toContain('Mock data');
    expect(pop.textContent?.toLowerCase()).not.toContain('workspace');
    f.destroy();
  });

  describe('on the public landing page', () => {
    beforeEach(() => history.replaceState(null, '', '/'));

    it('renders without the console chrome and ignores console shortcuts', async () => {
      const f = await mount();
      const el = f.nativeElement as HTMLElement;
      expect(el.classList).toContain('bare');
      expect(el.querySelector('.side')).toBeNull();
      expect(el.querySelector('main#main')).not.toBeNull();
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'g' }));
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }));
      document.dispatchEvent(new KeyboardEvent('keydown', { key: '?' }));
      await settle(f);
      expect(router.navigateByUrl).not.toHaveBeenCalled();
      expect(TestBed.inject(Shell).help()).toBe(false);
      f.destroy();
    });

    it('does not call the API before the visitor opens the console', async () => {
      const api = TestBed.inject(ApiService);
      const spy = vi.spyOn(api, 'dashboard');
      TestBed.inject(Shell).refresh();
      expect(spy).not.toHaveBeenCalled();
    });
  });
});
