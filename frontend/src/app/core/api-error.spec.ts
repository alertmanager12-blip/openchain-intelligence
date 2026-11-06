import { HttpErrorResponse, HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError, toApiError } from './api-error';
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
