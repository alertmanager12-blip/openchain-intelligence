# P0, item 2 : réorganisations blockchain — rapport avant / après

Date : 2026-11-10. Conception validée : `docs/P0_REORG_DESIGN.md`. Branche `wip/adr-0024-step-3a`, modifications
**non commitées**, **non déployées** (la production tourne toujours avec l'image de la mesure 3b, que ce travail ne
touche pas).

## Résumé

Une réorganisation annule désormais un flux et tout ce qui en dérive : profils (exactement), signaux (réévalués à
partir des preuves restantes, jamais supprimés sans recalcul), scores, contributions aux règles, alertes (qualifiées,
jamais supprimées) et notifications (`alert.retracted`, une fois, par l'outbox). Le traitement est idempotent et une
transaction réincluse dans un autre bloc est comptée une seule fois.

## Avant / après

| Critère | Avant | Après |
|---|---|---|
| Scénarios R01 conformes (banc) | 2 / 5 (dont 2 par hasard) | **5 / 5** |
| Signaux fondés sur des flux retirés (banc) | 4 | **0** |
| Signal encore justifié après retrait (R01b) | conservé par absence de traitement | **recalculé** : même identifiant, preuves mises à jour |
| Réinclusion (R01e) | non gérée (l'identifiant restait connu) | signal restauré une fois, profil compté une fois |
| 12 scénarios historiques | précision 1,00 (0,93 sans R1), rappel 1,00 des détecteurs existants, 59 signaux sur S01 | **identiques** (lignes par scénario et 59 signaux de même composition) |
| Alertes dont la preuve disparaît | un commentaire | état `PARTIALLY_WITHDRAWN` / `WITHDRAWN`, historique, bandeau console, `alert.retracted` |
| Métriques de réorganisation | `oci_reorg_total`, `oci_events_retracted_total` | + flux annulés, signaux retirés / recalculés / ajoutés, retraits ignorés, profils inexacts, rejeux tronqués |
| `mvn verify` | 529 tests | **544 tests, 0 échec, 0 ignoré** |

## Conception appliquée (écarts avec le document de conception)

- **Réinclusion** : le document prévoyait seulement d'oublier l'identifiant. Le banc (R01e) a montré que ce n'était
  pas suffisant : la transaction réincluse arrive **après** la sortie qu'elle explique, et le transfert immédiat ne
  s'évalue qu'à une sortie. Une réinclusion déclenche donc la même réévaluation que le retrait (même fenêtre).
- **Signaux dépendants** : limités aux signaux issus des flux. Une approbation et un transfert peuvent partager une
  transaction ; le signal d'approbation ne serait pas recréé par le rejeu des flux et aurait été supprimé à tort.
  Les approbations et changements d'administrateur retirés ont leur propre traitement.
- **Journal (étape 2 de l'ADR-0024, désactivé par défaut)** : les suppressions passent par la même file d'écriture
  que les insertions, dans l'ordre, pour qu'un retrait ne devance jamais l'insertion qu'il annule.
- **Notification `alert.retracted`** : routée vers les canaux des actions de la règle (même routage que l'ouverture,
  y compris le connecteur par défaut), une fois par passage à « sans preuve valide ».

## Fichiers modifiés (item 2)

| Module | Fichier | Changement |
|---|---|---|
| intelligence | `flow/FlowStore.java`, `flow/InMemoryFlowStore.java` | `remove(eventId)`, idempotent, l'identifiant est oublié |
| intelligence | `profile/WalletProfileAccumulator.java` | `revert(flow)` exact ; compte de flux par transaction (`RecentCounts`) |
| intelligence | `profile/WalletProfileService.java` | `revert(flow)` des deux côtés, sans créer de profil |
| intelligence | `detection/SignalRepository.java`, `detection/InMemorySignalRepository.java` | `findByEvidenceReference(txHash)`, `remove(id)` |
| intelligence | `detection/BalanceBook.java` | `forget(eventId)` |
| intelligence | `IntelligencePipeline.java` | `retract(ChainEvent)`, `retractFlow`, `RetractResult`, rejeu borné (`replay`), réévaluation à la réinclusion, retrait d'approbations et de changements d'admin |
| app | `chain/ChainIngestionService.java` | `applyRetraction` : événements de signal retirés vers le moteur de règles, signaux récents mis à jour, compteurs |
| app | `config/MetricsConfiguration.java` | `oci_reorg_reverted_flows_total`, `oci_reorg_signals_total{outcome}`, `oci_reorg_ignored_total`, `oci_reorg_inexact_profiles_total`, `oci_reorg_replay_truncated_total` |
| app | `journal/IntelligenceJournal.java`, `journal/JdbcIntelligenceJournal.java`, `journal/JournalingStores.java` | suppressions ordonnées dans le journal |
| alerting | `alert/Alert.java` | `EvidenceState` (`VALID`, `PARTIALLY_WITHDRAWN`, `WITHDRAWN`), `withdrawnEventIds` |
| alerting | `alert/AlertService.java` | `onRetraction` → `EvidenceChange` (idempotent, historique) ; restauration à la réinclusion ; compteurs |
| alerting | `pipeline/AlertingPipeline.java` | `alert.retracted` mis en file quand la dernière preuve disparaît |
| alerting | `notify/Notification.java`, `NotificationFormatter.java`, `WebhookSignature.java`, `connector/WebhookConnector.java`, `delivery/DeliveryService.java` | événement de notification, en-tête `X-OCI-Event`, texte « preuve retirée », `enqueueRetraction` |
| app | `persistence/JdbcAlertRepository.java`, `persistence/JdbcDeliveryStore.java` | colonnes V10 ; événement dans l'instantané de l'outbox (anciens instantanés lus comme `alert.opened`) |
| app | `db/migration/V10__alert_evidence_state.sql` | 2 colonnes avec valeurs par défaut + index partiel ; **additive** : les alertes existantes deviennent `VALID` |
| app / frontend | `api/Dto.java`, `alerts/AlertFacade.java`, `core/models.ts`, `alerts/alert-detail.html`, `alerts/alerts.html` | `evidenceState`, `withdrawnEventIds` dans l'API ; bandeau et puce dans la console |

## Tests exécutés

| Niveau | Tests | Résultat |
|---|---|---|
| Banc | R01a à R01e + 12 scénarios historiques | 5 / 5 ; historiques identiques |
| Unitaires intelligence | `ReorgRetractionTest` (8) : retrait idempotent du stock, profil exact (transaction à 2 flux), **signal agrégé dont une seule transaction est retirée** (recalculé, même id, preuve mise à jour), signal plus justifié retiré et score recalculé, signaux dérivés sur d'autres wallets, **retrait répété sans effet**, **réinclusion sans double comptage**, approbation retirée | 8 / 8 |
| Unitaires alertes | `AlertServiceTest` : retrait partiel puis sans preuve, retrait répété idempotent, restauration à la réinclusion ; `DeliveryAndPipelineTest` : `alert.retracted` livré **une fois** (en-tête, charge utile), rien sur retrait partiel ni sur avis répété | 4 nouveaux + existants |
| Persistance (PostgreSQL, Testcontainers) | état de preuve après redémarrage, idempotence après redémarrage ; événement de notification dans l'outbox, anciens instantanés lus comme ouverture ; migration V1 à V10 sur base vide puis idempotente | réussis |
| Intégration (application complète) | `ReorgIntegrationTest` : transfert depuis une adresse sanctionnée → exposition → alerte (règle livrée) ; retrait → flux, signal, score, signaux récents annulés, alerte `WITHDRAWN`, statut inchangé ; avis répété sans effet ; réinclusion → même signal, alerte `VALID`, pas de seconde alerte, compté une fois | réussi |
| Frontend | `ng build`, `ng test` | 142 tests |
| Projet | `mvn -B verify` (Docker actif) | **BUILD SUCCESS, 544 tests, 0 échec, 0 ignoré** |

## Risques et limites résiduels

1. **Activité repliée** : un flux plus ancien que la mémoire exacte d'un profil (4 096 derniers événements) n'est pas
   retiré de ce profil ; ses totaux restent tels quels. Compté par `oci_reorg_inexact_profiles_total`. En pratique, une
   réorganisation touche des blocs récents ; seuls des wallets extrêmement actifs sont concernés.
2. **Après un redémarrage** : une réorganisation de blocs indexés avant le redémarrage est détectée, mais leurs
   événements ne sont plus connus (le point de reprise ne garde que les références de blocs) ; sans journal, l'état
   correspondant n'existe déjà plus en mémoire. Les alertes en base concernées ne sont pas requalifiées.
3. **Coût du rejeu** : jusqu'à 500 déclencheurs par wallet concerné, sur ±24 h ; au-delà, le rejeu est tronqué et
   compté (`oci_reorg_replay_truncated_total`), mais le signal n'indique pas encore cette troncature (item 5,
   `SearchCoverage`).
4. **Recherche par référence de preuve** : parcours linéaire du stock de signaux (150 000 au plus) à chaque retrait ;
   acceptable parce que les réorganisations sont rares (0 pendant la mesure du jour). À indexer si la fréquence change.
5. **Notification** : `alert.retracted` suit les actions de la règle, pas les abonnements déclarés des webhooks (qui
   ne filtrent rien aujourd'hui, ni pour `alert.opened`). Les connecteurs de discussion reçoivent un texte préfixé
   « EVIDENCE WITHDRAWN ».
6. **Données réelles** : aucune réorganisation n'a encore été observée en production ; le comportement est prouvé sur
   données synthétiques et sur les réorganisations simulées des tests.
