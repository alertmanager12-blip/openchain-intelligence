import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor, errorInterceptor } from '../core/interceptors';
import { mockBackendInterceptor } from '../core/mock/mock-backend.interceptor';
import { SettingsService } from '../core/settings.service';
import { CommandPalette } from './command-palette';

@Component({ imports: [CommandPalette], template: `<button id="opener">Search</button><oci-command-palette [(open)]="open" />` })
class Host {
  readonly open = signal(false);
}

describe('CommandPalette', () => {
  let router: Router;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))],
    });
    TestBed.inject(SettingsService).update({ mock: true });
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  async function openPalette() {
    const f = TestBed.createComponent(Host);
    const el = f.nativeElement as HTMLElement;
    document.body.appendChild(el);
    el.querySelector<HTMLButtonElement>('#opener')!.focus();
    f.componentInstance.open.set(true);
    await f.whenStable();
    await new Promise((r) => setTimeout(r, 450));
    await f.whenStable();
    const input = el.querySelector<HTMLInputElement>('input[role="combobox"]')!;
    const type = async (v: string) => { input.value = v; input.dispatchEvent(new Event('input')); await f.whenStable(); };
    const key = async (k: string) => { input.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true })); await f.whenStable(); };
    return { f, el, input, type, key };
  }

  it('is a modal dialog with an ARIA combobox and listbox, focused on open', async () => {
    const { el, input } = await openPalette();
    expect(el.querySelector('[role="dialog"]')?.getAttribute('aria-modal')).toBe('true');
    expect(input.getAttribute('aria-controls')).toBe('palette-list');
    expect(el.querySelector('#palette-list')?.getAttribute('role')).toBe('listbox');
    expect(document.activeElement).toBe(input);
    expect(input.getAttribute('aria-activedescendant')).toBe(el.querySelector('[role="option"]')?.id);
  });

  it('lists pages and recent alerts, and filters them by the query', async () => {
    const { el, type } = await openPalette();
    const text = () => el.querySelector('#palette-list')?.textContent ?? '';
    expect(text()).toContain('Fund flow graph');
    expect(text()).toContain('Recent alerts');
    await type('simul');
    const options = [...el.querySelectorAll('[role="option"]')].map((o) => o.textContent?.trim());
    expect(options).toEqual(['Rule simulator']);
  });

  it('detects an EVM address, offers each network and opens the wallet on Enter', async () => {
    const { el, type, key } = await openPalette();
    const addr = '0xFA4EDE3000000000000000000000000000000003';
    await type(addr);
    const labels = [...el.querySelectorAll('[role="option"] .l')].map((o) => o.textContent?.trim());
    expect(labels.slice(0, 3)).toEqual(['Open address on Ethereum', 'Open address on BSC', 'Open address on Polygon']);
    await key('ArrowDown');
    expect(el.querySelector('[aria-selected="true"]')?.textContent).toContain('BSC');
    await key('Enter');
    expect(router.navigate).toHaveBeenCalledWith(['/wallets', 'bsc', addr.toLowerCase()]);
    expect(el.querySelector('[role="dialog"]')).toBeNull();
  });

  it('opens a transaction hash in the explorer', async () => {
    const { type, key } = await openPalette();
    const tx = '0x' + 'ab'.repeat(32);
    await type(tx);
    await key('Enter');
    expect(router.navigate).toHaveBeenCalledWith(['/explorer'], { queryParams: { q: tx, network: 'ethereum' } });
  });

  it('closes on Escape and returns focus to where it was', async () => {
    const { el, key } = await openPalette();
    await key('Escape');
    expect(el.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement?.id).toBe('opener');
    el.remove();
  });
});
