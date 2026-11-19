import type { Entry } from '../i18n.service';

/** Key prefixes this area owns (checked by dictionaries.spec.ts). */
export const prefixes = ['band.'] as const;

export const band = {
  'band.title': 'Is monitoring reliable?',
  'band.yes': 'Yes',
  'band.partly': 'Partly',
  'band.no': 'No',
  'band.fix': 'Fix',
  'band.passed': '{count, plural, one {# check passed} other {# checks passed}}',
  'band.olderGaps': '{count, plural, one {Older indexing gap (#)} other {Older indexing gaps (#)}}',
  'band.state.fail': 'Failing:',
  'band.state.warn': 'Warning:',
  'band.state.unknown': 'Unknown:',
  'band.state.pass': 'Passed:',
  'band.hidePassed': 'Hide the passed checks',
} as const satisfies Record<string, Entry>;
