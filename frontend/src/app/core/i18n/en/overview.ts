import type { Entry } from '../i18n.service';

/** Key prefixes this area owns (checked by dictionaries.spec.ts). */
export const prefixes = ['overview.'] as const;

export const overview = {
  'overview.title': 'Overview',
  'overview.allOpen': '{count, plural, one {See the # open alert} other {See all # open alerts}}',
  'overview.lede': 'Whether monitoring can be trusted right now, then the alerts to handle first. A signal is a reason to look closer, not proof of wrongdoing.',
  'overview.updated': 'Updated {when}',
  'overview.refresh': 'Refresh',
  'overview.loading': 'Loading the overview',
  'overview.handleNow': 'Handle now',
  'overview.handleNote': 'The most severe open alerts, with what they rest on.',
  'overview.reviewOpen': 'Review the open alerts',
  'overview.addWatchlist': 'Add wallets to the watchlist to follow them closely',
  'overview.last24h': 'Last 24 hours',
  'overview.alertsUnit': '{count, plural, one {alert} other {alerts}}',
  'overview.networks': 'Networks',
  'overview.net.unknown': 'delay unknown',
  'overview.net.blocks': '{n, plural, one {# block behind} other {# blocks behind}}',
  'overview.openAlert': 'Open the alert',
} as const satisfies Record<string, Entry>;
