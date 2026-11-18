import { SanctionsList } from '../core/models';

const day = (iso: string) =>
  new Date(iso.length === 10 ? iso + 'T00:00:00Z' : iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });

/**
 * What sanctions screening rests on (ADR-0027), for the coverage of a verdict and for Settings. A missing or stale list
 * is said plainly: it must never read as "no sanction found".
 */
export function sanctionsSentence(l: SanctionsList | undefined): string {
  if (!l) return 'Sanctions screening status unknown.';
  if (l.state === 'NONE' || !l.issued) return 'No official sanctions list is loaded: a sanctioned address would not be recognised.';
  const what = `${l.source} of ${day(l.issued)}`;
  if (l.state === 'STALE') {
    return `Sanctions screening: ${what}, not confirmed since ${l.checkedAt ? day(l.checkedAt) : 'an unknown date'}: newer listings may be missing.`;
  }
  return `Sanctions screening: ${what} (${l.tron.toLocaleString('en-US')} TRON and ${l.ethereum.toLocaleString('en-US')} Ethereum addresses).`;
}
