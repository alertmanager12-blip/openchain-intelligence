import { formatUnits, indexerLag, shortHash } from './format';
import { severityName, statusName } from './format';

describe('formatUnits', () => {
  it('formats exactly with grouping and trimmed fractions', () => {
    expect(formatUnits('1234567890000', 6)).toBe('1,234,567.89');
    expect(formatUnits('1', 18)).toBe('<0.000001');     // non-zero below the precision: never "0"
    expect(formatUnits('4000', 6, 2)).toBe('<0.01');      // 0.004 USDT of dust stays visible
    expect(formatUnits('-4000', 6, 2)).toBe('-<0.01');
    expect(formatUnits('1', 6, 0)).toBe('<1');
    expect(formatUnits('1004000', 6, 2)).toBe('1');       // truncation above the smallest unit is unchanged
    expect(formatUnits('1', 18, 18)).toBe('0.000000000000000001');
  });
  it('does not lose precision on huge amounts', () => {
    expect(formatUnits('123456789012345678901234567890', 18, 18)).toBe('123,456,789,012.34567890123456789');
  });
  it('handles zero, negatives and invalid input', () => {
    expect(formatUnits('0', 6)).toBe('0');
    expect(formatUnits('-0', 6)).toBe('0');
    expect(formatUnits('000', 6)).toBe('0');
    expect(formatUnits('-1500000', 6)).toBe('-1.5');
    expect(formatUnits('1.5', 6)).toBe('—');
  });
  it('reports an unknown indexing lag as null, never as 0', () => {
    expect(indexerLag({ indexerLag: 12, headBlock: 100, finalizedBlock: 90, healthy: true })).toBe(12);
    expect(indexerLag({ indexerLag: 0, headBlock: 100, finalizedBlock: 90, healthy: true })).toBe(0);
    expect(indexerLag({ indexerLag: null, headBlock: 0, finalizedBlock: 0, healthy: false })).toBeNull();
    expect(indexerLag({ headBlock: 100, finalizedBlock: 90 })).toBe(10);               // older payload
    expect(indexerLag({ headBlock: 0, finalizedBlock: 0, healthy: false })).toBeNull(); // older payload, provider down
  });
  it('shortens hashes', () => {
    expect(shortHash('0x1234567890abcdef')).toBe('0x1234…cdef');
  });
});

describe('severity and status names (spec 2026-11-19)', () => {
  it('names them in both languages', () => {
    expect(severityName('HIGH', 'fr')).toBe('Élevée');
    expect(severityName('HIGH', 'en')).toBe('High');
    expect(statusName('OPEN', 'fr')).toBe('Ouverte');
    expect(statusName('FALSE_POSITIVE', 'fr')).toBe('Faux positif');
    expect(statusName('FALSE_POSITIVE', 'en')).toBe('False positive');
  });
});
