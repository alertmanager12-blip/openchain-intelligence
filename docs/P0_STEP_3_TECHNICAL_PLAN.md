# P0, étape 3 : plan technique détaillé

Date : 2026-11-10. Statut : **proposition, rien n'est implémenté**. Base : branche `wip/adr-0024-step-3a`
(`299db4f` + corrections de l'étape 2, non commitées). Ordre fixé par la revue du 2026-11-10.

Règles qui s'appliquent à chaque item : tests de non-régression conservés ; banc complet relancé (attentes inchangées,
toute modification justifiée par une règle métier écrite) ; mesure avant/après ; pas de perte de rappel ; `mvn verify` ;
rien en production ; pas de commit ni de push sans accord. Chaque item se termine par un rapport présenté avant
l'item suivant.

Exigence transverse (point 3 de la revue) : **toute limite de recherche est visible dans le résultat** : profondeur
demandée et explorée, budgets épuisés, chemins non explorés, données manquantes. Une analyse partielle n'est jamais
présentée comme complète. Elle est traitée à l'item 5, et les items 2 à 4 doivent déjà la respecter.

---

## Item 1 : terminer et documenter la validation mémoire 3b

**État à 22 h 19** (mesure lancée à 20 h 22, fin vers 23 h 20) : 0 GC complet, 0 redémarrage ; profils tenus à
300 000 ; flux à 455 000 (plafond 400 000 hors adresses conservées) ; **signaux à 240 000 pour un plafond de 150 000,
toujours en hausse** ; ancienne génération à 1,9 Go et encore en hausse lente ; **Ethereum encore environ 4 000 blocs
en retard** : le régime normal n'aura pas été observé.

**Actions** : à la fin de la mesure, histogramme du heap (déjà programmé), tableau final, analyse du plateau,
`docs/ADR-0024-STEP-3B-VALIDATION.md` complété avec un verdict.

**Verdict attendu, à confirmer par les données** : « amélioration, mais à confirmer », parce que (1) le plafond des
signaux ne borne pas (item 3), (2) le régime normal n'a pas été observé.

**Proposition pour conclure** : une deuxième mesure **après** l'item 3, une fois le rattrapage terminé, de 12 h au
moins, avec des seuils fixés à l'avance : pente des données vivantes après GC < 20 Mo/h sur les 6 dernières heures ;
0 GC complet ; conteneur < 90 % de sa limite ; 0 redémarrage ; chaque plafond respecté à chaque purge.

---

## Item 2 : réorganisations blockchain

**Défaut** : un flux retiré par une réorganisation reste dans les flux, les profils, les signaux et les scores
(`IntelligencePipeline.java:40-41` ; `ChainIngestionService.java:308-313`). Seuls les gels sont annulés.

**Fréquence observée** : `oci_reorg_total` = 0 sur toutes les chaînes pendant la mesure 3b (l'indexeur attend 12 à 19
confirmations). Le cas est rare, mais un flux fantôme peut produire un signal d'exposition ou de transfert immédiat
sur des fonds qui n'ont jamais existé : c'est un défaut d'exactitude des preuves.

**Conception**

| Composant | Changement |
|---|---|
| `FlowStore` / `InMemoryFlowStore` | `Optional<ValueFlow> remove(String eventId)` (retire des listes entrantes et sortantes et de l'index) ; décorateur du journal : délégation (et suppression de la ligne V9 si le journal est actif) |
| `WalletProfileAccumulator` | `revert(ValueFlow)` : soustrait les totaux et compteurs par jeton (exacts), décrémente la contrepartie, retire l'instant d'activité s'il est encore dans la partie non repliée ; compteur de transactions : les hachages récents gardent un nombre de flux par transaction, la transaction n'est décomptée que lorsque son dernier flux est retiré. Limite documentée : un flux plus ancien que la partie repliée (4 096 derniers événements, 2 048 instants) ne peut pas être retiré exactement de l'activité repliée. |
| `SignalRepository` | `removeByEvidenceReference(txHash)` : retire les signaux dont une preuve de type flux cite la transaction retirée (ils ont été dérivés d'un flux qui n'existe plus) ; retourne les adresses touchées |
| `IntelligencePipeline.retract(ChainEvent)` | retire le flux, annule le profil des deux parties, retire les signaux dérivés, oublie l'identifiant d'événement (une nouvelle branche peut réinclure la transaction, comme déjà fait pour les gels, `:174`), retire l'entrée correspondante de `BalanceBook`, relance la détection sans déclencheur sur les adresses touchées (exposition, circulation) et recalcule leur score |
| `ChainIngestionService.processLocked` | appelle `retract` pour tout événement retiré de type transfert (aujourd'hui : seulement les gels) |
| Alertes | inchangé à cet item (déjà annotées, `AlertService.java:137-147`) ; le sort d'une alerte dont la preuve a disparu sera traité dans la politique de routage (item 6) |
| Métriques | `oci_reorg_reverted_flows_total`, `oci_reorg_removed_signals_total` |

**Limite qui reste** : après un redémarrage, une réorganisation de blocs indexés avant le redémarrage est détectée
mais ses événements ne sont pas connus (le point de reprise V8 ne garde que les références de blocs). Sans journal,
l'état en mémoire de ces blocs n'existe plus de toute façon ; à documenter, pas à corriger ici.

**Tests** : unitaires (`retract` : flux absent, totaux du profil revenus à l'état antérieur, signal dérivé retiré,
score recalculé, transaction réincluse acceptée à nouveau) ; intégration avec le `BlockIndexer` (réorganisation de
profondeur 1 et 3 de `BlockIndexerTest`, via `ChainIngestionService`) ; **nouveau scénario de banc R01** (nouvelle
capacité, attente fixée avant le code) : une mule dont le flux entrant est retiré par réorganisation ne doit plus
porter de `FAST_FORWARDING` ; un wallet exposé à un mixer par un flux retiré ne doit plus porter de `MIXER_EXPOSURE`.

**Impact sur la détection** : supprime des signaux et des scores fondés sur des fonds inexistants ; aucun effet sur les
flux valides (banc inchangé attendu). **Critères** : banc existant identique ; R01 conforme ; 0 flux retiré encore
présent dans le stock après une réorganisation (test) ; totaux des profils exacts après retrait (test).

---

## Item 3 : plafond des signaux

**Défaut** : les signaux des wallets qui ont un profil ne sont jamais retirés ; un wallet actif accumule des signaux
sans limite (240 000 pour un plafond de 150 000 à 22 h 19, en hausse).

**Conception**

1. **Plafond par wallet** dans `InMemorySignalRepository` (64 par défaut, configurable) : au-delà, retrait du plus
   ancien signal de sévérité INFO à MEDIUM ; les HIGH et CRITICAL sont retirés en dernier, et seulement au-delà de
   leur propre plafond (16 par wallet).
2. **Plafond global**, ordre de retrait (déjà justifié dans `docs/ADR-0024-STEP-3B-VALIDATION.md` § 10) : (a) signaux
   des wallets sans profil, du plus ancien au plus récent ; (b) signaux INFO à MEDIUM des wallets avec profil ;
   (c) HIGH et CRITICAL. La liste de surveillance et les historiques chargés restent exclus.
3. **Aucun retrait silencieux** : métrique `oci_signals_evicted_total{severity,reason}` et ligne de journal de la purge
   par sévérité ; un retrait de HIGH ou CRITICAL est un événement visible.

**Effet sur le score** : le score est calculé à partir des signaux stockés (demi-vie de 180 jours) ; retirer d'anciens
signaux faibles peut baisser un score. **Mesure** : sur le banc, scores avant et après avec un plafond par wallet
volontairement bas (4) pour rendre l'effet visible ; écart maximal documenté.

**Point de vigilance** : tant que la politique de routage (item 6) n'existe pas, un signal grave non transformé en
alerte et retiré est perdu. D'où l'ordre de retrait (HIGH et CRITICAL en dernier) et la métrique par sévérité.

**Tests** : unitaires (plafond par wallet, ordre de retrait, exclusions) ; intégration `WorkingSetTrimTest` (signaux ≤
plafond après purge) ; banc inchangé. **Critères** : signaux ≤ plafond à chaque purge dans la deuxième mesure ; 0 HIGH
ou CRITICAL retiré tant que des signaux plus faibles existent ; écart de score sur le banc documenté.

---

## Item 4 : analyse du fan-out et de la recherche de chemins d'exposition indirecte

Cet item est une **analyse avec propositions** ; les corrections seront présentées avant d'être faites.

### 4.1 `FanOutDetector`

| Défaut | Référence | Effet | Proposition |
|---|---|---|---|
| tous les jetons comptés, y compris non vérifiés (`UNKNOWN`) et poussière | `detectors/FanOutDetector.java:26-28` | un distributeur de spam ou d'airdrop déclenche en continu ; le compte de destinataires est gonflé | ne compter que les jetons vérifiés et les montants ≥ seuil de poussière (seuils déjà configurés pour l'exposition) |
| fenêtre découpée par tranches horaires fixes (`SignalIds.window`) | `:36-37` | une même dispersion à cheval sur deux tranches donne 2 signaux (constaté sur HUB) | déduplication sur le premier flux de la rafale, comme `FastForwarding` |
| exchanges, routeurs, paies déclenchent une fois par heure | — | bruit structurel | pas de filtre arbitraire : à traiter par le contexte (labels EXCHANGE et DEFI, périmètre client) dans la politique de routage |

Scénarios de banc à ajouter **avant** la correction : B05 distributeur d'airdrop en jeton non vérifié (fan-out
interdit), S09 dispersion à cheval sur deux heures (un seul signal attendu).

### 4.2 Recherche de chemins de `ExposureDetector`

| Défaut | Référence | Effet | Proposition |
|---|---|---|---|
| ensemble « visité » global | `detectors/ExposureDetector.java:130,133,156` | une adresse atteinte d'abord par un chemin n'est plus explorée par un autre, même si l'autre permettait de remonter plus loin dans le temps : **faux négatifs** | visiter à nouveau une adresse si le nouveau chemin a une borne de temps plus tardive (clé adresse + meilleure borne connue), budgets inchangés |
| montant du goulot ignoré | `:146` | 10 USDT tracés comptent comme 1 million | ajouter la part tracée (goulot / montant reçu) à la preuve ; l'utiliser dans la confiance seulement après mesure sur le banc |
| pas d'exposition en aval | — | un wallet qui envoie vers un mixer via un intermédiaire n'est pas relié au mixer | capacité P1 (traçage en aval), hors P0 |
| l'API d'exposition ne contrôle pas l'ordre chronologique | `oci-app/.../analysis/InvestigationService.java:266-311` | l'API peut afficher une exposition que le détecteur refuse | aligner l'API sur la règle du détecteur |

Scénario de banc à ajouter **avant** la correction : S10, deux chemins vers une même adresse intermédiaire dont seul le
second, plus tardif, remonte à une adresse sanctionnée (exposition indirecte attendue, aujourd'hui manquée).

---

## Item 5 : troncature silencieuse et visibilité des limites de recherche

**Défauts** : budgets de `CircularFlowDetector` (10 000 extensions, 20 cycles) et d'`ExposureDetector` (5 000 flux,
20 chemins) épuisés sans le dire ; graphe : `truncated` ne couvre que le plafond de nœuds ; profondeur limitée à 3 par
validation (une demande de 5 est refusée au lieu d'être explorée jusqu'à 3 avec une mention) ; éviction des flux par
la mémoire jamais signalée.

**Conception : un compte rendu de couverture commun**

```
SearchCoverage {
  requestedDepth, exploredDepth,          // 5 demandés, 3 explorés
  nodesVisited, nodeBudget, flowsVisited, flowBudget,
  exhausted: [NODE_BUDGET | FLOW_BUDGET | PATH_LIMIT | DEPTH_LIMIT | TIME_BUDGET],
  unexploredFrontier,                      // adresses atteintes mais non développées
  dataFrom,                                // plus ancien flux disponible pour ces adresses (éviction mémoire)
  missingData: [INTERNAL_TRANSACTIONS_NOT_INDEXED, NO_HISTORICAL_BALANCE_TRON, ...],
  complete                                 // vrai seulement si rien de ce qui précède ne limite la réponse
}
```

| Où | Changement |
|---|---|
| `InvestigationService.graph` et `exposure` | renvoient un `SearchCoverage` ; profondeur demandée au-delà du maximum acceptée et ramenée au maximum avec mention `DEPTH_LIMIT` |
| `CircularFlowDetector`, `ExposureDetector` | preuve de type `coverage` dans le signal quand un budget est épuisé ; métrique `oci_detector_budget_exhausted_total{detector}` |
| Stock de flux | expose la date du plus ancien flux conservé par réseau (filigrane d'éviction) pour remplir `dataFrom` |
| Console | bandeau « Analyse partielle : … » sur le graphe, l'exposition et la fiche wallet, avec la liste des limites |
| API | champ `coverage` additif (v1 non cassée) ; documentation OpenAPI |

**Tests** : un test par limite (budget de nœuds, de flux, de chemins, profondeur demandée supérieure, données
évincées, données manquantes par réseau) ; `complete` vrai seulement sans limite. **Critères** : 0 résultat de recherche
sans compte rendu de couverture ; chaque budget épuisé visible dans la réponse et dans la métrique.

---

## Item 6 : politique de routage des signaux vers les alertes

**Document de conception d'abord, aucun développement avant validation.** Contenu prévu :

1. Inventaire : les 15 types de signaux, leur niveau de preuve, leur sévérité possible, leur taux de faux positifs sur
   le banc et en production.
2. Matrice de décision : type × sévérité × niveau de preuve × périmètre (liste de surveillance ou non) × contexte
   (labels EXCHANGE, DEFI) → alerte, alerte regroupée, ou trace consultable sans alerte.
3. **Garantie « aucun signal grave ignoré en silence »** : tout signal HIGH ou CRITICAL aboutit soit à une alerte, soit
   à une entrée « signal grave non alerté » avec sa raison, visible dans l'API et la console.
4. Regroupement : un schéma, une alerte (par exemple, les signaux d'un même wallet sur une même fenêtre).
5. Sort des alertes dont la preuve a disparu (réorganisation, item 2).
6. Mesure : banc étendu au passage signal → alerte, et API de bruit en production ; objectifs chiffrés.

---

## Calendrier proposé et points de décision

| Ordre | Item | Livrable présenté avant la suite |
|---|---|---|
| 1 | Validation 3b (ce soir) | `docs/ADR-0024-STEP-3B-VALIDATION.md` complété, verdict |
| 2 | Réorganisations | rapport : tests, banc (dont R01), limites |
| 3 | Plafond des signaux | rapport : tests, banc, effet sur les scores |
| 4 | Analyse fan-out et chemins | propositions chiffrées (scénarios B05, S09, S10 ajoutés avant le code) |
| 5 | Visibilité des limites | rapport : couverture dans chaque résultat |
| 6 | Politique de routage | document de conception à valider |
| — | Deuxième mesure mémoire (12 h, régime normal) | après l'item 3 |
