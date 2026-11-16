# Étape 2, point 1 : métriques des adresses exemptées — rapport technique

Date : 2026-11-11. Branche `wip/adr-0024-step-3a`, modifications **non commitées**, **non déployées**. Origine :
`docs/EXEMPT_ADDRESSES_ANALYSIS.md` § 7 (R-E5).

## Ce qui ne change pas

Aucune modification de la politique d'exemption, de la purge ni des plafonds ; ni les plafonds proposés (10 000 par
adresse, 150 000 au total) ni l'expiration de 7 jours ne sont appliqués. Le relevé lit les stocks sans les modifier ; le
test d'intégration vérifie que le nombre de flux et de signaux en mémoire est identique avant et après un relevé.
Seul ajout de comportement : l'enregistrement, en mémoire, de l'heure de consultation d'une adresse (dans
`AddressHistoryService.ensure`, qui était déjà appelé à chaque consultation), sans effet sur l'historique.

## Changements

| Fichier | Changement |
|---|---|
| `oci-app/.../chain/ExemptWorkingSetMonitor.java` (nouveau) | relevé par adresse et par catégorie : flux et signaux en mémoire, profil présent, estimation mémoire, dernière consultation, dépassements des plafonds proposés (information) |
| `oci-app/.../api/ExemptWorkingSetController.java` (nouveau) | `GET /api/v1/admin/working-set/exempt` : détail par adresse, relevé frais à chaque requête ; permission `keys:admin` (règle existante `/api/v1/admin/**`) |
| `oci-app/.../history/AddressHistoryService.java` | heure de dernière consultation (en mémoire, depuis le démarrage) ; lecture des lignes d'historique (`progressRows`) |
| `oci-app/.../config/Bootstrap.java` | relevé après chaque purge et ligne de synthèse dans le journal |
| `oci-app/.../config/MetricsConfiguration.java` | jauges `oci_exempt_*` par catégorie |
| `oci-app/.../config/ModulesConfiguration.java` | bean du moniteur (liste de surveillance optionnelle) |
| `docs/guides/installation.md`, `CHANGELOG.md` | documentation |

## Ce qui est mesuré (périmètre demandé)

| Demande | Réponse |
|---|---|
| Adresses exemptées par motif | `oci_exempt_addresses{category}` ; catégories `watchlist`, `history_loaded` (terminé avec événements), `history_partial` (tronqué avec événements), `history_in_progress` (en attente ou en cours), `history_empty` (terminé ou tronqué sans événement), `history_failed` |
| Flux et signaux par adresse | endpoint : `flowsInMemory`, `signalsInMemory`, `profileInMemory`, par adresse ; jauges `oci_exempt_flows{category}`, `oci_exempt_signals{category}`, `oci_exempt_max_flows_per_address{category}`, `oci_exempt_flows_distinct` |
| Mémoire par adresse et par catégorie | `estimatedBytes` par adresse, `oci_exempt_memory_estimate_bytes{category}` : flux × 703 o + signaux × 1 057 o (coûts mesurés en laboratoire, `FlowFootprintProbe`) ; **estimation**, pas une mesure du heap |
| Ancienneté de la dernière consultation | `lastViewedAt`, `viewAgeSeconds` par adresse (consultations depuis le démarrage) ; à défaut, `recordAgeSeconds` (âge de la ligne d'historique) ; `oci_exempt_oldest_view_age_seconds{category}`, `oci_exempt_not_viewed_since_start{category}` |
| Chargé, partiel, en cours, vide, en échec | catégories ci-dessus ; `historyStatus` et `historyEvents` par adresse |
| Consommation de la liste de surveillance | catégorie `watchlist` (une adresse surveillée qui a aussi un historique est classée `watchlist`, avec `alsoHistory = true`) |
| Dépassements des plafonds proposés (information) | `overProposedPerAddressCap` par adresse ; `oci_exempt_proposed_cap_addresses_over`, `oci_exempt_proposed_cap_flows_over` (flux exemptés hors liste de surveillance au-delà de 150 000) |

Choix de conception : **aucune adresse en libellé Prometheus** (les adresses consultées par les analystes sont une
information sensible, et chaque adresse créerait une série) ; le détail par adresse passe par l'endpoint
d'administration. Un flux entre deux adresses exemptées est compté pour chacune dans les totaux par catégorie, et une
seule fois dans `oci_exempt_flows_distinct`.

## Tests exécutés

| Test | Vérifie |
|---|---|
| `ExemptWorkingSetMonitorTest.historyCategoryDistinguishesLoadedPartialInProgressEmptyAndFailed` | la classification des 5 états d'historique |
| `ExemptWorkingSetMonitorTest.categoriesFlowsSignalsAndProposedCapsAreMeasured` (application complète) | catégories ; adresse surveillée qui est aussi un historique en échec ; adresse à 10 001 flux signalée au-delà du plafond proposé, estimation mémoire ; flux partagé compté une fois en distinct ; **aucun flux ni signal modifié par le relevé** ; consultation enregistrée ; endpoint : 401 sans clé, 403 avec une clé de lecture, 200 avec une clé d'administration |
| `mvn -B verify` | **BUILD SUCCESS, 554 tests, 0 échec, 0 ignoré** |

## Limites

- La dernière consultation n'est connue que depuis le démarrage (en mémoire, non persistée) ; avant, seul l'âge de la
  ligne d'historique est disponible. La persister demanderait une migration, hors de ce périmètre.
- La mémoire est une **estimation** à partir de coûts de laboratoire ; l'histogramme de la mesure de 12 heures servira de
  contrôle.
- Le coût du relevé (parcours des flux de chaque adresse exemptée, toutes les 10 minutes) est affiché dans la ligne de
  journal ; il sera mesuré pendant la mesure de 12 heures.
- Ces métriques ne produisent des données de production qu'une fois déployées : prévues dans la build de la mesure de
  12 heures, avec ton autorisation.
