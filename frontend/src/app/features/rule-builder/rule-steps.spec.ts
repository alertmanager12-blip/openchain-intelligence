import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { TestBed } from '@angular/core/testing';
import { MatStepperHarness } from '@angular/material/stepper/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor, errorInterceptor } from '../../core/interceptors';
import { mockBackendInterceptor } from '../../core/mock/mock-backend.interceptor';
import { SettingsService } from '../../core/settings.service';
import { RuleBuilderPage } from './rule-builder';
import { stepOfPath } from './rule-steps';

describe('rule builder stepper', () => {
  it('maps validation paths to the step that owns them', () => {
    expect(stepOfPath('name')).toBe('scope');
    expect(stepOfPath('event_types[0]')).toBe('scope');
    expect(stepOfPath('when')).toBe('conditions');
    expect(stepOfPath('when.all[1].value')).toBe('conditions');
    expect(stepOfPath('window.duration')).toBe('window');
    expect(stepOfPath('actions[0].target')).toBe('actions');
    expect(stepOfPath('cooldown')).toBe('actions');
  });

  it('walks the steps, counts issues per step and opens a stored rule from ?rule=', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))],
    });
    TestBed.inject(SettingsService).update({ mock: true });
    const f = TestBed.createComponent(RuleBuilderPage);
    await f.whenStable();
    await new Promise((r) => setTimeout(r, 300));
    await f.whenStable();
    const page = f.componentInstance;
    const el = f.nativeElement as HTMLElement;
    const stepper = await TestbedHarnessEnvironment.loader(f).getHarness(MatStepperHarness);
    expect(await Promise.all((await stepper.getSteps()).map((s) => s.getLabel()))).toEqual([
      expect.stringContaining('Scope'), expect.stringContaining('Conditions'), expect.stringContaining('Window'), expect.stringContaining('Actions'), expect.stringContaining('Review'),
    ]);
    const [selected] = await stepper.getSteps({ selected: true });
    expect(await selected.getLabel()).toContain('Scope');

    page.patch({ name: '' });
    await f.whenStable();
    expect(page.issues().scope).toBeGreaterThan(0);
    const header = el.querySelector('[role="tab"][aria-selected="true"]');
    expect(header?.querySelector('.iss')).not.toBeNull();
    expect(header?.getAttribute('aria-label')).toMatch(/^1\. Scope, \d+ issues?$/);

    // the header selects its step (non-linear stepper); the step left behind shows its error state
    const [conditions] = await stepper.getSteps({ label: /Conditions/ });
    await conditions.select();
    expect(page.step()).toBe('conditions');
    const [scope] = await stepper.getSteps({ label: /Scope/ });
    expect(await scope.isSelected()).toBe(false);
    expect(await scope.hasErrors()).toBe(true);

    page.step.set('review');
    await f.whenStable();
    expect(await (await stepper.getSteps({ selected: true }))[0].getLabel()).toContain('Review');
    expect(el.textContent).toContain('issue');
    expect(el.querySelector('pre[aria-label="Rule YAML"]')?.textContent).toContain('apiVersion: openchain.io/rule/v1');

    const stored = page.rules()[0];
    expect(stored).toBeDefined();
    f.componentRef.setInput('rule', stored.id);
    await f.whenStable();
    expect(page.loadedId()).toBe(stored.id);
    expect(page.d().id).toBe(stored.id);
  });
});
