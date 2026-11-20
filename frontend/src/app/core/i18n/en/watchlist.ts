import type { Entry } from '../i18n.service';

/** Key prefixes this area owns (checked by dictionaries.spec.ts). */
export const prefixes = ['watchlist.'] as const;

export const watchlist = {
  'watchlist.title': 'Watchlist',
  'watchlist.lede': 'Your own wallets: deposit addresses, treasury, hot wallets. Behavioural rules focus on them and on their direct counterparties.',
  'watchlist.scopeActive': 'Behavioural rules (fund-splitting, suspicious-wallet, suspicious-approval) only look at {n, plural, one {this wallet} other {these # wallets}} and their direct counterparties. Rules on sanctions, issuer freezes and large transfers still cover the whole chain.',
  'watchlist.scopeEmpty': 'The watchlist is empty: behavioural rules watch the whole chain, which is noisy on busy networks. Add your own wallets to focus them.',
  'watchlist.watched': 'Watched wallets',
  'watchlist.empty': 'No wallet watched yet.',
  'watchlist.wallet': 'Wallet',
  'watchlist.name': 'Name',
  'watchlist.added': 'Added',
  'watchlist.by': 'by {name}',
  'watchlist.actions': 'Actions',
  'watchlist.remove': 'Remove',
  'watchlist.stopWatching': 'Stop watching {address}',
  'watchlist.watchA': 'Watch a wallet',
  'watchlist.address': 'Address',
  'watchlist.network': 'Network',
  'watchlist.nameOptional': 'Name (optional)',
  'watchlist.namePlaceholder': 'Hot wallet, customer deposits…',
  'watchlist.watch': 'Watch',
  'watchlist.adding': 'Adding…',
  'watchlist.toast.added': 'Wallet watched',
  'watchlist.toast.removed': 'Wallet no longer watched',
  'watchlist.toast.removeFailed': 'Could not remove the wallet',
} as const satisfies Record<string, Entry>;
