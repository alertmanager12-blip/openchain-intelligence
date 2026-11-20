import { Alert } from '../core/models';
import { ruleName, ruleText } from './rule-text';

const rule = { name: 'Wallet drained (>80% of balance out in 2 minutes)', description: 'English description', documentation: 'English doc' };

describe('ruleText', () => {
  it('takes the French name and falls back to the English description when only fr.name exists', () => {
    const t = ruleText({ ...rule, translations: { fr: { name: 'Portefeuille vidé (plus de 80 % du solde sorti en 2 minutes)' } } }, 'fr');
    expect(t.name).toContain('Portefeuille vidé');
    expect(t.description).toBe('English description');
    expect(t.documentation).toBe('English doc');
  });

  it('uses the rule\'s own text in English and without translations', () => {
    expect(ruleText({ ...rule, translations: { fr: { name: 'x' } } }, 'en').name).toBe(rule.name);
    expect(ruleText(rule, 'fr')).toEqual({ name: rule.name, description: 'English description', documentation: 'English doc' });
  });

  it('gives null for absent description and documentation, and ignores blank translations', () => {
    expect(ruleText({ name: 'N', translations: { fr: { name: '  ', description: '' } } }, 'fr')).toEqual({ name: 'N', description: null, documentation: null });
  });
});

describe('ruleName', () => {
  const alert = (extra: Partial<Alert>) => ({ ruleName: 'Wallet drained', ...extra }) as Alert;
  it('reads the translations the alert carries', () => {
    expect(ruleName(alert({ ruleTranslations: { fr: { name: 'Portefeuille vidé' } } }), 'fr')).toBe('Portefeuille vidé');
    expect(ruleName(alert({ ruleTranslations: { fr: { name: 'Portefeuille vidé' } } }), 'en')).toBe('Wallet drained');
    expect(ruleName(alert({}), 'fr')).toBe('Wallet drained');
  });
});
