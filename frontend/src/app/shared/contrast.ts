/**
 * Colour tokens of the console (specs 2026-11-18 and 2026-11-20) and the WCAG contrast ratio that keeps them readable. The values
 * mirror `src/styles.scss`; a token changed there must change here, and the contrast test then checks it in both themes.
 */
export const TOKENS: { light: Record<string, string>; dark: Record<string, string> } = {
  light: {
    canvas: '#F4F6F8', band: '#EDF1F4', line: '#DFE5EA', ink: '#0B1F3A', 'ink-2': '#4F627A', brand: '#0A7381',
    'lvl-observed': '#4F627A', 'lvl-inferred': '#8A5D00', 'lvl-inferred-rule': '#B7791F', 'lvl-confirmed': '#1E7457',
    'lvl-predicted': '#6B3FA0', 'lvl-simulated': '#4F627A',
  },
  dark: {
    canvas: '#090F1A', band: '#151F33', line: '#22304A', ink: '#E8EEF6', 'ink-2': '#A7B6C9', brand: '#46CFDB',
    'lvl-observed': '#A7B6C9', 'lvl-inferred': '#E0B04A', 'lvl-inferred-rule': '#C99A3A', 'lvl-confirmed': '#5BC79A',
    'lvl-predicted': '#B9A0F2', 'lvl-simulated': '#A7B6C9',
  },
};

function luminance(hex: string): number {
  const h = hex.replace('#', '');
  const c = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

/** WCAG 2.x contrast ratio between two #RRGGBB colours, from 1 to 21. */
export function contrastRatio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}
