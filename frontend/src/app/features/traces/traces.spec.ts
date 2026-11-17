import { fractionUnits } from './traces';

describe('stored traces page', () => {
  it('shows an exact fraction of raw units in token units, rounded only for display', () => {
    expect(fractionUnits({ num: 100_000_000, den: 1 }, 6)).toBe('100');
    expect(fractionUnits({ num: '1', den: '3' }, 0)).toBe('0.333333');
    // 30 USDT of a 60/40 pool: 50 * 60 / 100 = 30, exact as a fraction of raw units
    expect(fractionUnits({ num: 3_000_000_000, den: 100 }, 6)).toBe('30');
  });
});
