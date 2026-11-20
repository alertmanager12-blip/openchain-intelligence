import { Alert } from '../core/models';
import type { Lang } from '../core/i18n/i18n.service';
import { fmtNumber, plural } from '../core/i18n/format';
import { categoryName } from './explain';
import { shortHash } from './format';

type AlertLike = Pick<Alert, 'ruleId' | 'ruleName' | 'summary' | 'evidenceSummary' | 'matchedEvent' | 'occurrenceCount'>;
type Fields = Record<string, unknown>;

const str = (f: Fields, k: string): string | null => (typeof f[k] === 'string' && f[k] !== '' ? (f[k] as string) : null);
const num = (f: Fields, k: string): number | null => {
  const v = f[k];
  const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : NaN;
  return Number.isFinite(n) ? n : null;
};
const amt = (n: number, lang: Lang) => fmtNumber(n, lang, Math.abs(n) < 1 ? 4 : 2);
const addr = (a: string) => shortHash(a, 6, 4);

type Builder = (f: Fields, lang: Lang) => string | null;

/** Signal-based rules: only the facts the signal carries (type and the list the source gives), nothing more. */
const exposure: Builder = (f, lang) => {
  const type = str(f, 'signal.type'), cat = str(f, 'signal.listed_category');
  if (!type || !cat) return null;
  // categoryName reads after "listed as" ("a mixer", "sanctioned", "frozen by the stablecoin issuer")
  const what = categoryName(cat, lang);
  if (type === 'INDIRECT_EXPOSURE') {
    return lang === 'fr' ? `Fonds reçus indirectement d'une adresse listée comme ${what}` : `Funds received indirectly from an address listed as ${what}`;
  }
  return lang === 'fr' ? `Transfert direct avec une adresse listée comme ${what}` : `Direct transfer with an address listed as ${what}`;
};

const BUILDERS: Record<string, Builder> = {
  'wallet-draining': (f, lang) => {
    const a = num(f, 'transaction.amount'), b = num(f, 'wallet.balance'), t = str(f, 'transaction.token.symbol'), to = str(f, 'counterparty.address');
    if (a === null || b === null || !t || !to) return null;
    return lang === 'fr'
      ? `Portefeuille vidé : ${amt(a, lang)} ${t} sortis sur un solde de ${amt(b, lang)} ${t}, vers ${addr(to)}`
      : `Wallet drained: ${amt(a, lang)} ${t} sent out of a balance of ${amt(b, lang)} ${t}, to ${addr(to)}`;
  },
  'large-usdt-transfer': (f, lang) => {
    const a = num(f, 'transaction.amount'), t = str(f, 'transaction.token.symbol'), from = str(f, 'transaction.from'), to = str(f, 'transaction.to');
    if (a === null || !t || !from || !to) return null;
    return lang === 'fr' ? `Gros transfert : ${amt(a, lang)} ${t} de ${addr(from)} vers ${addr(to)}`
      : `Large transfer: ${amt(a, lang)} ${t} from ${addr(from)} to ${addr(to)}`;
  },
  'fund-splitting': (f, lang) => {
    const from = str(f, 'transaction.from'), a = num(f, 'transaction.amount'), t = str(f, 'transaction.token.symbol'), to = str(f, 'transaction.to');
    if (!from || a === null || !t || !to) return null;
    return lang === 'fr'
      ? `Fractionnement : ${addr(from)} a envoyé des fonds à de nombreux destinataires distincts en peu de temps (dernier : ${amt(a, lang)} ${t} vers ${addr(to)})`
      : `Fund splitting: ${addr(from)} sent funds to many distinct recipients within a short time (last: ${amt(a, lang)} ${t} to ${addr(to)})`;
  },
  'issuer-freeze-counterparty': (f, lang) => {
    const who = str(f, 'freeze.address'), tok = str(f, 'freeze.token'), issuer = str(f, 'freeze.issuer'), n = num(f, 'freeze.known_counterparties');
    if (!who || !tok || !issuer || n === null) return null;
    const name = issuer.charAt(0).toUpperCase() + issuer.slice(1);
    return lang === 'fr'
      ? `${name} a gelé ${addr(who)} (${tok}), déjà en relation avec ${fmtNumber(n, lang)} ${plural(n, lang, { one: 'portefeuille indexé', other: 'portefeuilles indexés' })}`
      : `${name} froze ${addr(who)} (${tok}), which had exchanged value with ${fmtNumber(n, lang)} ${plural(n, lang, { one: 'indexed wallet', other: 'indexed wallets' })}`;
  },
  'sanctioned-exposure-signal': exposure,
  'listed-address-exposure': exposure,
};

/**
 * The sentence of an alert in the console's language (spec 2026-11-19 §5, option C), built only from the alert's matched-event
 * fields. A rule this console has no builder for, or a missing field, gives the engine's own wording (English) rather than a
 * guess. Rules absent from production on 2026-11-19 (suspicious-wallet, contract-exploit, dormant-wallet-reactivation,
 * suspicious-approval) have no builder yet: their fields were never observed.
 */
export function alertSentence(a: AlertLike, lang: Lang): string {
  const b = BUILDERS[a.ruleId];
  const s = b ? b(a.matchedEvent ?? {}, lang) : null;
  return s ?? a.evidenceSummary ?? a.summary;
}
