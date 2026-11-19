import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { Alert, Dashboard } from '../../core/models';
import { ALERT_FIELDS } from '../../shared/testing/alert-fields.fixture';
import { OverviewPage } from './overview';

const NOW = Date.now();
const net = { network: 'tron', headBlock: 10, finalizedBlock: 5, healthy: true, providerId: 'p', latencyMillis: 1, checkedAt: new Date(NOW).toISOString(),
  detail: null, lastIndexedBlock: 10, indexerLag: 0, enabled: true, lastIndexedBlockTime: new Date(NOW - 60_000).toISOString() };
const base = {
  generatedAt: new Date(NOW).toISOString(),
  totals: { walletsTracked: 1, eventsIndexed: 0, transfersIndexed: 0, eventsRetracted: 0, rules: 9, rulesEnabled: 9, alerts: 0, openAlerts: 0, signals24h: 0 },
  riskDistribution: { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0, UNKNOWN: 0 },
  networks: [net], activity: [], recentSignals: [], omittedSections: [],
  alerts: { byStatus: {}, bySeverity: {}, openBySeverity: {}, recent: [], attention: [], last24h: { total: 0, byRule: [] } },
  coverage: { gaps: [] }, notifications: { unroutedRules: [], openAlertsUnrouted: 0 },
} as unknown as Dashboard;

const drain = (id: string): Alert => ({ id, ruleId: 'wallet-draining', ruleName: 'Wallet drained', severity: 'HIGH', status: 'OPEN', network: 'ethereum',
  address: '0xabc', txHash: null, counterparty: null, blockNumber: 1, summary: 'Engine summary', evidenceSummary: 'Engine evidence',
  evidenceLevel: 'RISK_SIGNAL', assignee: null, createdAt: new Date(NOW).toISOString(), updatedAt: new Date(NOW).toISOString(),
  matchedEvent: ALERT_FIELDS['wallet-draining'][0].fields, comments: [], history: [] }) as unknown as Alert;
const busy = {
  ...base,
  totals: { ...base.totals, openAlerts: 1385 },
  alerts: { byStatus: { OPEN: 1385 }, bySeverity: {}, openBySeverity: { HIGH: 881, MEDIUM: 504 }, recent: [],
    attention: ['1', '2', '3', '4', '5'].map(drain), last24h: { total: 135, byRule: [{ ruleId: 'large-usdt-transfer', ruleName: 'Large USDT', count: 81 },
      { ruleId: 'wallet-draining', ruleName: 'Wallet drained', count: 54 }] } },
} as unknown as Dashboard;

async function render(d: Dashboard, lang: 'en' | 'fr' = 'en') {
  localStorage.setItem('oci.lang', lang);
  TestBed.configureTestingModule({ providers: [provideRouter([]),
    { provide: ApiService, useValue: { dashboard: () => of(d), sanctionsLists: () => of({ items: [] }) } }] });
  const f = TestBed.createComponent(OverviewPage);
  await f.whenStable();
  f.detectChanges();
  return f;
}

/** Spec 2026-11-19 §4: reliability first, then what to handle, then a compact side column. */
describe('OverviewPage', () => {
  afterEach(() => localStorage.removeItem('oci.lang'));

  it('says no alert is open and offers the watchlist when nothing needs attention', async () => {
    const f = await render(base);
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('No open alert right now.');
    expect(el.querySelector('a[href="/watchlist"]')).not.toBeNull();
  });

  it('shows the band, at most 5 verdicts and the link to every open alert, in French', async () => {
    const f = await render(busy, 'fr');
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('oci-reliability-band')?.textContent).toContain('Ma surveillance est-elle fiable ?');
    expect(el.textContent).toContain('À traiter maintenant');
    expect(el.textContent).toContain('24 dernières heures');
    expect(el.querySelectorAll('oci-verdict').length).toBe(5);
    expect(el.querySelector('oci-verdict')?.textContent).toContain('Portefeuille vidé');
    // a verdict is not inside a link (its "engine's wording" must open): the alert has its own link
    expect(el.querySelector('a oci-verdict')).toBeNull();
    expect(el.querySelector('a.open-alert')?.getAttribute('href')).toBe('/alerts?id=1');
    const all = el.querySelector<HTMLAnchorElement>('a.all-open')!;
    expect(all.getAttribute('href')).toBe('/alerts?status=OPEN');
    expect(all.textContent?.trim()).toBe('Voir les 1 385 alertes ouvertes');
    // the blocks the overview no longer shows
    expect(el.querySelector('oci-live-strip')).toBeNull();
    expect(el.querySelector('table')).toBeNull();
    expect(el.textContent).not.toContain('Recent signals');
  });

  it('re-renders in English when the language changes, without navigating (Review Focus 5)', async () => {
    const f = await render(busy, 'fr');
    TestBed.inject(I18nService).set('en');
    await f.whenStable();
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Is monitoring reliable?');
    expect(el.textContent).toContain('Handle now');
    expect(el.querySelector('a.all-open')?.textContent?.trim()).toBe('See all 1,385 open alerts');
    expect(el.querySelector('oci-verdict')?.textContent).toContain('Wallet drained');
    expect(el.textContent).not.toContain('À traiter maintenant');
  });
});
