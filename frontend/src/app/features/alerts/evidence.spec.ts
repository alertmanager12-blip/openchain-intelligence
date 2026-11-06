import { compareScale, isoDuration, opSymbol, parseCondition, parseEventId, parseStep } from './evidence';

describe('alert evidence parsing', () => {
  it('parses engine condition outcomes', () => {
    expect(parseCondition('transaction.amount gte 250000 (actual 312000.5) -> true')).toEqual({
      field: 'transaction.amount', op: 'gte', expected: '250000', actual: '312000.5', matched: true,
    });
    expect(parseCondition('transaction.token.symbol in [USDT, USDC] (actual DAI) -> false')?.matched).toBe(false);
    expect(parseCondition('garbage')).toBeNull();
  });

  it('formats operators and ISO durations', () => {
    expect(opSymbol('GTE')).toBe('≥');
    expect(opSymbol('not_in')).toBe('not in');
    expect(isoDuration('PT2H')).toBe('2 h');
    expect(isoDuration('PT1H30M')).toBe('1 h 30 min');
    expect(isoDuration('P1D')).toBe('1 d');
    expect(isoDuration('weird')).toBe('weird');
  });

  it('splits event ids and sequence steps', () => {
    expect(parseEventId('ethereum:0xabc:3')).toEqual({ network: 'ethereum', hash: '0xabc', logIndex: '3' });
    expect(parseEventId('nope')).toBeNull();
    expect(parseStep('deposit=tron:abc:0@2026-01-01T00:00:00Z')).toEqual({ name: 'deposit', eventId: 'tron:abc:0', at: '2026-01-01T00:00:00Z' });
  });

  it('scales observed and threshold for the comparison bar', () => {
    const s = compareScale('12', '8');
    expect(s?.observed).toBeCloseTo(83.33, 1);
    expect(s?.threshold).toBeCloseTo(55.56, 1);
    expect(compareScale('abc', '8')).toBeNull();
  });
});
