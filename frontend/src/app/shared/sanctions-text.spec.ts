import { SanctionsList } from '../core/models';
import { sanctionsSentence } from './sanctions-text';

const base: SanctionsList = {
  source: 'OFAC SDN', state: 'CURRENT', fetchEnabled: true, issued: '2026-11-18', fetchedAt: '2026-11-18T22:40:00Z',
  checkedAt: '2026-11-18T22:40:00Z', tron: 351, ethereum: 127, lastError: null,
};

describe('sanctionsSentence (ADR-0027)', () => {
  it('names the list, its publication date and what it covers', () => {
    expect(sanctionsSentence(base)).toBe('Sanctions screening: OFAC SDN of 18 Nov 2026 (351 TRON and 127 Ethereum addresses).');
  });

  it('says when the list in force was not confirmed for too long', () => {
    expect(sanctionsSentence({ ...base, state: 'STALE', checkedAt: '2026-11-14T08:00:00Z' }))
      .toBe('Sanctions screening: OFAC SDN of 18 Nov 2026, not confirmed since 14 Nov 2026: newer listings may be missing.');
  });

  it('never lets a missing list read as no sanction found', () => {
    expect(sanctionsSentence({ ...base, state: 'NONE', issued: null, tron: 0, ethereum: 0 }))
      .toBe('No official sanctions list is loaded: a sanctioned address would not be recognised.');
    expect(sanctionsSentence(undefined)).toBe('Sanctions screening status unknown.');
  });
});
