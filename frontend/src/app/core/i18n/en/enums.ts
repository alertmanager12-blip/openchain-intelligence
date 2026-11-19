import type { Entry } from '../i18n.service';

/** Key prefixes this area owns (checked by dictionaries.spec.ts). */
export const prefixes = ['enum.'] as const;

export const enums = {
} as const satisfies Record<string, Entry>;
