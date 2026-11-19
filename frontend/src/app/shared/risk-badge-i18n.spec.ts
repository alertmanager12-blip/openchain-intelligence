import { TestBed } from '@angular/core/testing';
import { I18nService } from '../core/i18n/i18n.service';
import { RiskBadge } from './ui';

/** Spec 2026-11-19: the risk badge of a verdict follows the console language. */
describe('RiskBadge language', () => {
  afterEach(() => TestBed.inject(I18nService).set('en'));
  function text(v: string): string {
    const f = TestBed.createComponent(RiskBadge);
    f.componentRef.setInput('value', v);
    f.detectChanges();
    return (f.nativeElement as HTMLElement).textContent?.trim() ?? '';
  }
  it('says the level in French and in English', () => {
    expect(text('CRITICAL')).toBe('Critical');
    expect(text('UNKNOWN')).toBe('Not scored');
    TestBed.inject(I18nService).set('fr');
    expect(text('CRITICAL')).toBe('Critique');
    expect(text('HIGH')).toBe('Élevé');
    expect(text('UNKNOWN')).toBe('Non évalué');
  });
});
