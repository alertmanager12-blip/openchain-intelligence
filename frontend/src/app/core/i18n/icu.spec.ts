import { formatIcu, isWellFormedIcu } from './icu';

describe('formatIcu', () => {
  const P = '{n, plural, one {# alerte} other {# alertes}}';

  it('plural in French: 0 and 1 are singular', () => {
    expect(formatIcu(P, { n: 0 }, 'fr')).toBe('0 alerte');
    expect(formatIcu(P, { n: 1 }, 'fr')).toBe('1 alerte');
    expect(formatIcu(P, { n: 2 }, 'fr')).toBe('2 alertes');
  });

  it('formats # with the locale number format', () => {
    expect(formatIcu(P, { n: 1385 }, 'fr').replace(/\s/g, ' ')).toBe('1 385 alertes');
  });

  it('plural in English: 0 is other', () => {
    expect(formatIcu(P, { n: 0 }, 'en')).toBe('0 alertes');
  });

  it('exact =0 wins over the category', () => {
    expect(formatIcu('{n, plural, =0 {aucune} one {# alerte} other {# alertes}}', { n: 0 }, 'fr')).toBe('aucune');
  });

  it('select falls back to other', () => {
    const m = '{k, select, address {adresse} other {transaction}}';
    expect(formatIcu(m, { k: 'tx' }, 'fr')).toBe('transaction');
    expect(formatIcu(m, { k: 'address' }, 'fr')).toBe('adresse');
  });

  it('nests one level', () => {
    const m = '{n, plural, one {{who} a gelé # adresse} other {{who} a gelé # adresses}}';
    expect(formatIcu(m, { n: 2, who: 'Tether' }, 'fr')).toBe('Tether a gelé 2 adresses');
  });

  it('replaces simple params and leaves unknown ones', () => {
    expect(formatIcu('Bonjour {name}', { name: 'Ada' }, 'fr')).toBe('Bonjour Ada');
    expect(formatIcu('Bonjour {name}', {}, 'fr')).toBe('Bonjour {name}');
    expect(formatIcu('{n, plural, one {x} other {y}}', {}, 'fr')).toBe('{n, plural, one {x} other {y}}');
  });

  it('formats number params in simple placeholders', () => {
    expect(formatIcu('{n} items', { n: 1234.5 }, 'en')).toBe('1,234.5 items');
  });

  it('never throws on malformed input', () => {
    expect(formatIcu('{n, plural, one {x', { n: 1 }, 'fr')).toBe('{n, plural, one {x');
    expect(formatIcu('a } b', {}, 'fr')).toBe('a } b');
  });

  it('isWellFormedIcu tells valid from malformed messages', () => {
    expect(isWellFormedIcu('{a')).toBe(false);
    expect(isWellFormedIcu('a } b')).toBe(false);
    expect(isWellFormedIcu('{n, plural, one {#} other {#}}')).toBe(true);
    expect(isWellFormedIcu('plain {name}')).toBe(true);
  });
});
