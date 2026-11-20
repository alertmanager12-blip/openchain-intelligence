import type { Alert, RuleTranslation } from '../core/models';
import type { Lang } from '../core/i18n/i18n.service';

type Source = {
  name: string;
  description?: string | null;
  documentation?: string | null;
  translations?: Record<string, Partial<RuleTranslation>>;
};

const pick = (translated: string | undefined, own: string | null | undefined): string | null => (translated?.trim() ? translated : (own ?? null));

/**
 * A rule's name, description and documentation in the console's language. Each text falls back on its own to the rule's
 * English text, so a rule translated in part (a name only) never shows an empty description.
 */
export function ruleText(r: Source, lang: Lang): { name: string; description: string | null; documentation: string | null } {
  const t = r.translations?.[lang];
  return {
    name: pick(t?.name, r.name) ?? r.name,
    description: pick(t?.description, r.description),
    documentation: pick(t?.documentation, r.documentation),
  };
}

/** Name of the rule behind an alert, from the translations the alert carries. */
export function ruleName(a: Pick<Alert, 'ruleName' | 'ruleTranslations'>, lang: Lang): string {
  return ruleText({ name: a.ruleName, translations: a.ruleTranslations }, lang).name;
}
