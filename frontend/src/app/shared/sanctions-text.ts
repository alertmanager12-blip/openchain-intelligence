import { SanctionsList } from '../core/models';
import type { Lang } from '../core/i18n/i18n.service';
import { fmtDate, fmtNumber } from '../core/i18n/format';

const day = (iso: string) =>
  new Date(iso.length === 10 ? iso + 'T00:00:00Z' : iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });

/**
 * What sanctions screening rests on (ADR-0027), for the coverage of a verdict and for Settings. A missing or stale list
 * is said plainly: it must never read as "no sanction found".
 */
export function sanctionsSentence(l: SanctionsList | undefined, lang: Lang = 'en'): string {
  if (lang === 'fr') return sanctionsFr(l);
  if (!l) return 'Sanctions screening status unknown.';
  if (l.state === 'NONE' || !l.issued) return 'No official sanctions list is loaded: a sanctioned address would not be recognised.';
  const what = `${l.source} of ${day(l.issued)}`;
  if (l.state === 'STALE') {
    return `Sanctions screening: ${what}, not confirmed since ${l.checkedAt ? day(l.checkedAt) : 'an unknown date'}: newer listings may be missing.`;
  }
  return `Sanctions screening: ${what} (${l.tron.toLocaleString('en-US')} TRON and ${l.ethereum.toLocaleString('en-US')} Ethereum addresses).`;
}

function sanctionsFr(l: SanctionsList | undefined): string {
  if (!l) return 'État de la liste de sanctions inconnu.';
  if (l.state === 'NONE' || !l.issued) return "Aucune liste officielle de sanctions n'est chargée : une adresse sanctionnée ne serait pas reconnue.";
  const what = `${l.source} du ${fmtDate(l.issued, 'fr')}`;
  if (l.state === 'STALE') {
    return `Liste de sanctions : ${what}, non confirmée depuis ${l.checkedAt ? 'le ' + fmtDate(l.checkedAt, 'fr') : 'une date inconnue'} : des inscriptions plus récentes peuvent manquer.`;
  }
  return `Liste de sanctions : ${what} (${fmtNumber(l.tron, 'fr')} adresses TRON et ${fmtNumber(l.ethereum, 'fr')} adresses Ethereum).`;
}
