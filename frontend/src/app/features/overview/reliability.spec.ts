import { Dashboard, NetworkStatus, SanctionsList } from '../../core/models';
import { reliability } from './reliability';

const NOW = Date.parse('2026-11-19T20:00:00Z');
const min = (m: number) => new Date(NOW - m * 60_000).toISOString();
const day = (d: number) => new Date(NOW - d * 86_400_000).toISOString();

const net = (network: 'tron' | 'ethereum', lag: number | null, behindMin: number | null, healthy = true): NetworkStatus => ({
  network, headBlock: 1000, finalizedBlock: 990, healthy, providerId: 'p', latencyMillis: 10, checkedAt: min(0), detail: null,
  enabled: true, lastIndexedBlock: 1000 - (lag ?? 0), indexerLag: lag, lastIndexedBlockTime: behindMin === null ? null : min(behindMin),
});
const list: SanctionsList = { source: 'OFAC SDN', state: 'CURRENT', fetchEnabled: true, issued: '2026-11-19', fetchedAt: min(60),
  checkedAt: min(60), tron: 351, ethereum: 128, lastError: null };

function dash(p: Partial<Dashboard> = {}): Dashboard {
  return {
    generatedAt: new Date(NOW).toISOString(),
    totals: { walletsTracked: 1, eventsIndexed: 1, transfersIndexed: 1, eventsRetracted: 0, rules: 10, rulesEnabled: 8, alerts: 1400, openAlerts: 1385, signals24h: 1 },
    riskDistribution: { UNKNOWN: 0, LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 },
    networks: [net('tron', 2, 1), net('ethereum', 1, 2)],
    activity: [],
    alerts: null,
    recentSignals: null,
    omittedSections: [],
    coverage: { gaps: [] },
    notifications: { unroutedRules: [], openAlertsUnrouted: 0 },
    ...p,
  };
}

/** The 19 Nov production state: 29,640 TRON blocks skipped yesterday, 1,385 open alerts to nobody, Ethereum 1 h 20 behind. */
const nov19 = dash({
  networks: [net('tron', 536, 27), net('ethereum', 414, 80)],
  coverage: { gaps: [{ network: 'tron', fromBlock: 88122971, toBlock: 88152610, blocks: 29640, cause: 'RESUME_GAP', detectedAt: day(1) }] },
  notifications: { unroutedRules: ['fund-splitting', 'wallet-draining'], openAlertsUnrouted: 1385 },
});

/** Spec 2026-11-19 §4: "Is monitoring reliable?" */
describe('reliability', () => {
  it('says yes only when every check passed', () => {
    const r = reliability(dash(), list, 'en', NOW);
    expect(r.verdict).toBe('yes');
    expect(r.lines.every((l) => l.state === 'pass')).toBe(true);
  });

  it('says partly on 19 Nov, problems first in a fixed order', () => {
    const r = reliability(nov19, list, 'en', NOW);
    expect(r.verdict).toBe('partly');
    expect(r.lines.map((l) => `${l.check}:${l.state}`)).toEqual([
      'gaps:warn', 'notifications:warn', 'delay:warn', 'delay:warn', 'health:pass', 'sanctions:pass']);
  });

  it('writes the 19 Nov lines in French and in English', () => {
    const fr = reliability(nov19, list, 'fr', NOW).lines.map((l) => l.text);
    expect(fr).toContain('29 640 blocs TRON jamais analysés (≈ 24 h 42 min, détecté le 18 nov. 2026)');
    expect(fr).toContain('1 385 alertes ouvertes ne parviennent à personne (2 règles sans destination)');
    expect(fr).toContain('Ethereum : 1 h 20 min de retard');
    expect(fr).toContain('Liste de sanctions : OFAC SDN du 19 nov. 2026 (351 adresses TRON et 128 adresses Ethereum).');
    const en = reliability(nov19, list, 'en', NOW).lines.map((l) => l.text);
    expect(en).toContain('29,640 TRON blocks never analysed (≈ 24 h 42 min, detected on 18 Nov 2026)');
    expect(en).toContain('1,385 open alerts reach nobody (2 rules without a destination)');
    expect(en).toContain('Ethereum: 1 h 20 min behind');
  });

  it('warns about a rule without destination even before it has open alerts', () => {
    const r = reliability(dash({ notifications: { unroutedRules: ['fund-splitting'], openAlertsUnrouted: 0 } }), list, 'fr', NOW);
    const l = r.lines.find((x) => x.check === 'notifications')!;
    expect(l.state).toBe('warn');
    expect(l.text).toBe("1 règle active n'a pas de destination : ses alertes ne parviendraient à personne");
    expect(reliability(dash({ notifications: { unroutedRules: ['a', 'b'], openAlertsUnrouted: 0 } }), list, 'en', NOW).lines
      .find((x) => x.check === 'notifications')!.text).toBe('2 enabled rules have no destination: their alerts would reach nobody');
  });

  it('links each fixable problem to where it is fixed', () => {
    const r = reliability(nov19, list, 'en', NOW);
    expect(r.lines.find((l) => l.check === 'notifications')?.link?.path).toBe('/integrations');
    expect(r.lines.find((l) => l.check === 'sanctions')?.link).toBeUndefined(); // a current list is not a problem
    const stale = reliability(nov19, { ...list, state: 'STALE' }, 'en', NOW);
    expect(stale.lines.find((l) => l.check === 'sanctions')?.link?.path).toBe('/settings');
  });

  it('says no when a network is unhealthy', () => {
    expect(reliability(dash({ networks: [net('tron', 2, 1, false), net('ethereum', 1, 2)] }), list, 'en', NOW).verdict).toBe('no');
  });

  it('says no, never a delay of 0, when the chain head is unknown (Review Focus 1)', () => {
    const r = reliability(dash({ networks: [net('tron', null, 1), net('ethereum', 1, 2)] }), list, 'fr', NOW);
    expect(r.verdict).toBe('no');
    const l = r.lines.find((x) => x.check === 'delay' && x.text.startsWith('TRON'))!;
    expect(l.state).toBe('unknown');
    expect(l.text).toBe('TRON : retard inconnu (tête de chaîne inconnue)');
  });

  it('never passes a check the role cannot see (Review Focus 2)', () => {
    const r = reliability(dash({ notifications: null, omittedSections: ['notifications (needs rules:read and webhooks:read)'] }), list, 'fr', NOW);
    expect(r.verdict).not.toBe('yes');
    const l = r.lines.find((x) => x.check === 'notifications')!;
    expect(l.state).toBe('unknown');
    expect(l.text).toBe('Notifications : non visibles avec votre rôle');
  });

  it('falls back to blocks when the time of the last block is unknown, without passing (Review Focus 3)', () => {
    const r = reliability(dash({ networks: [net('tron', 536, null), net('ethereum', 1, 2)] }), list, 'en', NOW);
    const l = r.lines.find((x) => x.check === 'delay' && x.text.startsWith('TRON'))!;
    expect(l.state).toBe('unknown');
    expect(l.text).toBe('TRON: 536 blocks behind (time of the last block unknown)');
    expect(r.verdict).toBe('partly');
  });

  it('lets an old gap not hold the band, but keeps it reachable (Review Focus 4)', () => {
    const r = reliability(dash({ coverage: { gaps: [{ network: 'tron', fromBlock: 1, toBlock: 100, blocks: 100, cause: 'RESUME_GAP', detectedAt: day(10) }] } }),
      list, 'en', NOW);
    expect(r.verdict).toBe('yes');
    expect(r.olderGaps).toEqual([{ network: 'tron', fromBlock: 1, toBlock: 100, blocks: 100, cause: 'RESUME_GAP', detectedAt: day(10) }]);
  });

  it('says no without a sanctions list, partly with a stale one, unknown when it could not be read', () => {
    expect(reliability(dash(), { ...list, state: 'NONE', issued: null }, 'en', NOW).verdict).toBe('no');
    expect(reliability(dash(), { ...list, state: 'STALE' }, 'en', NOW).verdict).toBe('partly');
    const r = reliability(dash(), null, 'en', NOW);
    expect(r.lines.find((l) => l.check === 'sanctions')?.state).toBe('unknown');
    expect(r.verdict).toBe('partly');
  });

  it('counts a missing coverage section as unknown, not as no gap', () => {
    const r = reliability(dash({ coverage: undefined }), list, 'en', NOW);
    expect(r.lines.find((l) => l.check === 'gaps')?.state).toBe('unknown');
    expect(r.verdict).toBe('partly');
  });
});
