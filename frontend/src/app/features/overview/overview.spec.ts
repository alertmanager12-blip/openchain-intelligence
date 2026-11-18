import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Dashboard } from '../../core/models';
import { OverviewPage } from './overview';

const empty = {
  generatedAt: '2026-11-18T20:00:00Z',
  totals: { walletsTracked: 1, eventsIndexed: 0, transfersIndexed: 0, eventsRetracted: 0, rules: 9, rulesEnabled: 9, alerts: 0, openAlerts: 0, signals24h: 0 },
  riskDistribution: { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0, UNKNOWN: 0 },
  networks: [{ network: 'tron', headBlock: 10, finalizedBlock: 5, healthy: true, providerId: 'p', latencyMillis: 1, checkedAt: '2026-11-18T20:00:00Z',
    detail: null, lastIndexedBlock: 10, indexerLag: 0, enabled: true }],
  activity: [], alerts: { byStatus: {}, bySeverity: {}, openBySeverity: {}, recent: [] }, recentSignals: [], omittedSections: [],
} as unknown as Dashboard;

describe('OverviewPage', () => {
  it('says no alert is open and offers the watchlist when nothing needs attention', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ApiService, useValue: { dashboard: () => of(empty), walletRisk: () => of(null) } }] });
    const f = TestBed.createComponent(OverviewPage);
    await f.whenStable();
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('No open alert right now.');
    expect(el.querySelector('a[href="/watchlist"]')).not.toBeNull();
    expect(el.querySelector('oci-live-strip')).not.toBeNull();
  });
});
