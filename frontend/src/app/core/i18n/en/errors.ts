import type { Entry } from '../i18n.service';

/** Key prefixes this area owns (checked by dictionaries.spec.ts). */
export const prefixes = ['error.'] as const;

export const errors = {
} as const satisfies Record<string, Entry>;
