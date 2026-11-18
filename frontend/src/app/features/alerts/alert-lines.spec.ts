import { Alert } from '../../core/models';
import { alertLines } from './alert-lines';

describe('alertLines', () => {
  it('shows an alert as an inferred finding with its own evidence level as detail', () => {
    const a = { evidenceLevel: 'RISK_SIGNAL', evidenceSummary: '24 recipients in 3 minutes', summary: 'Fund splitting' } as unknown as Alert;
    expect(alertLines(a)).toEqual([{ level: 'INFERRED', text: '24 recipients in 3 minutes', detail: 'risk signal' }]);
  });

  it('falls back to the alert summary and never upgrades a rule match to confirmed', () => {
    const a = { evidenceLevel: 'CONFIRMED_BY_SOURCE', evidenceSummary: null, summary: 'Transfer to a sanctioned address' } as unknown as Alert;
    expect(alertLines(a)).toEqual([{ level: 'INFERRED', text: 'Transfer to a sanctioned address', detail: 'confirmed by source' }]);
  });
});
