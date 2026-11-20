import { fmtDate, fmtDuration, fmtNumber, fmtPercent, fmtRelative, plural } from './format';

/** Spec 2026-11-19 §1: numbers, dates, relative times, durations and plurals follow the language. */
describe('i18n formatting', () => {
  it('formats numbers per language', () => {
    expect(fmtNumber(1385, 'fr')).toBe('1 385');
    expect(fmtNumber(1385, 'en')).toBe('1,385');
    expect(fmtNumber(248.36, 'fr')).toBe('248,36');
    expect(fmtNumber(248.369, 'en', 1)).toBe('248.4');
  });

  it('formats a date per language', () => {
    expect(fmtDate('2026-11-19T10:00:00Z', 'fr')).toBe('19 nov. 2026');
    expect(fmtDate('2026-11-19T10:00:00Z', 'en')).toBe('19 Nov 2026');
  });

  it('says a relative time per language', () => {
    const now = Date.parse('2026-11-19T12:00:00Z');
    expect(fmtRelative('2026-11-19T10:00:00Z', 'fr', now)).toBe('il y a 2 heures');
    expect(fmtRelative('2026-11-19T10:00:00Z', 'en', now)).toBe('2 hours ago');
    expect(fmtRelative('2026-11-19T11:59:30Z', 'en', now)).toBe('now');
  });

  it('writes durations the same way in both languages', () => {
    expect(fmtDuration(4_800_000, 'fr')).toBe('1 h 20 min');
    expect(fmtDuration(4_800_000, 'en')).toBe('1 h 20 min');
    expect(fmtDuration(27 * 60_000, 'fr')).toBe('27 min');
    expect(fmtDuration(45_000, 'en')).toBe('45 s');
    expect(fmtDuration(3_600_000, 'en')).toBe('1 h');
    expect(fmtDuration(26 * 3_600_000, 'fr')).toBe('26 h');
  });

  it('picks plural forms by language rules (0 is singular in French)', () => {
    const f = { one: 'alerte', other: 'alertes' };
    expect(plural(0, 'fr', f)).toBe('alerte');
    expect(plural(1, 'fr', f)).toBe('alerte');
    expect(plural(2, 'fr', f)).toBe('alertes');
    expect(plural(0, 'en', { one: 'alert', other: 'alerts' })).toBe('alerts');
  });
});

describe('percent, stamps and fractions', () => {
  it('writes a ratio as a percentage with the narrow space in French', () => {
    expect(fmtPercent(0.7, 'en')).toBe('70%');
    expect(fmtPercent(0.7, 'fr')).toBe('70 %');
  });
  it('keeps a fixed number of fractions on request', () => {
    expect(fmtNumber(0.5, 'en', 2, 2)).toBe('0.50');
    expect(fmtNumber(0.5, 'fr', 2, 2)).toBe('0,50');
  });
});
