import { Alert } from '../core/models';
import { fmtNumber } from '../core/i18n/format';
import { alertSentence } from './alert-sentence';
import { ALERT_FIELDS } from './testing/alert-fields.fixture';

type A = Pick<Alert, 'ruleId' | 'ruleName' | 'summary' | 'evidenceSummary' | 'matchedEvent' | 'occurrenceCount'>;
const ENGINE = 'Engine wording, as stored.';
function alert(ruleId: string, fields: Record<string, unknown>, extra: Partial<A> = {}): A {
  return { ruleId, ruleName: ruleId, summary: 'Rule summary', evidenceSummary: ENGINE, matchedEvent: fields, occurrenceCount: 1, ...extra };
}
const real = (rule: string, i = 0) => ALERT_FIELDS[rule][i].fields;
const amount = (f: Record<string, unknown>, lang: 'en' | 'fr') => fmtNumber(Number(f['transaction.amount']), lang);

/** Spec 2026-11-19 §5: one sentence per shipped rule from the alert's own fields, same facts in both languages. */
describe('alertSentence', () => {
  it('wallet-draining: amount sent against the balance, to the counterparty', () => {
    const f = real('wallet-draining');
    const fr = alertSentence(alert('wallet-draining', f), 'fr');
    const en = alertSentence(alert('wallet-draining', f), 'en');
    expect(fr).toContain('Portefeuille vidé');
    expect(fr).toContain(`${amount(f, 'fr')} ETH`);
    expect(fr).toContain(`solde de ${fmtNumber(Number(f['wallet.balance']), 'fr')} ETH`);
    expect(en).toContain('Wallet drained');
    expect(en).toContain(`${amount(f, 'en')} ETH`);
  });

  it('large-usdt-transfer: amount, sender and recipient', () => {
    const f = real('large-usdt-transfer');
    expect(alertSentence(alert('large-usdt-transfer', f), 'fr')).toMatch(/^Gros transfert : .+ USDT de T\w+…\w+ vers T\w+…\w+$/);
    expect(alertSentence(alert('large-usdt-transfer', f), 'en')).toMatch(/^Large transfer: .+ USDT from T\w+…\w+ to T\w+…\w+$/);
  });

  it('fund-splitting: says what the rule asserts, not a recipient count it does not carry', () => {
    const f = real('fund-splitting');
    // no threshold figure: an operator may edit the rule, and the alert does not carry the threshold it fired on
    const fr = alertSentence(alert('fund-splitting', f), 'fr'), en = alertSentence(alert('fund-splitting', f), 'en');
    expect(fr).toContain('de nombreux destinataires distincts en peu de temps');
    expect(en).toContain('many distinct recipients within a short time');
    expect(fr + en).not.toMatch(/30|3 minutes/);
  });

  it('issuer-freeze-counterparty: who froze what, and how many indexed wallets dealt with it', () => {
    const f = real('issuer-freeze-counterparty');
    expect(alertSentence(alert('issuer-freeze-counterparty', f), 'fr')).toMatch(/a gelé 0x\w+…\w+ \(USD[TC]\)/);
    expect(alertSentence(alert('issuer-freeze-counterparty', f), 'en')).toMatch(/froze 0x\w+…\w+ \(USD[TC]\)/);
  });

  it('listed-address-exposure: names the list the source gives, never a sanction', () => {
    const f = real('listed-address-exposure');
    const fr = alertSentence(alert('listed-address-exposure', f), 'fr');
    expect(fr).toContain("gelée par l'émetteur du stablecoin");
    expect(fr).not.toMatch(/sanction/i);
    expect(alertSentence(alert('listed-address-exposure', f), 'en')).toContain('frozen by the stablecoin issuer');
  });

  it('sanctioned-exposure-signal: says sanctioned only when the signal says so', () => {
    const f = { ...real('sanctioned-exposure-signal'), 'signal.listed_category': 'sanctioned' };
    expect(alertSentence(alert('sanctioned-exposure-signal', f), 'fr')).toContain('sanctionnée');
    // the alerts of 18 Nov carry no category (they were Tether freezes): the engine's own wording, never "sanctioned"
    expect(alertSentence(alert('sanctioned-exposure-signal', real('sanctioned-exposure-signal')), 'fr')).toBe(ENGINE);
  });

  it('falls back to the engine wording when a field it needs is missing', () => {
    const { ['transaction.amount']: _, ...f } = real('wallet-draining');
    expect(alertSentence(alert('wallet-draining', f), 'fr')).toBe(ENGINE);
  });

  it('keeps the rule summary for a rule it does not know', () => {
    expect(alertSentence(alert('my-custom-rule', {}, { evidenceSummary: null }), 'fr')).toBe('Rule summary');
  });
});
