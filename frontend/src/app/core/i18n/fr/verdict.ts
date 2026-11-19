import type { Entry } from '../i18n.service';
import type { verdict as enVerdict } from '../en/verdict';

export const verdict: Record<keyof typeof enVerdict, Entry> = {
  'verdict.coverageUnknown': "Couverture inconnue : les données sur lesquelles ce verdict repose n'ont pas pu être précisées.",
  'evidence.cite': 'Voir les preuves de {id}',
  'evidence.original': 'Formulation du moteur',
};
