# Audit de ChainSentinel au regard de sa mission fondamentale

Date : 2026-11-10. Base : branche `wip/adr-0024-step-3a` (commit `299db4f`, = `main` + étapes 3a et 3b de l'ADR-0024),
instance de production locale (Ethereum mainnet + TRON) et base PostgreSQL de production.
Méthode : lecture du code et des tests (pas de la documentation), requêtes sur la base de production, et une sonde
d'investigation sur données synthétiques (détails dans `docs/CHAIN_SENTINEL_INVESTIGATION_INTELLIGENCE.md`).
Chemins relatifs à la racine du dépôt ; `intelligence/` = `oci-intelligence/src/main/java/io/openchain/intelligence/`.

## 1. La mission

ChainSentinel doit **détecter, comprendre, corréler, investiguer et expliquer** les activités suspectes sur les
blockchains. Indexer, afficher, alerter sont des moyens. Toute décision d'architecture se juge à sa contribution à la
qualité de l'analyse ; une économie de mémoire qui fait perdre de la détection n'est pas un succès. L'absence de signal
ne signifie jamais qu'une adresse est sûre.

## 2. Ce que la production montre (7 au 10 novembre 2026)

Requêtes sur la table `alerts` et `deliveries` de l'instance réelle :

| Fait | Valeur |
|---|---|
| Alertes créées en 3 jours | 7 921 (Ethereum 4 591, TRON 3 340) |
| Alertes confirmées ou résolues comme vrais positifs | **0** |
| `wallet-draining` | 2 302 alertes, dont 2 242 classées faux positifs (**97 %**) |
| `fund-splitting` | 2 680 alertes, dont 2 677 jamais traitées ; encore 196 sur les dernières 24 h |
| `suspicious-wallet`, `suspicious-approval` | 2 044 et 675 alertes avant le 9/11 10 h, **0 depuis** (période d'observation de 30 jours, voir 4.3) |
| Règles qui se déclenchent encore (24 h) | `fund-splitting` (196) et `large-usdt-transfer` (30) |
| Livraisons de notifications | 6 625 en échec définitif, 2 281 livrées, 394 ignorées |
| Liste de surveillance (périmètre client) | 0 adresse |

Ces chiffres ne mesurent pas la qualité de détection (aucune vérité terrain), mais ils montrent qu'aucune alerte n'a
encore été établie comme pertinente, et que le volume a été réduit en rendant des règles muettes, pas en les rendant
justes.

## 3. Capacités analytiques réellement disponibles

### 3.1 Données collectées

| Source | Couvert | Non couvert | Références |
|---|---|---|---|
| EVM (Ethereum, BSC, Polygon) | transferts natifs de premier niveau (`tx.value`), transferts ERC-20, approbations (jetons du registre), gels Tether/Circle, changements d'admin (contrats du registre ou surveillés) | **transactions internes** (sorties de mixers, contrats), ERC-721, `RoleGranted` | `oci-blockchain/.../evm/EvmJsonRpcProvider.java:30-46,180-241`, `LogDecoder.java:62-112`, `BlockIndexer.java:108-121,176-180` |
| TRON | TRX (TransferContract + `call_value`), TRC-20, approbations, gels, changements d'admin | **TRC-10, transactions internes, solde historique** | `oci-blockchain/.../tron/TronGridProvider.java:33-45,286-327`; `oci-core/.../provider/BlockchainProvider.java:56` |
| Historique d'adresse à la demande (ADR-0020) | EVM : ERC-20 + approbations (`eth_getLogs`) ; TRON : TRC-20 + TRX | EVM : ETH natif, internes ; plafonds 5 000 événements / 500 000 blocs | `oci-app/.../history/AddressHistoryService.java:184-335`; `OciProperties.java:250-263` |
| Événements jamais produits | `CONTRACT_CALL`, `RISK_SCORE_CHANGED` | | `PopulatedFields.java:17-48`, `RuleReachability.java:20-79` |

### 3.2 Détecteurs (tous branchés : `intelligence/detection/DetectionEngine.java:29-33`, production `oci-app/.../config/ModulesConfiguration.java:57-76`)

| Détecteur | Comportement détecté | Fonctionne sur chaîne réelle ? | Principale limite |
|---|---|---|---|
| `ExposureDetector` | exposition directe (entrante et sortante) et indirecte (remontée des **entrées** seulement, ≤ 3 sauts, confiance × 0,5 par saut) à une adresse étiquetée à risque | oui, **si des labels existent** (aucun hors gels, voir 3.4) | pas d'exposition indirecte **en aval** ; ensemble « visité » global qui masque des chemins valides (`detectors/ExposureDetector.java:130-156`) ; montant du goulot ignoré |
| `FastForwardingDetector` | fonds reçus puis renvoyés à ≥ 90 % en 30 min | oui | **ratio sans borne ni solde** : recevoir 1 USDT puis envoyer 1 000 déclenche (`detectors/FastForwardingDetector.java:26-33`) |
| `StructuringDetector` | ≥ 3 sorties dans [9 000 ; 10 000[ en 24 h | oui | seuil de 10 000 appliqué à **tous** les actifs (ETH, TRX, jetons inconnus) (`DetectionConfig.java:72-73`) ; fenêtre de 24 h tronquée par la mémoire |
| `FanOutDetector` | ≥ 20 destinataires distincts en 1 h | oui | aucun filtre de jeton ni de montant (spam, airdrops comptés) ; exchanges et routeurs déclenchent en continu |
| `CircularFlowDetector` | retour des fonds à l'origine en ≤ 4 transferts, 7 jours | oui | montant retourné non comparé ; budget partagé qui coupe en priorité les cycles récents, sans le signaler ; fenêtre de 7 jours tronquée |
| `UnusualAmountDetector` | montant hors norme (z robuste ≥ 3,5) sur ≥ 10 flux | oui (wallets actifs) | historique constant ⇒ MAD = 0 ⇒ z = ∞ : tout montant supérieur déclenche en MEDIUM (constaté dans la sonde) ; entrées et sorties mélangées |
| `RapidDrainDetector` | ≥ 80 % du solde sorti en 1 h | **TRON : jamais** (pas de solde historique) ; Ethereum : ≤ 5 consultations de solde par bloc | rate le cas « reçu et vidé dans l'heure » (solde initial nul dans la fenêtre, constaté dans la sonde) |
| `DormantReactivationDetector` | réactivation après ≥ 180 jours | **jamais pour les wallets non surveillés** (flux gardés 24 h au plus) | tout flux entrant (spam) remet l'horloge à zéro |
| `UnusualApprovalDetector` | approbation quasi illimitée | oui (jetons du registre) | déclenche sur chaque approbation normale à un routeur DeFi (Permit2, Uniswap) |
| `OwnershipChangeDetector` | changement d'admin/propriétaire de contrat | oui (contrats du registre ou surveillés) | mises à jour de proxy courantes en MEDIUM |

Tests : tous les tests de détecteurs utilisent des données synthétiques (`oci-intelligence/src/test/.../DetectorsTest`,
`DetectorSummariesTest`, `IntelligencePipelineTest`, `RapidDrainRealBalanceTest`) ; **aucune donnée réelle n'atteint un
test de détection** ; aucun test du comportement après éviction mémoire, des budgets de `CircularFlowDetector` et
`ExposureDetector`, de `FastForwarding` quand la sortie dépasse l'entrée, ni d'`UnusualAmount` sur historique constant.

### 3.3 Scoring de risque (`intelligence/risk/RiskScoringEngine.java`, `ScoringConfig.java`)

Explicable et borné : chaque point a une contribution (facteur, confiance, gravité, âge, preuve) ; pondération par
facteur, combinaison probabiliste, demi-vie de 180 jours ; seuls les labels SANCTIONED et ISSUER_FROZEN ne décroissent
pas. Testé (`RiskScoringEngineTest`, 12 tests). Limites : le score n'utilise que les éléments **propres** à l'adresse ;
il n'est recalculé qu'au passage d'un événement ; il est **oublié** quand le profil est évincé
(`ChainIngestionService.java:698-707`).

### 3.4 Renseignement (labels)

| Source | État | Référence |
|---|---|---|
| Gels d'émetteur Tether / Circle (on-chain) | **réelle**, fiabilité 1,0, CONFIRMED_BY_SOURCE ; BSC, USDC.e et USDC TRON non couverts ; gels en direct non persistés | `oci-blockchain/.../token/StablecoinFreezeSources.java:48-57`; `IntelligencePipeline.java:136-180` |
| Listes de sanctions (OFAC…) | **aucune** livrée ni téléchargée ; fichier opérateur possible au démarrage | `application.yml:70-71` (`files: []`) |
| Labels de démonstration | 5 lignes fictives, profil démo seulement | `oci-intelligence/src/main/resources/labels/demo-labels.csv:1-2` |
| Signalements communautaires | stockés en PENDING, **aucun circuit de validation** : ne deviennent jamais des labels | `LabelReportStore.java:9-13` |
| Modèle de label | source, fiabilité, date d'observation, niveau de preuve ; **pas d'expiration ni de révocation** ; catalogue **non persisté** | `intelligence/label/AddressLabel.java:15-28`; `oci-app/.../labels/LabelCatalog.java` |

### 3.5 Traçage, relations, corrélation

- Graphe : parcours en largeur dans les deux sens, ≤ 3 sauts, ≤ 500 nœuds (80 depuis la console), en mémoire, **ni
  sauvegardé ni rejouable** (`oci-app/.../analysis/InvestigationService.java:349-399`).
- Exposition (API) : saut 1 dans les deux sens ; sauts 2-3 **en remontée seulement**, sans contrôle de l'ordre
  chronologique (le détecteur, lui, le contrôle) (`InvestigationService.java:266-311`).
- Chemin entre deux adresses : aucun côté serveur ; la console calcule un chemin non orienté dans le navigateur
  (`frontend/src/app/features/investigation/graph-canvas.ts:172-182`).
- **Regroupement de wallets (contrôle commun, financement commun, co-dépense, adresses de dépôt) : inexistant.**
- Corrélation : uniquement dans les règles (agrégations, séquences, réinjection d'une alerte comme événement). Aucun
  lien entre alertes portant sur les mêmes fonds, aucune notion de dossier, aucune corrélation inter-chaînes.

## 4. Pertes de capacité et risques introduits par l'architecture

### 4.1 Mémoire et rétention (ADR-0024)

Tous les détecteurs ne lisent que le stock de flux en mémoire. Avec les plafonds de l'étape 3b (400 000 flux) et
environ 4,8 millions de flux par jour, les wallets non surveillés ne gardent **qu'environ 2 h de flux**
(`docs/ADR-0024-STEP-3B-VALIDATION.md` § 6). Conséquences : structuration (24 h), flux circulaires (7 j), exposition
indirecte et références de montants réduites à 2 h ; réactivation de wallets dormants impossible. Le plafond des
signaux ne borne pas (même document § 5.1). Les wallets de la liste de surveillance gardent tout, mais **elle est
vide en production**.

### 4.2 Qualité des données

- **Réorganisations** : les flux retirés de la chaîne ne sont pas retirés des flux, profils, signaux et scores
  (`IntelligencePipeline.java:40-41`; `ChainIngestionService.java:308-313`). Une alerte n'est jamais retirée, seulement
  annotée (`AlertService.java:137-147`).
- Reprise après un écart supérieur à 7 200 blocs : la plage sautée est **seulement journalisée**, ni exposée ni testée
  (`Bootstrap.java:395-408`).
- Le graphe et l'exposition ne disent pas que des flux ont été évincés : `truncated` ne concerne que le plafond de nœuds.

### 4.3 Champs de règles conditionnels

`wallet.age_days`, `wallet.tx_count`, `counterparty.age_days`, `approval.spender_age_days` ne sont émis qu'après
30 jours d'observation continue ; le début d'observation est en mémoire et repart à zéro à chaque redémarrage
(`ChainIngestionService.java:132,151-156,308`). `suspicious-wallet` et `suspicious-approval` sont donc muettes en
pratique, et pourront produire des faux positifs ensuite (un profil évincé fait paraître un wallet neuf).
`RuleReachability` ne modélise pas ces conditions.

### 4.4 Chaîne signal → alerte

Seuls `DORMANT_REACTIVATION`, `SANCTIONED_EXPOSURE` et `INDIRECT_EXPOSURE` (confiance ≥ 0,5) sont consommés par les
règles livrées (`rules/generic/dormant-wallet-reactivation.yaml`, `rules/generic/sanctioned-exposure-signal.yaml`).
Une exposition **directe à un mixer, une arnaque, des fonds volés** (signal HIGH) ne crée **aucune alerte**. À l'inverse,
`sanctioned-exposure-signal` se déclenche pour une exposition indirecte à **n'importe quelle** catégorie à risque,
gels d'émetteur compris, alors qu'elle se présente comme une règle « liste de sanctions »
(`detectors/ExposureDetector.java:140-148`).

### 4.5 Faux positifs et validation

Risques de faux positifs documentés dans le code : `FastForwarding` (ratio non borné), `UnusualAmount` (z infini),
`Structuring` (seuil hors stablecoins), `FanOut` et `CircularFlow` (exchanges, routeurs), `UnusualApproval` (routeurs
DeFi), `wallet-draining` (97 % en production). **Aucun jeu de vérité terrain**, aucune mesure de précision ni de rappel.

### 4.6 Fonctionnalités présentes mais non intégrées

`EmailConnector` jamais instancié ; abonnements webhook `alert.updated/retracted/status_changed` stockés mais seul
`alert.opened` est envoyé (`WebhookConnector.java:47`) ; signalements communautaires sans validation ;
`DetectionContext.profile` jamais lu par un détecteur ; `DetectionConfig` non configurable en production ;
`oci.chains.<n>.provider` jamais lu ; règle `contract-exploit` impossible à déclencher ; livraisons ignorées ou en
échec visibles seulement dans Prometheus.

## 5. Recommandations prioritaires

Chaque item répond aux 8 questions de la consigne (détection apportée, comportements, preuves, coût, faux positifs,
vérification, intégration) ; résumé ici, détail à produire dans l'ADR ou la PR de chaque item.

### P0 — Capacités fondamentales

1. **Banc d'évaluation des détecteurs** (vérité terrain) : bibliothèque de scénarios synthétiques versionnés (la sonde
   de ce jour en est le premier) + cas réels publics (gels confirmés par l'émetteur, incidents documentés) rejoués dans
   le pipeline ; précision et rappel par détecteur et par règle. *Sans cela, aucune autre amélioration n'est prouvable.*
2. **Corriger les défauts de détection identifiés**, chacun avec un test de non-régression : borne et solde dans
   `FastForwarding` ; z infini d'`UnusualAmount` ; seuils de `Structuring` par actif ; filtres jeton/montant de
   `FanOut` ; ensemble visité d'`ExposureDetector` ; regroupement des signaux `CircularFlow` redondants.
3. **Câbler les signaux importants vers les alertes** : expositions directes MIXER/SCAM/FLAGGED ; renommer et
   restreindre `sanctioned-exposure-signal` à ce qu'elle dit.
4. **Réorganisations** : retirer les flux, signaux et scores issus de blocs orphelins.
5. **Conserver les données analytiques nécessaires** : ADR-0026 (périmètre client) + correction du plafond des
   signaux ; persister le début d'observation ; signaler l'éviction dans les résultats (graphe, exposition, verdict).
6. **Réduire le bruit à la source** : `wallet-draining` et `fund-splitting` évalués sur le banc avant d'être actives
   par défaut ; livraisons en échec exposées dans l'API.

### P1 — Intelligence blockchain

7. Sources réelles avec provenance et date (liste SDN de l'OFAC pour les adresses crypto, gels couvrant toutes les
   émissions) ; expiration et révocation des labels ; nouvelle détection quand un label change.
8. Traçage **en aval** et chemins orientés dans le temps côté serveur, avec pondération par montant.
9. Relations : schémas dispersion → consolidation, financement commun, peel chains, activité coordonnée, toujours
   présentés comme INFERRED avec leurs hypothèses alternatives.
10. Transactions internes EVM quand le fournisseur le permet (sorties de mixers).

### P2 — Automatisation

11. Verdicts traçables et `POST /screening` (ADR-0026 § 5). 12. Dossiers d'investigation persistés (ADR-0022) sur les
données du périmètre. 13. Regroupement des alertes en dossiers.

### P3 — Écosystème

14. MCP en lecture seule, SDK, intégrations, intelligence prédictive : après P0-P2.

## 6. Feuille de route technique

| Étape | Contenu | Dépend de |
|---|---|---|
| 1 | Clore l'ADR-0024 (verdict de la mesure, correction du plafond des signaux) | — |
| 2 | Banc d'évaluation + scénarios (P0.1) | — |
| 3 | Corrections des détecteurs et câblage signal → alerte (P0.2, P0.3), mesurés sur le banc | 2 |
| 4 | Réorganisations (P0.4) | — |
| 5 | ADR-0026 validée puis implémentée (P0.5) | 1 |
| 6 | Sources de renseignement réelles (P1.7) | 2 (mesure de l'apport) |
| 7 | Traçage en aval, relations, peel chains (P1.8, P1.9) | 2, 5 |
| 8 | Screening et verdicts (P2.11), puis dossiers (P2.12, P2.13) | 5, 6 |
| 9 | MCP (P3) | 8 |

## 7. Critères mesurables

| Critère | Aujourd'hui | Cible |
|---|---|---|
| Détecteurs évalués sur le banc (précision et rappel publiés) | 0 / 10 | 10 / 10 |
| Scénarios de référence couverts | 0 | ≥ 15 (un par comportement de la consigne) |
| Signaux HIGH/CRITICAL pouvant produire une alerte | 2 types sur 15 | tous ceux jugés actionnables, documentés |
| Faux positifs par règle active en production | `wallet-draining` 97 % | < 30 % sur 7 jours, mesuré avec l'API de bruit |
| Alertes traitées (conclusion enregistrée) | 2 245 / 7 921 | > 90 % sur le périmètre client |
| Historique disponible pour les wallets surveillés | 24 h (vide en pratique) | ≥ 13 mois, persistant |
| Flux de blocs orphelins encore pris en compte | non mesuré (non retirés) | 0 |
| Résultats indiquant leur couverture et leurs limites | wallet seulement | tous (graphe, exposition, verdict) |

## 8. Réponse

**ChainSentinel n'est plus une simple infrastructure d'indexation, mais ce n'est pas encore un moteur capable de
détecter efficacement des activités suspectes.**

Ce qui existe réellement : dix détecteurs branchés en production qui reconnaissent des schémas réels (dispersion,
structuration, circulation, passage rapide, exposition directe et indirecte), avec des preuves (transactions,
chemins, labels) et un score explicable. La sonde synthétique le confirme : sur un scénario de type blanchiment,
**66 signaux** sont produits et la plupart des schémas sont reconnus.

Ce qui empêche de parler de détection efficace, preuves à l'appui :

1. **Aucune preuve d'efficacité** : tous les tests sont synthétiques, aucune vérité terrain, 0 alerte confirmée en
   production, 97 % de faux positifs sur une règle.
2. **La détection n'arrive pas à l'analyste** : 3 types de signaux sur 15 peuvent produire une alerte ; le scénario
   donne **66 signaux et 0 alerte**, y compris pour une exposition directe à un mixer.
3. **Presque pas de renseignement réel** : seuls les gels Tether/Circle ; aucune liste de sanctions ; pas de
   regroupement de wallets.
4. **Pas de mémoire analytique** : environ 2 h d'historique pour les wallets non surveillés, liste de surveillance
   vide, rien de persisté pour rejouer une investigation, flux de blocs orphelins jamais retirés.

Du point de vue d'un analyste, c'est aujourd'hui **principalement une infrastructure de surveillance dotée d'un bon
socle de détection non validé**. Les fondations (pipeline, détecteurs, preuves, score explicable) sont saines et
justifient de construire dessus plutôt que de réécrire ; la priorité est de **prouver** et de **faire aboutir** la
détection (P0) avant toute nouvelle fonctionnalité.
