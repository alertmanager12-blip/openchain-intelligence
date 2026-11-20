import type { Entry } from '../i18n.service';

/** Key prefixes this area owns (checked by dictionaries.spec.ts). */
export const prefixes = ['dialog.', 'toast.', 'nne.', 'gauge.', 'time.', 'ui.'] as const;

export const shared = {
  'dialog.cancel': 'Cancel',
  'dialog.confirm': 'Confirm',
  'dialog.sample.title': 'Delete this item?',
  'dialog.sample.message': 'This cannot be undone.',
  'toast.notifications': 'Notifications',
  'toast.dismiss': 'Dismiss notification',
  'nne.title': '{network} is not indexed by this instance',
  'nne.only': 'This instance indexes only {list}.',
  'nne.none': 'This instance indexes no network yet.',
  'nne.unknown': 'Its operator has not enabled this network.',
  'nne.openOn': 'Open on {network}',
  'nne.search': 'Search another address',
  'gauge.label': 'Risk score',
  'gauge.of': '/ 100',
  'gauge.valueText': '{score} of 100, {level} risk',
  'time.secAgo': '{n}s ago',
  'time.minAgo': '{n}m ago',
  'time.hourAgo': '{n}h ago',
  'time.dayAgo': '{n}d ago',
  'time.min': '{n} min',
  'time.hour': '{n} h',
  'time.day': '{n} d',
  'ui.evidence.ANOMALY': 'Statistical anomaly: unusual behaviour, no risk claim.',
  'ui.evidence.RISK_SIGNAL': 'Risk signal: pattern associated with risk. Not proof of wrongdoing.',
  'ui.evidence.CONFIRMED_BY_SOURCE': 'Confirmed by a cited external source (e.g. official sanctions list).',
} as const satisfies Record<string, Entry>;
