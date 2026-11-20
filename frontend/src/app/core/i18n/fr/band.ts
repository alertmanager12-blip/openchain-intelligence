import type { Entry } from '../i18n.service';
import type { band as enBand } from '../en/band';

export const band: Record<keyof typeof enBand, Entry> = {
  'band.title': 'Ma surveillance est-elle fiable ?',
  'band.yes': 'Oui',
  'band.partly': 'En partie',
  'band.no': 'Non',
  'band.fix': 'Corriger',
  'band.passed': '{count, plural, one {# vérification réussie} other {# vérifications réussies}}',
  'band.olderGaps': "{count, plural, one {Trou d'indexation plus ancien (#)} other {Trous d'indexation plus anciens (#)}}",
  'band.state.fail': 'En échec :',
  'band.state.warn': 'Avertissement :',
  'band.state.unknown': 'Inconnu :',
  'band.state.pass': 'Réussi :',
  'band.hidePassed': 'Masquer les vérifications réussies',
};
