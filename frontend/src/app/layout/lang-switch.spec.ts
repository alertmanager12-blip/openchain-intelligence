import { TestBed } from '@angular/core/testing';
import { I18nService } from '../core/i18n/i18n.service';
import { LangSwitch } from './lang-switch';

/** Spec 2026-11-19 §1: a FR | EN switch, the active language pressed, switching without reload. */
describe('LangSwitch', () => {
  afterEach(() => TestBed.inject(I18nService).set('en'));

  it('marks the active language and switches on click', async () => {
    TestBed.inject(I18nService).set('fr');
    const f = TestBed.createComponent(LangSwitch);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    const [frB, enB] = [...el.querySelectorAll('button')];
    expect(frB.textContent?.trim()).toBe('FR');
    expect(frB.getAttribute('aria-pressed')).toBe('true');
    expect(enB.getAttribute('aria-pressed')).toBe('false');
    expect(el.querySelector('[role="group"]')?.getAttribute('aria-label')).toBe('Langue');
    enB.click();
    await f.whenStable();
    expect(TestBed.inject(I18nService).lang()).toBe('en');
    expect(enB.getAttribute('aria-pressed')).toBe('true');
    expect(el.querySelector('[role="group"]')?.getAttribute('aria-label')).toBe('Language');
  });
});
