import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor, errorInterceptor } from '../../core/interceptors';
import { mockBackendInterceptor } from '../../core/mock/mock-backend.interceptor';
import { SettingsService } from '../../core/settings.service';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { AlertsPage } from './alerts';

/** Keyboard triage in the alerts inbox: J / K move, Enter opens, E acknowledges, Esc closes the pane. */
describe('Alerts inbox keyboard', () => {
  let router: Router;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), MATERIAL_DEFAULTS, provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))],
    });
    TestBed.inject(SettingsService).update({ mock: true });
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
  });

  const settle = async (f: ComponentFixture<unknown>, ms = 450) => {
    await f.whenStable();
    await new Promise((r) => setTimeout(r, ms));
    await f.whenStable();
  };

  async function mount(inputs: Record<string, unknown> = {}) {
    const f = TestBed.createComponent(AlertsPage);
    for (const [k, v] of Object.entries(inputs)) f.componentRef.setInput(k, v);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    return f;
  }

  function press(key: string, target: EventTarget = document.body): KeyboardEvent {
    const e = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    target.dispatchEvent(e);
    return e;
  }

  const el = (f: ComponentFixture<AlertsPage>) => f.nativeElement as HTMLElement;
  const ids = (f: ComponentFixture<AlertsPage>) => [...el(f).querySelectorAll('[role="option"]')].map((o) => o.id.replace('opt-', ''));
  const cursorId = (f: ComponentFixture<AlertsPage>) => el(f).querySelector('[role="listbox"]')?.getAttribute('aria-activedescendant')?.replace('opt-', '');

  it('moves the highlighted row with J and K without opening it', async () => {
    const f = await mount();
    const rows = ids(f);
    expect(rows.length).toBeGreaterThan(2);
    press('j');
    await settle(f, 20);
    expect(cursorId(f)).toBe(rows[0]);
    press('j');
    await settle(f, 20);
    expect(cursorId(f)).toBe(rows[1]);
    expect(el(f).querySelector('.item.cursor')?.id).toBe('opt-' + rows[1]);
    press('k');
    await settle(f, 20);
    expect(cursorId(f)).toBe(rows[0]);
    // K on the first row stays there
    press('k');
    await settle(f, 20);
    expect(cursorId(f)).toBe(rows[0]);
    expect(el(f).querySelector('oci-alert-detail')).toBeNull();
    f.destroy();
  });

  it('opens the highlighted alert with Enter, keeps it in the URL and closes it with Esc', async () => {
    const f = await mount();
    const rows = ids(f);
    press('j');
    press('j');
    press('Enter');
    await settle(f);
    expect(el(f).querySelector('[role="option"][aria-selected="true"]')?.id).toBe('opt-' + rows[1]);
    expect(el(f).querySelector('oci-alert-detail[role="region"]')).not.toBeNull();
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { id: rows[1] }, queryParamsHandling: 'merge' }));
    // with the pane open, J opens the next alert
    press('j');
    await settle(f);
    expect(el(f).querySelector('[role="option"][aria-selected="true"]')?.id).toBe('opt-' + rows[2]);
    press('Escape');
    await settle(f, 20);
    expect(el(f).querySelector('oci-alert-detail')).toBeNull();
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { id: null } }));
    f.destroy();
  });

  it('acknowledges the open alert with E', async () => {
    const f = await mount({ status: 'OPEN' });
    const first = ids(f)[0];
    f.componentInstance.select(first);
    await settle(f);
    expect(el(f).querySelector('oci-alert-detail .status')?.textContent).toContain('Open');
    press('e');
    await settle(f, 600);
    expect(el(f).querySelector('oci-alert-detail .status')?.textContent).toContain('Acknowledged');
    f.destroy();
  });

  it('ignores single-key shortcuts while typing in a field', async () => {
    const f = await mount();
    const input = el(f).querySelector<HTMLInputElement>('input[type="search"]')!;
    input.focus();
    const e = press('j', input);
    await settle(f, 20);
    expect(e.defaultPrevented).toBe(false);
    expect(cursorId(f)).toBeUndefined();
    f.destroy();
  });

  it('shows the shortcut hints next to the list', async () => {
    const f = await mount();
    const hints = el(f).querySelector('.keys')?.textContent ?? '';
    for (const k of ['J', 'K', 'Enter', 'E', '?']) expect(hints).toContain(k);
    f.destroy();
  });
});
