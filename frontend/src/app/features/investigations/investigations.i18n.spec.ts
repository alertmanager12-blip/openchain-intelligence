import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { InvestigationsPage } from './investigations';

const A = '0xfa4ede30000000000000000000000000000000cc';
const summary = { id: 'i1', lineageId: 'l', version: 2, previousId: 'i0', network: 'ethereum', seed: A, direction: 'FORWARD', status: 'DONE', resultStatus: 'COMPLETE',
  stale: true, createdBy: null, createdAt: '2026-11-19T10:00:00Z', completedAt: null };
const detail = { ...summary, subject: A, plan: 'standard@1', staleEventIds: ['e1'], frozenFlows: 3, error: null, assertions: [], diff: { added: ['a'], removed: [] },
  report: { plan: 'standard@1', templates: 'report@1', subject: A, assertions: 2, sections: [
    { title: 'Coverage and limits', sentences: [{ text: 'Data covered: partial.', assertionId: 'as_c', level: 'OBSERVED' }] },
    { title: 'Value flows', sentences: [{ text: 'Value sent may have reached TX.', assertionId: 'as_1', level: 'INFERRED' }] }] } };
const reviews = { investigationId: 'i1', version: 2, stale: true, decided: 1, total: 2, history: [],
  current: { as_1: { decision: 'ACCEPTED', comment: null, actor: 'apikey:analyst', createdAt: '2026-11-17T10:00:00Z', version: 1, inherited: true } } };
const provider = { id: 'p', label: 'Local Ollama', connector: 'x', model: 'm', dataLocation: 'this machine' };
const aiSummary = { id: 's1', investigationId: 'i1', provider: 'p', model: 'm', promptVersion: 'v', inputHash: 'h', status: 'ACCEPTED',
  sentences: [{ text: 'The wallet sent value onward.', assertionIds: ['as_1'] }], reasons: [], createdBy: 'a', createdAt: '2026-11-18T08:00:00Z' };

describe('Investigations screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ investigations: () => of([summary]), investigation: () => of(detail), investigationReviews: () => of(reviews),
      aiProviders: () => of([provider]), investigationSummaries: () => of([aiSummary]) } as Record<string, unknown>,
      { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => localStorage.removeItem('oci.lang'));
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };

  it('writes the list, the report and the review in French, the AI sentences with lang="en", and switches back', async () => {
    const f = TestBed.createComponent(InvestigationsPage);
    f.componentRef.setInput('id', 'i1');
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Enquêtes');
    expect(el.textContent).toContain('Vos enquêtes');
    expect(el.textContent).toContain('3 transferts figés');
    expect(el.textContent).toContain('Couverture et limites');
    expect(el.textContent).toContain('Flux de valeur');
    expect(el.textContent).toContain('Déduit');
    expect(el.textContent).toContain('Acceptée par apikey:analyst');
    expect(el.textContent).toContain('Relancer (nouvelle version)');
    expect(el.textContent).toContain('Résumé par IA');
    const engine = Array.from(el.querySelectorAll('[lang="en"]')).map((n) => n.textContent);
    expect(engine.some((t) => t?.includes('The wallet sent value onward.'))).toBe(true);
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Investigations');
    expect(el.textContent).toContain('3 frozen transfers');
    expect(el.textContent).toContain('Coverage and limits');
    expect(el.textContent).toContain('Accepted by apikey:analyst');
    expect(el.querySelector('.grid [lang="en"]')).toBeNull();
    f.destroy();
  });
});
