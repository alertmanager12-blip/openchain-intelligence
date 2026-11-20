import { TestBed } from '@angular/core/testing';
import { I18nService } from '../core/i18n/i18n.service';
import { EngineText } from './engine-text';

/** Option C (spec 2026-11-19): the engine's English sentences are labelled and marked lang="en" inside a French page. */
describe('EngineText', () => {
  afterEach(() => TestBed.inject(I18nService).set('en'));

  function render(inputs: Record<string, unknown>) {
    const f = TestBed.createComponent(EngineText);
    for (const [k, v] of Object.entries(inputs)) f.componentRef.setInput(k, v);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  it('labels the engine wording in French and marks the text lang="en"', () => {
    TestBed.inject(I18nService).set('fr');
    const el = render({ text: 'Received 3 transfers', mode: 'visible' });
    expect(el.textContent).toContain('Formulation du moteur :');
    const span = el.querySelector('span[lang="en"]');
    expect(span?.textContent?.trim()).toBe('Received 3 transfers');
    expect(el.querySelector('details')).toBeNull();
  });

  it('separates the label from the sentence by a space', () => {
    TestBed.inject(I18nService).set('fr');
    expect(render({ text: 'Received 3 transfers', mode: 'visible' }).querySelector('.engine')?.textContent).toBe('Formulation du moteur\u202f: Received 3 transfers');
  });

  it('labels it in English too', () => {
    expect(render({ text: 'x' }).textContent).toContain("Engine's wording:");
  });

  it('collapsed mode renders a details element with the label as summary', () => {
    TestBed.inject(I18nService).set('fr');
    const el = render({ text: 'Received 3 transfers', mode: 'collapsed' });
    expect(el.querySelector('details > summary')?.textContent).toContain('Formulation du moteur');
    expect(el.querySelector('details span[lang="en"]')?.textContent).toContain('Received 3 transfers');
  });
});
