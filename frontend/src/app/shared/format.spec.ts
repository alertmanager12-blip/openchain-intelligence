import { formatUnits, shortHash } from './format';

describe('formatUnits', () => {
  it('formats exactly with grouping and trimmed fractions', () => {
    expect(formatUnits('1234567890000', 6)).toBe('1,234,567.89');
    expect(formatUnits('1', 18)).toBe('0');
    expect(formatUnits('1', 18, 18)).toBe('0.000000000000000001');
  });
  it('does not lose precision on huge amounts', () => {
    expect(formatUnits('123456789012345678901234567890', 18, 18)).toBe('123,456,789,012.34567890123456789');
  });
  it('handles zero, negatives and invalid input', () => {
    expect(formatUnits('0', 6)).toBe('0');
    expect(formatUnits('-1500000', 6)).toBe('-1.5');
    expect(formatUnits('1.5', 6)).toBe('—');
  });
  it('shortens hashes', () => {
    expect(shortHash('0x1234567890abcdef')).toBe('0x1234…cdef');
  });
});
