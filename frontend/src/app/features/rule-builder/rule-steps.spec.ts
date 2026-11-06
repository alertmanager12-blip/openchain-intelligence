import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
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
    expect(el.querySelector('[aria-current="step"]')?.textContent).toContain('Scope');

    page.patch({ name: '' });
    await f.whenStable();
    expect(page.issues().scope).toBeGreaterThan(0);
    expect(el.querySelector('[aria-current="step"] .iss')).not.toBeNull();

    page.step.set('review');
    await f.whenStable();
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
