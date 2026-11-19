import type { Entry } from './i18n.service';

/** English: the source of the keys (fr.ts must define every one). Keys are namespaced by area. */
export const en = {
  'overview.title': 'Overview',
  'overview.allOpen': 'See all {count} open alerts',
} as const satisfies Record<string, Entry>;
