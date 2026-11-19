import { TestBed } from '@angular/core/testing';
import { I18nService } from '../core/i18n/i18n.service';
import { VerdictPanel } from './verdict-panel';

describe('VerdictPanel', () => {
  function render(inputs: Record<string, unknown>): HTMLElement {
    const f = TestBed.createComponent(VerdictPanel);
    for (const [k, v] of Object.entries(inputs)) f.componentRef.setInput(k, v);
    f.detectChanges();
    return f.nativeElement;
  }

  it('shows the sentence, the risk, every line and the coverage', () => {
    const el = render({ sentence: 'A watched wallet received funds from a sanctioned address.', risk: 'HIGH',
      lines: [{ level: 'CONFIRMED', text: 'Listed as sanctioned by OFAC SDN' }, { level: 'INFERRED', text: '1,000 USDT received', detail: 'risk signal' }],
      coverage: 'Based on the transfers this instance indexes on TRON.', limit: 'This is not proof of intent.' });
    expect(el.textContent).toContain('A watched wallet received funds from a sanctioned address.');
    expect(el.querySelector('oci-risk')).not.toBeNull();
    expect(el.querySelectorAll('oci-evidence-line').length).toBe(2);
    expect(el.textContent).toContain('Based on the transfers this instance indexes on TRON.');
    expect(el.textContent).toContain('This is not proof of intent.');
  });

  it('never hides missing coverage', () => {
    expect(render({ sentence: 'x', risk: null, lines: [], coverage: '' }).textContent).toContain('Coverage unknown');
  });
});

describe('VerdictPanel in French (spec 2026-11-19)', () => {
  afterEach(() => TestBed.inject(I18nService).set('en'));
  it('says a missing coverage in French', () => {
    TestBed.inject(I18nService).set('fr');
    const f = TestBed.createComponent(VerdictPanel);
    f.componentRef.setInput('sentence', 'x');
    f.componentRef.setInput('coverage', '');
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).textContent).toContain('Couverture inconnue');
  });
});
