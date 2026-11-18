import { Alert, Dashboard, Signal } from '../../core/models';
import { attention } from './attention';

const alert = (id: string, severity: string, status = 'OPEN', address: string | null = 'TA' + id): Alert =>
  ({ id, severity, status, network: 'tron', address, ruleName: 'Rule ' + id, summary: 'Summary ' + id, blockNumber: 1 }) as unknown as Alert;
const signal = (address: string, evidenceLevel: string): Signal =>
  ({ id: 's-' + address, address, network: 'tron', evidenceLevel, summary: 'Signal on ' + address, type: 'SANCTIONED_EXPOSURE' }) as unknown as Signal;
const dash = (recent: Alert[] | null, signals: Signal[] = []): Dashboard =>
  ({ alerts: recent === null ? null : { recent }, recentSignals: signals }) as unknown as Dashboard;

describe('attention', () => {
  it('keeps the three most severe open alerts', () => {
    const r = attention(dash([alert('a', 'LOW'), alert('b', 'CRITICAL'), alert('c', 'HIGH', 'RESOLVED'), alert('d', 'HIGH'), alert('e', 'MEDIUM')]));
    expect(r.map((x) => x.sentence)).toEqual(['Summary b', 'Summary d', 'Summary e']);
    expect(r[0].risk).toBe('CRITICAL');
  });

  it('shows the signals of the alert wallet as inferred findings, with their original level as detail', () => {
    const r = attention(dash([alert('a', 'HIGH')], [signal('TAa', 'CONFIRMED_BY_SOURCE'), signal('TAz', 'RISK_SIGNAL')]));
    expect(r[0].lines).toEqual([{ level: 'INFERRED', text: 'Signal on TAa', detail: 'confirmed by source' }]);
  });

  it('is empty without open alert or when the section is omitted', () => {
    expect(attention(dash([]))).toEqual([]);
    expect(attention(dash(null))).toEqual([]);
  });
});

import { emptyState } from './attention';

describe('emptyState (final review, 2026-11-18)', () => {
  const d = (o: Partial<{ alerts: unknown; open: Record<string, number> }>): Dashboard =>
    ({ alerts: 'alerts' in o ? o.alerts : { recent: [], openBySeverity: o.open ?? {} } }) as unknown as Dashboard;

  it('never says nothing needs attention while open alerts exist outside the latest ones', () => {
    const s = emptyState(d({ open: { CRITICAL: 2, HIGH: 1, MEDIUM: 0 } }));
    expect(s.kind).toBe('hidden-open');
    expect(s.text).toBe('3 open alerts (2 critical, 1 high) are not among the latest alerts shown here.');
  });

  it('says when alerts cannot be read with this key', () => {
    expect(emptyState(d({ alerts: null })).kind).toBe('no-permission');
  });

  it('says that no alert is open when none is', () => {
    expect(emptyState(d({ open: {} }))).toEqual({ kind: 'none-open', text: 'No open alert right now.' });
  });
});
