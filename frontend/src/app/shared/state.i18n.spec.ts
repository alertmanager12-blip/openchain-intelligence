import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ApiError, toApiError } from '../core/api-error';
import { I18nService } from '../core/i18n/i18n.service';
import { StateComponent } from './ui';

const problemHeaders = new HttpHeaders({ 'Content-Type': 'application/problem+json' });
const problem = (status: number, body: Record<string, unknown>) =>
  toApiError(new HttpErrorResponse({ status, headers: problemHeaders, error: { status, title: 'Request validation failed', detail: 'server english', ...body } }));

/** Final review: the shared error state speaks the console language (server codes, status titles, field errors). */
describe('oci-state error in French', () => {
  beforeEach(() => TestBed.inject(I18nService).set('fr'));
  afterEach(() => { TestBed.inject(I18nService).set('en'); localStorage.removeItem('oci.lang'); });

  const render = async (e: ApiError) => {
    const f = TestBed.createComponent(StateComponent);
    f.componentRef.setInput('error', e);
    await f.whenStable();
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  };

  it('shows the French message of a known code under a French status title', async () => {
    const el = await render(problem(422, { code: 'network.not_enabled', params: { network: 'solana' } }));
    expect(el.querySelector('strong')?.textContent).toBe('Validation échouée');
    expect(el.textContent).toContain('le réseau « solana » n\'est pas activé sur cette instance');
    expect(el.textContent).not.toContain('server english');
    expect(el.textContent).not.toContain('Request validation failed');
  });

  it('writes a network failure (status 0) in French', async () => {
    const el = await render(toApiError(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') })));
    expect(el.querySelector('strong')?.textContent).toBe('Serveur injoignable');
    expect(el.textContent).toContain('L’API n’a pas pu être jointe');
    expect(el.textContent).not.toMatch(/Backend unreachable|could not be reached/);
  });

  it('writes a field error through its code, with U+202F before the colon', async () => {
    const el = await render(problem(422, { errors: [{ pointer: '/network', code: 'field.unsupported_network', params: {}, message: 'network not enabled' }] }));
    expect(el.textContent).toContain('/network : réseau non activé');
    expect(el.textContent).not.toContain('network not enabled');
  });

  it('shows the server detail, never a raw key, for a code this console does not know', async () => {
    const el = await render(problem(409, { code: 'brand.new_code', params: {} }));
    expect(el.querySelector('strong')?.textContent).toBe('Conflit');
    expect(el.textContent).toContain('server english');
    expect(el.textContent).not.toContain('error.');
  });

  it('switches back to English', async () => {
    const f = TestBed.createComponent(StateComponent);
    f.componentRef.setInput('error', toApiError(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') })));
    await f.whenStable(); f.detectChanges();
    TestBed.inject(I18nService).set('en');
    await f.whenStable(); f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('strong')?.textContent).toBe('Backend unreachable');
    expect(el.textContent).toContain('The API could not be reached');
  });
});
