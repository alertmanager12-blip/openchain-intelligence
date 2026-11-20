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

  it('keeps the light values of the spec (2026-11-20: tinted canvas, white panels)', () => {
    expect(TOKENS.light).toMatchObject({ canvas: '#F4F6F8', band: '#EDF1F4', line: '#DFE5EA', ink: '#0B1F3A', 'ink-2': '#4F627A',
      brand: '#0A7381', 'lvl-confirmed': '#1E7457', 'lvl-inferred': '#8A5D00', 'lvl-inferred-rule': '#B7791F' });
  });

  it('keeps white panels readable: ink-2 on a white surface in light, on the panel tone in dark', () => {
    expect(contrastRatio(TOKENS.light['ink-2'], '#FFFFFF')).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(TOKENS.dark['ink-2'], '#0F1829')).toBeGreaterThanOrEqual(4.5);
  });
});
