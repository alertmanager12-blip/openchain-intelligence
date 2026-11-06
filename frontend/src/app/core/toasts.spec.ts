import { TestBed } from '@angular/core/testing';
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
  it('renders toasts in a polite live region with a dismiss button', async () => {
    const f = TestBed.createComponent(ToastOutlet);
    const t = TestBed.inject(Toasts);
    t.success('Rule enabled', 'large-transfer');
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    const region = el.querySelector('[aria-live="polite"]');
    expect(region?.getAttribute('role')).toBe('status');
    expect(region?.textContent).toContain('Rule enabled');
    expect(region?.textContent).toContain('large-transfer');
    el.querySelector<HTMLButtonElement>('button[aria-label="Dismiss notification"]')!.click();
    await f.whenStable();
    expect(t.items()).toEqual([]);
  });
});
