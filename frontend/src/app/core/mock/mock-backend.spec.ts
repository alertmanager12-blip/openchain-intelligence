import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ApiError } from '../api-error';
import { ApiService } from '../api.service';
import { detectQuery } from '../network-detect';
import { authInterceptor, errorInterceptor } from '../interceptors';
import { SettingsService } from '../settings.service';
import { mockBackendInterceptor } from './mock-backend.interceptor';
import { fakeAddress } from './mock-db';
import { isNetworkNotEnabled } from '../../shared/network-not-enabled';

describe('mock backend (through the real ApiService + interceptors)', () => {
  let api: ApiService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))] });
    TestBed.inject(SettingsService).update({ mock: true });
    api = TestBed.inject(ApiService);
  });

  it('generates fake addresses the detector recognises', () => {
    expect(detectQuery(fakeAddress('ethereum', 3)).candidates).toContain('ethereum');
    expect(detectQuery(fakeAddress('tron', 3)).candidates).toEqual(['tron']);
    expect(detectQuery(fakeAddress('bitcoin', 3)).candidates).toEqual(['bitcoin']);
    expect(detectQuery(fakeAddress('solana', 3)).candidates).toEqual(['solana']);
  });

  it('serves a risk score whose level matches the core thresholds', async () => {
    const r = await firstValueFrom(api.walletRisk(fakeAddress('ethereum', 7), 'ethereum'));
    const expected = r.score >= 85 ? 'CRITICAL' : r.score >= 65 ? 'HIGH' : r.score >= 35 ? 'MEDIUM' : 'LOW';
    expect(r.level).toBe(expected);
    expect(r.contributions.every((c) => c.explanation && c.source)).toBe(true);
  });

  it('serves the explainability fields with the backend factor keys', async () => {
    const r = await firstValueFrom(api.walletRisk(fakeAddress('ethereum', 7), 'ethereum'));
    expect(r.methodSummary).toBeTruthy();
    expect(r.contributions.every((c) => /^(signal|label):[A-Z_]+$/.test(c.factor) && c.title && c.summary)).toBe(true);
    const sig = (await firstValueFrom(api.recentSignals())).items;
    expect(sig.every((x) => x.title && x.summary)).toBe(true);
    const alerts = (await firstValueFrom(api.alerts({}))).items;
    expect(alerts.every((x) => x.address && x.txHash && x.evidenceSummary && x.blockNumber !== null)).toBe(true);
  });

  it('reports history coverage: NOT_CONFIGURED on the simulated chain, loading then terminal on Ethereum', async () => {
    expect((await firstValueFrom(api.walletAnalysis(fakeAddress('tron', 4), 'tron'))).history?.status).toBe('NOT_CONFIGURED');
    const a = fakeAddress('ethereum', 11);
    const seen: string[] = [];
    for (let i = 0; i < 4; i++) seen.push((await firstValueFrom(api.walletAnalysis(a, 'ethereum'))).history!.status);
    expect(seen.slice(0, 2)).toEqual(['PENDING', 'RUNNING']);
    expect(['COMPLETE', 'TRUNCATED']).toContain(seen[2]);
    expect(seen[3]).toBe(seen[2]);
  });

  it('answers like the backend for a network this instance does not index', async () => {
    const nets = await firstValueFrom(api.networks());
    expect(nets.map((n) => n.network)).not.toContain('bitcoin');
    const err = await firstValueFrom(api.walletAnalysis(fakeAddress('bitcoin', 1), 'bitcoin')).catch((e) => e);
    expect((err as ApiError).status).toBe(422);
    expect((err as ApiError).detail).toBe("network 'bitcoin' is not enabled on this instance");
    expect(isNetworkNotEnabled(err as ApiError)).toBe(true);
  });

  it('returns problem+json errors as ApiError (provider outage is retryable)', async () => {
    const err = await firstValueFrom(api.walletRisk(fakeAddress('solana', 1), 'solana')).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(503);
    expect((err as ApiError).retryable).toBe(true);
  });

  it('runs the alert lifecycle and rejects invalid transitions', async () => {
    const page = await firstValueFrom(api.alerts({ status: 'OPEN' }));
    const a = page.items[0];
    expect((await firstValueFrom(api.acknowledgeAlert(a.id))).status).toBe('ACKNOWLEDGED');
    const commented = await firstValueFrom(api.commentAlert(a.id, 'checked with exchange'));
    expect(commented.comments.at(-1)!.body).toBe('checked with exchange');
    expect((await firstValueFrom(api.setAlertStatus(a.id, 'RESOLVED'))).history[0].action).toBe('RESOLVED');
    const bad = await firstValueFrom(api.setAlertStatus(a.id, 'INVESTIGATING')).catch((e) => e);
    expect((bad as ApiError).status).toBe(409);
  });

  it('validates rules server-side and refuses invalid ones with field errors', async () => {
    const v = await firstValueFrom(api.validateRule('apiVersion: openchain.io/v1\nkind: Rule\n'));
    expect(v.valid).toBe(false);
    expect(v.errors.length).toBeGreaterThan(0);
    const err = await firstValueFrom(api.createRule('kind: Rule\n')).catch((e) => e);
    expect((err as ApiError).status).toBe(422);
    expect((err as ApiError).fieldErrors.length).toBeGreaterThan(0);
  });

  it('dry-runs a stored rule', async () => {
    const rules = await firstValueFrom(api.rules());
    const now = new Date();
    const r = await firstValueFrom(api.testRule({ ruleId: rules.items[0].id, from: new Date(now.getTime() - 86_400_000).toISOString(), to: now.toISOString(), mode: 'DRY_RUN' }));
    expect(r.evaluatedEvents).toBeGreaterThan(0);
    expect(r.matchCount).toBeLessThanOrEqual(r.evaluatedEvents);
  });
});
