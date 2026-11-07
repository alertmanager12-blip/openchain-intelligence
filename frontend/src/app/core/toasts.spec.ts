import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { TestBed } from '@angular/core/testing';
import { MatSnackBarHarness } from '@angular/material/snack-bar/testing';
import { ToastOutlet } from '../layout/toast-outlet';
import { Toasts } from './toasts';

describe('Toasts', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('shows a confirmation, then dismisses it after its duration', () => {
    const t = TestBed.inject(Toasts);
    t.success('Alert acknowledged');
    expect(t.items().map((x) => x.message)).toEqual(['Alert acknowledged']);
    vi.advanceTimersByTime(t.duration + 10);
    expect(t.items()).toEqual([]);
  });

  it('keeps errors twice as long and pauses while hovered or focused', () => {
    const t = TestBed.inject(Toasts);
    const id = t.error('Test not delivered', 'HTTP 500');
    vi.advanceTimersByTime(t.duration + 10);
    expect(t.items().length).toBe(1);
    t.pause(id);
    vi.advanceTimersByTime(t.duration * 3);
    expect(t.items().length).toBe(1);
    t.resume(id);
    vi.advanceTimersByTime(t.duration * 2 + 10);
    expect(t.items()).toEqual([]);
  });

  it('keeps at most four toasts', () => {
    const t = TestBed.inject(Toasts);
    for (let i = 0; i < 6; i++) t.success(`Toast ${i}`);
    expect(t.items().map((x) => x.message)).toEqual(['Toast 2', 'Toast 3', 'Toast 4', 'Toast 5']);
  });
});

describe('ToastOutlet', () => {
  it('shows the toasts in a Material snack bar, a polite live region, with a dismiss button', async () => {
    const f = TestBed.createComponent(ToastOutlet);
    const t = TestBed.inject(Toasts);
    t.success('Rule enabled', 'large-transfer');
    await f.whenStable();
    // the snack bar module is imported on the first toast; the snack bar moves its content into the live region after 150 ms
    await new Promise((r) => setTimeout(r, 300));
    const snack = await TestbedHarnessEnvironment.documentRootLoader(f).getHarness(MatSnackBarHarness);
    // Material's live region: aria-live="polite" (it adds role="status" only on Firefox, where aria-live alone is not enough)
    expect(await snack.getAriaLive()).toBe('polite');
    const region = document.querySelector('.mat-mdc-snack-bar-container [aria-live="polite"]');
    expect(region?.closest('[aria-hidden="true"]')).toBeNull();
    expect(region?.textContent).toContain('Rule enabled');
    expect(region?.textContent).toContain('large-transfer');
    document.querySelector<HTMLButtonElement>('.mat-mdc-snack-bar-container button[aria-label="Dismiss notification"]')!.click();
    await f.whenStable();
    expect(t.items()).toEqual([]);
    expect(await snack.isDismissed()).toBe(true);
  });
});
