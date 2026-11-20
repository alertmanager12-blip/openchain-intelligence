import type { Entry } from '../i18n.service';
import type { watchlist as en } from '../en/watchlist';

export const watchlist: Record<keyof typeof en, Entry> = {
  'watchlist.title': 'Liste de surveillance',
  'watchlist.lede': 'Vos propres portefeuilles : adresses de dépôt, trésorerie, portefeuilles chauds. Les règles comportementales se concentrent sur eux et sur leurs contreparties directes.',
  'watchlist.scopeActive': 'Les règles comportementales (fractionnement de fonds, portefeuille suspect, approbation suspecte) ne regardent que {n, plural, one {ce portefeuille} other {ces # portefeuilles}} et leurs contreparties directes. Les règles sur les sanctions, les gels d’émetteur et les gros transferts couvrent toujours toute la chaîne.',
  'watchlist.scopeEmpty': 'La liste de surveillance est vide : les règles comportementales surveillent toute la chaîne, ce qui est bruyant sur les réseaux actifs. Ajoutez vos propres portefeuilles pour les concentrer.',
  'watchlist.watched': 'Portefeuilles surveillés',
  'watchlist.empty': 'Aucun portefeuille surveillé pour l’instant.',
  'watchlist.wallet': 'Portefeuille',
  'watchlist.name': 'Nom',
  'watchlist.added': 'Ajouté',
  'watchlist.by': 'par {name}',
  'watchlist.actions': 'Actions',
  'watchlist.remove': 'Retirer',
  'watchlist.stopWatching': 'Ne plus surveiller {address}',
  'watchlist.watchA': 'Surveiller un portefeuille',
  'watchlist.address': 'Adresse',
  'watchlist.network': 'Réseau',
  'watchlist.nameOptional': 'Nom (facultatif)',
  'watchlist.namePlaceholder': 'Portefeuille chaud, dépôts clients…',
  'watchlist.watch': 'Surveiller',
  'watchlist.adding': 'Ajout…',
  'watchlist.toast.added': 'Portefeuille surveillé',
  'watchlist.toast.removed': 'Portefeuille retiré de la surveillance',
  'watchlist.toast.removeFailed': 'Impossible de retirer le portefeuille',
};
