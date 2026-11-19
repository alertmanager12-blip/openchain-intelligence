import { contributionSummary, contributionTitle, factorTitle, signalSummary, signalTitle } from './explain';
import { categoryName, signalTypeName } from './explain';

describe('plain-language fallbacks', () => {
  it('names backend factor keys', () => {
    expect(factorTitle('signal:RAPID_DRAIN')).toBe('Rapid drain');
    expect(factorTitle('signal:SANCTIONED_EXPOSURE')).toBe('Direct exposure to a sanctioned address');
    expect(factorTitle('label:SANCTIONED')).toBe('Listed as sanctioned');
    expect(factorTitle('label:ISSUER_FROZEN')).toBe('Frozen by the stablecoin issuer');
    expect(factorTitle('SOME_NEW_FACTOR')).toBe('Some new factor');
  });

  it('prefers the server title and summary, and falls back on older payloads', () => {
    expect(contributionTitle({ factor: 'signal:RAPID_DRAIN', title: 'Drained fast' })).toBe('Drained fast');
    expect(contributionTitle({ factor: 'signal:RAPID_DRAIN', title: undefined as unknown as string })).toBe('Rapid drain');
    expect(contributionSummary({ summary: '' })).toBeNull();
    const old = { type: 'FAST_FORWARDING', title: undefined as unknown as string, summary: undefined as unknown as string,
      evidence: [{ kind: 'pattern', description: '92% forwarded within 4 minutes', reference: null, source: 'x', observedAt: '' }] };
    expect(signalTitle(old)).toBe('Fast forwarding');
    expect(signalSummary(old)).toBe('92% forwarded within 4 minutes');
  });
});

describe('signal type names in both languages (spec 2026-11-19)', () => {
  it('names a known type and returns null for an unknown one', () => {
    expect(signalTypeName('INDIRECT_EXPOSURE', 'fr')).toBe('Exposition indirecte à une adresse listée');
    expect(signalTypeName('INDIRECT_EXPOSURE', 'en')).toBe('Indirect exposure to a listed address');
    expect(signalTypeName('RAPID_DRAIN', 'fr')).toBe('Vidage rapide');
    expect(signalTypeName('SOMETHING_NEW', 'fr')).toBeNull();
  });
  it('names the categories of a listed address', () => {
    expect(categoryName('ISSUER_FROZEN', 'fr')).toBe("gelée par l'émetteur du stablecoin");
    expect(categoryName('SANCTIONED', 'fr')).toBe('sanctionnée');
    expect(categoryName('issuer-frozen', 'en')).toBe('frozen by the stablecoin issuer');
  });
});
