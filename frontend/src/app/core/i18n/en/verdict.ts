import type { Entry } from '../i18n.service';

/** Key prefixes this area owns (checked by dictionaries.spec.ts). */
export const prefixes = ['verdict.', 'evidence.'] as const;

export const verdict = {
  'verdict.coverageUnknown': 'Coverage unknown: the data this rests on could not be stated.',
  'evidence.cite': 'Show the evidence of {id}',
  'evidence.original': "Engine's wording",
} as const satisfies Record<string, Entry>;
