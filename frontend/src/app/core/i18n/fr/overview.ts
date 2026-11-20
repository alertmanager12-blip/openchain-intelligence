import type { Entry } from '../i18n.service';
import type { overview as enOverview } from '../en/overview';

export const overview: Record<keyof typeof enOverview, Entry> = {
  'overview.title': "Vue d'ensemble",
  'overview.allOpen': "{count, plural, one {Voir l'alerte ouverte} other {Voir les # alertes ouvertes}}",
  'overview.lede': "D'abord : puis-je me fier à la surveillance en ce moment ? Ensuite, les alertes à traiter en premier. Un signal est une raison de regarder de plus près, pas une preuve.",
  'overview.updated': 'Mis à jour {when}',
  'overview.refresh': 'Actualiser',
  'overview.loading': "Chargement de la vue d'ensemble",
  'overview.handleNow': 'À traiter maintenant',
  'overview.handleNote': 'Les alertes ouvertes les plus graves, avec ce sur quoi elles reposent.',
  'overview.reviewOpen': 'Voir les alertes ouvertes',
  'overview.addWatchlist': 'Ajouter des portefeuilles à la liste de surveillance pour les suivre de près',
  'overview.last24h': '24 dernières heures',
  'overview.alertsUnit': '{count, plural, one {alerte} other {alertes}}',
  'overview.networks': 'Réseaux',
  'overview.net.unknown': 'retard inconnu',
  'overview.net.blocks': '{n, plural, one {# bloc de retard} other {# blocs de retard}}',
  'overview.net.lastBlock': 'Bloc {n}',
  'overview.openAlert': "Ouvrir l'alerte",
};
