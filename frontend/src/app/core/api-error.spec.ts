import { HttpErrorResponse, HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError, errorText, fieldErrorText, toApiError } from './api-error';
import { ApiService } from './api.service';
import { authInterceptor, errorInterceptor } from './interceptors';
import { SettingsService } from './settings.service';

const problemHeaders = new HttpHeaders({ 'Content-Type': 'application/problem+json' });

describe('toApiError', () => {
  it('maps an RFC 9457 problem with field errors', () => {
    const e = toApiError(new HttpErrorResponse({
      status: 422,
      headers: problemHeaders,
      error: { type: 'https://openchain.io/problems/rule-invalid', title: 'Rule invalid', status: 422, detail: 'Two errors', instance: '/api/v1/rules/validate',
        errors: [{ pointer: '/when/all/0/op', detail: 'bad operator' }, { field: 'cooldown', message: 'bad duration' }] },
    }));
    expect(e).toBeInstanceOf(ApiError);
    expect(e).toMatchObject({ status: 422, title: 'Rule invalid', detail: 'Two errors', type: 'https://openchain.io/problems/rule-invalid', instance: '/api/v1/rules/validate' });
    expect(e.fieldErrors).toEqual([{ field: '/when/all/0/op', message: 'bad operator' }, { field: 'cooldown', message: 'bad duration' }]);
    expect(e.retryable).toBe(false);
  });

  it('parses a problem delivered as a raw JSON string and supports map-style errors', () => {
    const e = toApiError(new HttpErrorResponse({ status: 400, error: JSON.stringify({ title: 'Bad', errors: { network: 'unknown network' } }) }));
    expect(e.title).toBe('Bad');
    expect(e.fieldErrors).toEqual([{ field: 'network', message: 'unknown network' }]);
  });

  it('falls back to a status-based title for non-problem bodies', () => {
    const e = toApiError(new HttpErrorResponse({ status: 503, error: '<html>Bad gateway</html>' }));
    expect(e).toMatchObject({ status: 503, title: 'Service unavailable', detail: null });
    expect(e.retryable).toBe(true);
  });

  it('maps network failures (status 0) to a retryable "unreachable" error', () => {
    const e = toApiError(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') }));
    expect(e.title).toBe('Backend unreachable');
    expect(e.retryable).toBe(true);
  });

  it('wraps unknown throwables and is idempotent', () => {
    const e = toApiError(new TypeError('boom'));
    expect(e).toMatchObject({ status: -1, detail: 'boom' });
    expect(toApiError(e)).toBe(e);
  });
});

describe('ApiService over HTTP', () => {
  let http: HttpTestingController;
  let api: ApiService;

  beforeEach(() => {
    try { localStorage.clear(); } catch { /* ignore */ }
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(ApiService);
  });

  afterEach(() => http.verify());

  it('surfaces problem+json failures as ApiError through the interceptor', () => {
    let err: unknown;
    api.walletRisk('0xabc', 'ethereum').subscribe({ error: (e) => (err = e) });
    const req = http.expectOne((r) => r.url === '/api/v1/wallets/0xabc/risk');
    expect(req.request.params.get('network')).toBe('ethereum');
    req.flush({ title: 'Provider unavailable', status: 502, detail: 'all RPC providers failed' }, { status: 502, statusText: 'Bad Gateway', headers: problemHeaders });
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).detail).toBe('all RPC providers failed');
    expect((err as ApiError).retryable).toBe(true);
  });

  it('adds the bearer token or API key only when configured', () => {
    const settings = TestBed.inject(SettingsService);
    api.networks().subscribe();
    expect(http.expectOne('/api/v1/networks').request.headers.has('Authorization')).toBe(false);

    settings.update({ authMode: 'bearer', token: 't0k' });
    api.networks().subscribe();
    expect(http.expectOne('/api/v1/networks').request.headers.get('Authorization')).toBe('Bearer t0k');

    settings.update({ authMode: 'apiKey', token: 'oci_live_x' });
    api.networks().subscribe();
    expect(http.expectOne('/api/v1/networks').request.headers.get('X-API-Key')).toBe('oci_live_x');
  });

  it('serialises query params and drops empty ones, encoding path segments', () => {
    api.alerts({ status: 'OPEN', severity: '', cursor: null, limit: 50 }).subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/alerts');
    expect(req.request.params.keys().sort()).toEqual(['limit', 'status']);
    req.flush({ items: [], nextCursor: null });
    api.graph('a/b', { network: 'tron', depth: 2, token: '' }).subscribe();
    const g = http.expectOne((r) => r.url === '/api/v1/graph/a%2Fb');
    expect(g.request.params.get('depth')).toBe('2');
    expect(g.request.params.has('token')).toBe(false);
  });
});

describe('errorText', () => {
  const problem = (extra: Record<string, unknown>) => toApiError(new HttpErrorResponse({
    status: 422, headers: problemHeaders, error: { title: 'Request validation failed', status: 422, detail: 'server english', ...extra },
  }));

  it('translates a known code with its params, in either language', () => {
    const e = problem({ code: 'network.not_enabled', params: { network: 'solana' } });
    expect(errorText(e, 'en')).toBe("network 'solana' is not enabled on this instance");
    expect(errorText(e, 'fr')).toBe('le réseau «\u202fsolana\u202f» n\'est pas activé sur cette instance');
  });

  it('shows the server detail, never a raw key, for a code this console does not know', () => {
    const e = problem({ code: 'brand.new_code', params: {} });
    expect(errorText(e, 'fr')).toBe('server english');
    expect(errorText(e, 'fr')).not.toContain('error.');
  });

  it('falls back to the title without code or detail', () => {
    expect(errorText(new ApiError(500, 'Server error', null), 'fr')).toBe('Server error');
  });

  it('translates field errors by their code and keeps the message otherwise', () => {
    const e = problem({ errors: [
      { pointer: '/network', code: 'field.unsupported_network', params: {}, message: 'network not enabled' },
      { pointer: '/x', code: 'field.future', params: {}, message: 'future english' },
      { pointer: '/y', message: 'plain' },
    ] });
    expect(e.fieldErrors.map((f) => fieldErrorText(f, 'fr'))).toEqual(['réseau non activé', 'future english', 'plain']);
    expect(fieldErrorText(e.fieldErrors[0], 'en')).toBe('network not enabled');
  });

  it('renders list and number params as text', () => {
    const e = problem({ code: 'param.out_of_range', params: { param: 'limit', min: 1, max: 1000 } });
    expect(errorText(e, 'fr')).toBe('limit doit être compris entre 1 et 1\u202f000');
  });
});
