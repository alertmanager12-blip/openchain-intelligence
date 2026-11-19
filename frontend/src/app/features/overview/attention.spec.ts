import { Alert, Dashboard, Signal } from '../../core/models';
import { attention, emptyState } from './attention';

const alert = (id: string, severity: string, status = 'OPEN', address: string | null = 'TA' + id): Alert =>
  ({ id, ruleId: 'custom-' + id, severity, status, network: 'tron', address, ruleName: 'Rule ' + id, summary: 'Summary ' + id,
    evidenceSummary: null, matchedEvent: {}, blockNumber: 1 }) as unknown as Alert;
const signal = (address: string, evidenceLevel: string, type = 'INDIRECT_EXPOSURE'): Signal =>
  ({ id: 's-' + address, address, network: 'tron', evidenceLevel, confidence: 0.5, summary: 'Engine: signal on ' + address, title: 'Engine title', type }) as unknown as Signal;
const dash = (recent: Alert[] | null, signals: Signal[] = [], top?: Alert[]): Dashboard =>
  ({ alerts: recent === null ? null : { recent, attention: top }, recentSignals: signals }) as unknown as Dashboard;

describe('attention (spec 2026-11-19 §4)', () => {
  it('takes the most severe open alerts from the server, up to 5', () => {
    const top = ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => alert(id, 'HIGH'));
    expect(attention(dash([], [], top), 'en').map((x) => x.id)).toEqual(['a', 'b', 'c', 'd', 'e']);
  });

  it('falls back to the latest alerts on an older server', () => {
    const r = attention(dash([alert('a', 'LOW'), alert('b', 'CRITICAL'), alert('c', 'HIGH', 'RESOLVED'), alert('d', 'HIGH')]), 'en');
    expect(r.map((x) => x.id)).toEqual(['b', 'd', 'a']);
    expect(r[0].risk).toBe('CRITICAL');
  });

  it('writes signal lines in the console language, with the engine wording kept apart', () => {
    const r = attention(dash([], [signal('TAa', 'CONFIRMED_BY_SOURCE')], [alert('a', 'HIGH')]), 'fr');
    expect(r[0].lines).toEqual([{ level: 'INFERRED', text: 'Exposition indirecte à une adresse listée', detail: 'confirmé par la source · confiance 50 %',
      original: 'Engine: signal on TAa' }]);
  });

  it('keeps the engine title for a signal type the console does not know', () => {
    const r = attention(dash([], [signal('TAa', 'RISK_SIGNAL', 'BRAND_NEW')], [alert('a', 'HIGH')]), 'en');
    expect(r[0].lines[0].text).toBe('Engine title');
  });

  it('is empty without open alert or when the section is omitted', () => {
    expect(attention(dash([], [], []), 'en')).toEqual([]);
    expect(attention(dash(null), 'en')).toEqual([]);
  });
});

describe('emptyState', () => {
  const d = (o: Partial<{ alerts: unknown; open: Record<string, number> }>): Dashboard =>
    ({ alerts: 'alerts' in o ? o.alerts : { recent: [], openBySeverity: o.open ?? {} } }) as unknown as Dashboard;

  it('never says nothing needs attention while open alerts exist', () => {
    expect(emptyState(d({ open: { CRITICAL: 2, HIGH: 1, MEDIUM: 0 } }), 'en'))
      .toEqual({ kind: 'hidden-open', text: '3 open alerts (2 critical, 1 high) are not shown here.' });
    expect(emptyState(d({ open: { CRITICAL: 2, HIGH: 1 } }), 'fr').text).toBe('3 alertes ouvertes (2 critiques, 1 élevée) ne sont pas affichées ici.');
  });

  it('says when alerts cannot be read with this key', () => {
    expect(emptyState(d({ alerts: null }), 'fr')).toEqual({ kind: 'no-permission', text: "Les alertes ne sont pas visibles avec cette clé : il lui manque la permission alerts:read." });
  });

  it('says that no alert is open when none is', () => {
    expect(emptyState(d({ open: {} }), 'fr')).toEqual({ kind: 'none-open', text: 'Aucune alerte ouverte pour le moment.' });
    expect(emptyState(d({ open: {} }), 'en')).toEqual({ kind: 'none-open', text: 'No open alert right now.' });
  });
});
