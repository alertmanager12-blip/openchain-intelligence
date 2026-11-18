import { rateLine } from './predictions';

describe('predictions page (ADR-0023 amendment 2)', () => {
  const base = { type: 'WAKE_CONTINUED_OUTFLOW', realised: 0, notRealised: 0, undetermined: 0, rate: null, established: false, minEvaluated: 30 };

  it('never presents a rate as established below the threshold', () => {
    expect(rateLine({ ...base, realised: 6, notRealised: 4, undetermined: 2, rate: 0.6 }))
      .toBe('WAKE_CONTINUED_OUTFLOW: 60% so far, not established (fewer than 30 evaluated; 6 realised, 4 not realised, 2 undetermined).');
    expect(rateLine({ ...base, realised: 20, notRealised: 10, rate: 2 / 3, established: true })).toContain('realised 67% of the time');
    expect(rateLine(base)).toContain('no evaluated prediction yet');
  });
});
