import { rateLine } from './predictions';

describe('predictions page (ADR-0023 amendment 2)', () => {
  const base = { type: 'WAKE_CONTINUED_OUTFLOW', realised: 0, notRealised: 0, undetermined: 0, rate: null, established: false, minEvaluated: 30 };

  it('never presents a rate as established below the threshold', () => {
    expect(rateLine({ ...base, realised: 6, notRealised: 4, undetermined: 2, rate: 0.6 }))
      .toBe('Continued outflow after wake-up: 60% so far, not established (fewer than 30 evaluated; 6 realised, 4 not realised, 2 undetermined).');
    expect(rateLine({ ...base, realised: 20, notRealised: 10, rate: 2 / 3, established: true })).toContain('realised 67% of the time');
    expect(rateLine(base)).toContain('no evaluated prediction yet');
  });

  it('names the prediction type in French, falling back to the raw code for an unknown one', () => {
    expect(rateLine({ ...base, realised: 1, rate: 1 }, 'fr')).toContain('Reprise des sorties après réveil');
    expect(rateLine({ ...base, type: 'SOMETHING_NEW' }, 'fr')).toContain('SOMETHING_NEW');
    expect(rateLine({ ...base, type: 'SOMETHING_NEW' }, 'en')).toContain('SOMETHING_NEW');
  });

  it('agrees the counts in number in both languages, without "(s)"', () => {
    const c = (n: number, lang: 'en' | 'fr') => rateLine({ ...base, realised: n, notRealised: n, undetermined: n }, lang);
    expect(c(0, 'fr')).toContain('0 réalisée, 0 non réalisée, 0 indéterminée');
    expect(c(1, 'fr')).toContain('1 réalisée, 1 non réalisée, 1 indéterminée');
    expect(c(2, 'fr')).toContain('2 réalisées, 2 non réalisées, 2 indéterminées');
    expect(c(2, 'en')).toContain('2 realised, 2 not realised, 2 undetermined');
    for (const n of [0, 1, 2]) expect(c(n, 'fr') + c(n, 'en')).not.toContain('(s)');
  });
});
