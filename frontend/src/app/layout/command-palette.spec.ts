import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Component, signal } from '@angular/core';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { TestBed } from '@angular/core/testing';
import { MatDialogHarness } from '@angular/material/dialog/testing';
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
    // The palette renders in a MatDialog, i.e. in the CDK overlay container at the end of <body>.
    const doc = document;
    const input = doc.querySelector<HTMLInputElement>('input[role="combobox"]')!;
    const type = async (v: string) => { input.value = v; input.dispatchEvent(new Event('input')); await f.whenStable(); };
    const key = async (k: string) => { input.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true })); await f.whenStable(); };
    return { f, el, doc, input, type, key };
  }

  it('is a modal dialog with an ARIA combobox and listbox, focused on open', async () => {
    const { f, doc, input } = await openPalette();
    const dialog = await TestbedHarnessEnvironment.documentRootLoader(f).getHarness(MatDialogHarness);
    expect(await dialog.getAriaLabel()).toBe('Command palette');
    expect(doc.querySelector('[role="dialog"]')?.getAttribute('aria-modal')).toBe('true');
    expect(doc.querySelector('[role="dialog"]')?.contains(input)).toBe(true);
    expect(input.getAttribute('aria-controls')).toBe('palette-list');
    expect(doc.querySelector('#palette-list')?.getAttribute('role')).toBe('listbox');
    expect(document.activeElement).toBe(input);
    expect(input.getAttribute('aria-activedescendant')).toBe(doc.querySelector('[role="option"]')?.id);
  });

  it('lists pages and recent alerts, and filters them by the query', async () => {
    const { doc, type } = await openPalette();
    const text = () => doc.querySelector('#palette-list')?.textContent ?? '';
    expect(text()).toContain('Fund flow graph');
    expect(text()).toContain('Recent alerts');
    await type('simul');
    const options = [...doc.querySelectorAll('[role="option"]')].map((o) => o.textContent?.trim());
    expect(options).toEqual(['Rule simulator']);
  });

  it('detects an EVM address, offers each network and opens the wallet on Enter', async () => {
    const { doc, type, key } = await openPalette();
    const addr = '0xFA4EDE3000000000000000000000000000000003';
    await type(addr);
    const labels = [...doc.querySelectorAll('[role="option"] .l')].map((o) => o.textContent?.trim());
    expect(labels.slice(0, 3)).toEqual(['Open address on Ethereum', 'Open address on BSC', 'Open address on Polygon']);
    await key('ArrowDown');
    expect(doc.querySelector('[aria-selected="true"]')?.textContent).toContain('BSC');
    await key('Enter');
    expect(router.navigate).toHaveBeenCalledWith(['/wallets', 'bsc', addr.toLowerCase()]);
    expect(doc.querySelector('[role="dialog"]')).toBeNull();
  });

  it('opens a transaction hash in the explorer', async () => {
    const { type, key } = await openPalette();
    const tx = '0x' + 'ab'.repeat(32);
    await type(tx);
    await key('Enter');
    expect(router.navigate).toHaveBeenCalledWith(['/explorer'], { queryParams: { q: tx, network: 'ethereum' } });
  });

  it('closes on Escape and returns focus to where it was', async () => {
    const { f, el, doc, key } = await openPalette();
    await key('Escape');
    await f.whenStable();
    expect(doc.querySelector('[role="dialog"]')).toBeNull();
    expect(f.componentInstance.open()).toBe(false);
    expect(document.activeElement?.id).toBe('opener');
    el.remove();
  });
});
