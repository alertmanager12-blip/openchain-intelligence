import { levelOfSignal, levelWord } from './levels';

/** ADR-0023 amendment 1 rule 4, as the console shows it (spec 2026-11-18). */
describe('levels of proof', () => {
  it('maps signal evidence levels to the five levels', () => {
    expect(levelOfSignal('ANOMALY', false)).toBe('INFERRED');
    expect(levelOfSignal('RISK_SIGNAL', false)).toBe('INFERRED');
    expect(levelOfSignal('CONFIRMED_BY_SOURCE', true)).toBe('CONFIRMED');
  });

  it('never shows an exposure derived from a confirmed label as confirmed', () => {
    expect(levelOfSignal('CONFIRMED_BY_SOURCE', false)).toBe('INFERRED');
  });

  it('uses the five exact words', () => {
    expect(['OBSERVED', 'INFERRED', 'CONFIRMED', 'PREDICTED', 'SIMULATED'].map((l) => levelWord(l as never)))
      .toEqual(['Observed', 'Inferred', 'Confirmed', 'Predicted', 'Simulated']);
  });
});
