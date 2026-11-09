import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Rule } from '../../core/models';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { StoredRules } from './stored-rules';

/** A rule that can never fire on this instance says why, and cannot be switched on from the list. */
describe('StoredRules', () => {
  const rule = (id: string, extra: Partial<Rule> = {}): Rule => ({
    id, name: id, enabled: false, severity: 'HIGH', yaml: '', version: 1, updatedAt: '2026-11-09T10:00:00Z', ...extra,
  });

  async function mount(items: Rule[]) {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), MATERIAL_DEFAULTS,
        { provide: ApiService, useValue: { rules: () => of({ items, nextCursor: null }), enableRule: vi.fn(),
          ruleNoise: () => of([{ ruleId: 'large-usdt-transfer', total: 40, open: 30, falsePositive: 3, resolved: 7, recent: 12, windowHours: 24, falsePositiveRate: 0.3 }]) } }],
    });
    const f = TestBed.createComponent(StoredRules);
    await f.whenStable();
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  it('shows why an inert rule can never fire and disables its toggle', async () => {
    const el = await mount([rule('contract-exploit', { unreachableReasons: ["sequence step 'large-outflow' can never match"] }),
      rule('large-usdt-transfer')]);
    const warn = el.querySelectorAll('[data-testid="unreachable"]');
    expect(warn.length).toBe(1);
    expect(warn[0].textContent).toContain('large-outflow');
    const toggles = Array.from(el.querySelectorAll('mat-slide-toggle button')) as HTMLButtonElement[];
    expect(toggles[0].disabled).toBe(true);
    expect(toggles[1].disabled).toBe(false);
  });

  it('shows the alert volume of the last 24 hours and the false-positive rate', async () => {
    const el = await mount([rule('large-usdt-transfer', { enabled: true })]);
    const cell = el.querySelector('[data-testid="noise"]')!;
    expect(cell.textContent).toContain('12');
    expect(cell.textContent).toContain('30% false positives');
  });
});
