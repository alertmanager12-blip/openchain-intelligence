import { en } from './en';
import { fr } from './fr';
import { translate } from './i18n.service';
import { alertSentence } from '../../shared/alert-sentence';

const FR = fr as Record<string, unknown>;
const EN = en as Record<string, unknown>;
const pairs = () => Object.keys(en).filter((k) => typeof FR[k] === 'string' && typeof EN[k] === 'string').map((k) => ({ k, f: FR[k] as string, e: EN[k] as string }));

/** Final review: one French word per concept, so a translated text never says more (or something else) than the English. */
describe('French terminology', () => {
  it('keeps « fractionnement » for structuring only', () => {
    const wrong = pairs().filter(({ f, e }) => /fractionn/i.test(f) && !/structuring/i.test(e)).map(({ k }) => k);
    expect(wrong).toEqual([]);
  });

  it('calls fund-splitting « répartition de fonds »', () => {
    expect(translate('fr', 'watchlist.scopeActive', { n: 2 })).toContain('(répartition de fonds, portefeuille suspect, approbation suspecte)');
    const sentence = alertSentence({ ruleId: 'fund-splitting', ruleName: 'fund-splitting', summary: 's', evidenceSummary: 'e', occurrenceCount: 1, matchedEvent: {
      'transaction.from': '0xaaaa000000000000000000000000000000000001', 'transaction.to': '0xbbbb000000000000000000000000000000000002',
      'transaction.amount': '1000', 'transaction.token.symbol': 'USDT' } } as never, 'fr');
    expect(sentence).toMatch(/^Répartition de fonds : /);
  });

  it('names the spender « bénéficiaire de l’autorisation », never « dépensier »', () => {
    expect(pairs().filter(({ f }) => /dépensier/i.test(f)).map(({ k }) => k)).toEqual([]);
    expect(translate('fr', 'rules.field.approval.spender')).toBe('Bénéficiaire de l’autorisation');
    expect(translate('fr', 'rules.field.approval.spender_is_contract')).toBe('Le bénéficiaire de l’autorisation est un contrat');
    expect(translate('fr', 'rules.field.approval.spender_age_days')).toBe('Âge du bénéficiaire de l’autorisation en jours');
  });

  it('says « preuve » only where the English says proof (evidence reads « éléments de preuve »)', () => {
    // « niveau de preuve » is the French term of art for "evidence level" (the spec's "levels of proof")
    const bare = (f: string) => /(?<!\p{L})preuves?(?!\p{L})/iu.test(f.replace(/[ée]léments? de preuve|niveaux? de preuve/gi, ''));
    const wrong = pairs().filter(({ f, e }) => bare(f) && !/proof/i.test(e)).map(({ k, f }) => `${k}: ${f}`);
    expect(wrong).toEqual([]);
    expect(translate('fr', 'landing.value.explain.d')).not.toMatch(/porte ses preuves/);
  });

  it('writes the window summary without an agreement to make', () => {
    const p = { what: 'Somme de transaction.amount', per: ' par transaction.from', duration: '5m', value: '1000' };
    expect(translate('fr', 'rules.win.summary', { ...p, cmp: translate('fr', 'rules.win.cmp.gt') })).toBe('Somme de transaction.amount par transaction.from sur 5m dépasse 1000.');
    expect(translate('fr', 'rules.win.summary', { ...p, cmp: translate('fr', 'rules.win.cmp.gte') })).toBe('Somme de transaction.amount par transaction.from sur 5m atteint au moins 1000.');
    expect(translate('en', 'rules.win.summary', { ...p, cmp: translate('en', 'rules.win.cmp.gt') })).toBe('Somme de transaction.amount par transaction.from over 5m is greater than 1000.');
  });
});
