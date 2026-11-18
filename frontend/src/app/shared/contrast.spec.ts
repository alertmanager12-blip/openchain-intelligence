import { TOKENS, contrastRatio } from './contrast';

/** Spec 2026-11-18 (console redesign): every text token readable on the page and on panels, WCAG AA, both themes. */
describe('design tokens contrast', () => {
  it('computes the WCAG contrast ratio', () => {
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 0);
    expect(contrastRatio('#FFFFFF', '#FFFFFF')).toBeCloseTo(1, 5);
  });

  const TEXT = ['ink', 'ink-2', 'brand', 'lvl-observed', 'lvl-inferred', 'lvl-confirmed', 'lvl-predicted', 'lvl-simulated'];
  for (const theme of ['light', 'dark'] as const) {
    for (const bg of ['canvas', 'band']) {
      for (const fg of TEXT) {
        it(`${theme}: ${fg} on ${bg} is at least 4.5:1`, () => {
          expect(contrastRatio(TOKENS[theme][fg], TOKENS[theme][bg])).toBeGreaterThanOrEqual(4.5);
        });
      }
    }
  }

  it('keeps the light values of the spec', () => {
    expect(TOKENS.light).toMatchObject({ canvas: '#FFFFFF', band: '#F2F6F7', line: '#DCE5EA', ink: '#0B2545', 'ink-2': '#4D6177',
      brand: '#0A7381', 'lvl-confirmed': '#1E7457', 'lvl-inferred': '#8A5D00', 'lvl-inferred-rule': '#B7791F' });
  });
});
