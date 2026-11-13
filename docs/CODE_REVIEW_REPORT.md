# Revue de code complète — ChainSentinel

Date : 2026-11-13. Code revu : branche `wip/adr-0024-step-3a`, `94ea42c` (le plus récent). La production tourne sur
`299db4f`, ancêtre de ce commit : sauf mention contraire, les constats s'y appliquent aussi.

**Méthode.** Six revues indépendantes en lecture seule, une par zone, sans build ni test (la production tourne sur la
machine) :
- `oci-core` + `oci-blockchain` ;
- `oci-intelligence` ;
- `oci-alerting` ;
- `oci-app` côté API, sécurité et persistance ;
- `oci-app` côté ingestion et exécution ;
- frontend, déploiement et CI.

Les constats graves ont ensuite été **revérifiés dans le code** par le coordinateur. Légende :
- **[V]** vérifié par le coordinateur (ligne lue) ;
- **[R]** rapporté par un relecteur avec citation, non revérifié ;
- **[?]** à vérifier par un test.

Taille du code : environ 23 000 lignes Java (`main`) + 12 000 lignes de tests Java, environ 10 000 lignes TypeScript + 1 900
lignes de specs.

## 0. Suivi des corrections

| Lot | Points | État |
|---|---|---|
| 1 — Exactitude | B1, B2, B3, I1, I2, I11 | **corrigé**, commit `444e423` (570 tests Java, 145 frontend) |
| 2 — Disponibilité | I15, I16, I17, I18, I24 | **corrigé** le 2026-11-13 (voir CHANGELOG) |
| 2 (commit) | — | commit `2719814` (577 tests Java) |
| 3 — Sécurité et exploitation | I31 | **corrigé** le 2026-11-13 ; I27, I28, I29, I30 attendent une décision ; I32 (SHA des actions CI) à faire par Dependabot |
| 4 — Fournisseurs | I10, I19, I20, I21 | **corrigé** le 2026-11-13 ; I22 (HTTP 403 de TronGrid) non traité : comportement à vérifier à la source |
| 3 et 4 (commit) | — | commit `3580931` (583 tests Java) |
| 5 — Reste, première partie | I6, I7, I12, I13, I23, I35, jauge de retard (tête inconnue) | **corrigé** le 2026-11-13 (voir CHANGELOG) |
| 5 (commit, première partie) | — | commit `aab3f8a` (589 tests Java, 146 console) |
| 5 — Reste, deuxième partie | I33 (confirmé par un test, puis corrigé) ; mineurs : disjoncteur bloqué en HALF_OPEN, rattrapage des gels bloqué en RUNNING, audit de la création et de la mise à jour des règles | **corrigé** le 2026-11-13 (voir CHANGELOG) |
| 5 (commit, deuxième partie) | — | commit `c0cd18f` (595 tests Java) |
| 5 — I3 | réévaluation du seuil des alertes d'agrégation après un retrait (conception validée le 2026-11-13) | **corrigé** le 2026-11-13 (voir CHANGELOG et `P0_REORG_DESIGN.md` § 3.3) |
| 5 (commit I3) | — | commit `6f8fd07` (599 tests Java) |
| 5 — I4 | notification `alert.reinstated` (conception validée le 2026-11-13) ; en plus : nouvelle détection regroupée dans une alerte retirée, message Telegram de retrait trompeur ; constaté : la liste `events` des webhooks n'est pas appliquée (documenté) | **corrigé** le 2026-11-13 |
| 5 (commit I4) | — | commit `a75513d` (602 tests Java) |
| 5 — I5 | annulation de l'état du moteur et file de réessai bornée (conception validée le 2026-11-13, option A : le délai de retard n'est pas contourné) ; constaté en plus : les signaux dérivés de l'événement étaient perdus aussi | **corrigé** le 2026-11-13 |
| 5 — Reste, suite | I8, I9, I14, I25, I26, I34, I36, autres mineurs | à faire. Conception nécessaire : I8, I9, I25, I34 (marqueur de mode en base). I14 se règle avec l'étape 3d de l'ADR-0024 (instantané). Décision : I26, I36 |

Rien n'est déployé : la production tourne sur `299db4f`.

## 1. Verdict

**Le code est soigné et sûr sur ses fondations** : authentification, chiffrement, SSRF, DSL de règles sans exécution de
code, arithmétique exacte, explicabilité. En revanche, **plusieurs défauts faussent des résultats présentés comme
exacts** :
- annulations de réorganisation inopérantes en mode PostgreSQL ;
- faux jetons qui déclenchent des détecteurs ;
- âge 0 pour d'anciens wallets ;
- exposition multi-jetons.

S'y ajoutent des **risques de disponibilité** : RPC sous le verrou global, retrait de flux en O(n), envois de
notifications séquentiels.

**Aucun de ces points n'est un défaut d'authentification exploitable** par un tiers non authentifié.

| Gravité | Nombre |
|---|---|
| Bloquant | 3 (dont 1 latent) |
| Important | 30 |
| Mineur | environ 30 (résumés au § 4) |

## 2. Bloquants

### B1. Annulations de réorganisation perdues en mode PostgreSQL [V]

- **Constat :**
  - `BlockIndexer.poll()` relit le point de reprise **à chaque passage** (`BlockIndexer.java:60-66`) ;
  - `JdbcCheckpointStore.load` reconstruit la fenêtre **sans événements** (`List.of()`, `JdbcCheckpointStore.java:44`) ;
  - ce stockage est câblé **dès que la persistance PostgreSQL est active**, donc en production
    (`PersistenceConfiguration.java:125-127`), indépendamment du journal ;
  - le rollback (`BlockIndexer.java:164`) parcourt `ib.events()`, toujours vide.
- **Effet :**
  - une réorganisation est détectée mais **aucune annulation n'est émise** : flux, profils, signaux et alertes orphelins
    restent présentés comme valides ;
  - le correctif P0 « réorganisations » (`53566bd`) est neutralisé en production ;
  - la déduplication `seenIds` de la fenêtre l'est aussi ;
  - les tests utilisent `InMemoryCheckpointStore`, qui garde les événements, et ne le voient pas.
- **Atténuation :** l'indexeur ne lit que des blocs confirmés (12 sur Ethereum, 19 sur TRON), donc ces réorganisations
  sont rares.
- **Correctif :** garder la fenêtre et ses événements en mémoire dans `BlockIndexer` et ne lire le stockage qu'au premier
  passage ; ou persister les identifiants d'événements de la fenêtre. Test : stockage « références seules » +
  réorganisation au passage suivant.

### B2. Faux jetons dans les détecteurs comportementaux [V]

- **Constat :**
  - l'indexeur émet les transferts de **tous** les contrats, sans filtre `isKnown` pour les transferts
    (`BlockIndexer.java:113` ; les approbations, elles, sont filtrées à la ligne 115) ;
  - seul `ExposureDetector` exclut `token.isUnverified()` ;
  - FastForwarding, CircularFlow, UnusualAmount, RapidDrain (mode estimé) et FanOut les prennent en compte
    (`FastForwardingDetector.java:29-36`, `CircularFlowDetector.java:86-89`, `UnusualAmountDetector.java:36-37`) ;
  - les jetons inconnus ont 0 décimale (`TokenRegistry.java:21-22`).
- **Effet :** n'importe qui peut fabriquer `victime → X → victime` sur un contrat sans valeur et produire des signaux
  `CIRCULAR_FLOW`, `FAST_FORWARDING` et `UNUSUAL_AMOUNT` contre une adresse tierce. Cela fait environ 25 à 30 points de
  score et des alertes [R pour le chiffrage].
- **Correctif :** filtre commun dans `AbstractDetector`, excluant `isUnverified()` des déclencheurs et des historiques.

### B3 (latent). Âge et nombre de transactions à 0 pour d'anciens wallets évincés [V]

- **Constat :** `ageDays` renvoie `0` quand `firstActivity` est null (`ChainIngestionService.java:918-919`). C'est le cas
  dès que le profil a été évincé par le plafond de 300 000 profils (`profileOrEmpty`).
  `wallet.age_days`, `counterparty.age_days` et `wallet.tx_count` valent alors 0 (`:885-896`).
- **Effet :** toute règle « wallet jeune » se déclencherait sur un vieux wallet réapparu, avec une valeur présentée comme
  mesurée.
- **Pourquoi latent :** la période d'observation de 30 jours (`observationStart`) n'est pas persistée et repart à chaque
  redémarrage. Le défaut sortira d'un coup dès que l'application tournera 30 jours sans redémarrer.
- **Correctif :** ne pas émettre ces champs quand `firstActivity` est inconnu, comme le fait déjà
  `approval.spender_age_days` (`:863-864`).

## 3. Importants

### Exactitude des résultats

| # | Constat | Où | Statut |
|---|---|---|---|
| I1 | Retrait d'alerte dépendant du cache de déduplication en mémoire : après redémarrage (ou éviction du LRU de 200 000 / 24 h), un événement retiré par réorganisation laisse l'alerte `VALID`, sans `alert.retracted` | `RuleEngine.java:161-164`, `AlertingPipeline.java:39` | [V] |
| I2 | Notification de retrait réinjectée comme événement **non retiré** par le connecteur interne (`removed=false` codé en dur) : les règles de corrélation ne sont jamais invalidées, et peuvent créer une alerte dérivée | `InternalEventConnector.java:50` | [V] |
| I3 | Retrait sur agrégation (SUM, COUNT) : le seuil n'est pas réévalué, et l'état de preuve ne porte que sur les 50 derniers identifiants | `SlidingWindow.java:73`, `AlertService.java:160` | [R] |
| I4 | Réinclusion d'une preuve après réorganisation jamais notifiée (pas d'événement `alert.reinstated`) | `AlertService.java:185-194` | [R] |
| I5 | État du moteur (déduplication, fenêtres) modifié avant la transaction et jamais annulé : alerte perdue si la transaction échoue puis que l'événement est rejoué | `RuleEngine.java:114`, `ChainIngestionService.java:435` | [R][?] |
| I6 | Retrait d'un gel par réorganisation : les signaux d'exposition des contreparties (jusqu'à 200) restent | `IntelligencePipeline.java:211-223` | [R] |
| I7 | Exposition indirecte : le parcours garde le flux **le plus ancien** de chaque expéditeur, ce qui produit des faux négatifs systématiques | `ExposureDetector.java:132-133, 154-157` | [R] |
| I8 | Signaux évincés réémis comme « nouveaux » (identifiants déterministes + `saveIfAbsent`) : alertes en double après chaque cycle d'éviction | `InMemorySignalRepository.java:37-38` | [R][?] |
| I9 | `/exposure` et graphe : sommes multi-jetons, filtre par symbole qui accepte un faux « USDT », pas d'indicateur de couverture ni de troncature (déjà détaillé dans `FUND_FLOW_TRACE_P0_A_EXPOSURE.md`) | `InvestigationService.java:270-294, 362` | [V] |
| I10 | Bloc absent chez un nœud en retard traité comme orphelin : annulations parasites, ou arrêt de l'indexeur (`ReorgTooDeepException`) après un basculement | `BlockIndexer.java:80, 146, 159` | [R] |
| I11 | Graphe de la console : changer de jeton, de dates ou de profondeur sur la même racine n'enlève jamais les anciens éléments ; le graphe paraît filtré sans l'être | `graph-canvas.ts:118-126` | [V] |
| I12 | Montant non nul affiché « 0 » (troncature sans indication) : une poussière d'empoisonnement d'adresse disparaît visuellement | `shared/format.ts:9` | [R] |
| I13 | Expansion du graphe sans annulation : la réponse peut se mélanger à une nouvelle racine | `investigation.ts:284-300` | [R] |
| I14 | Les adresses à historique ont un trou après chaque redémarrage, non signalé par `coverage()` | `AddressHistoryService.java:140, 250-257` | [R] |

### Disponibilité et performance

| # | Constat | Où | Statut |
|---|---|---|---|
| I15 | Appel RPC `isContract` **sous le verrou global** de traitement : un quota TronGrid épuisé fige aussi Ethereum | `ChainIngestionService.java:343, 852-861` | [V] |
| I16 | Retrait d'un flux en O(tous les flux), puis rejeu jusqu'à 500 × 10 détecteurs, sous le verrou global : une réorganisation sur un wallet très actif fige l'ingestion | `InMemoryFlowStore.java:61-75`, `IntelligencePipeline.java:299-310` | [V] |
| I17 | Une `Error` (manque de mémoire) arrête **sans bruit** une tâche planifiée, y compris la purge, qui est la soupape mémoire | `Bootstrap.java:499-503` | [V] |
| I18 | Envois de notifications séquentiels sous verrou : un webhook en trou noir retarde Telegram et Slack de plusieurs minutes | `DeliveryService.java:104` | [V] |
| I19 | Erreurs JSON-RPC renvoyées en HTTP 200 (par exemple -32005, limite de débit) traitées comme succès par retry, disjoncteur et basculement | `JsonRpcClient.java:39-46`, `HttpJsonTransport.java:126-127` | [R] |
| I20 | `TronGridProvider.getBlock` appelé hors indexeur déclenche préchargements et appels lourds et vole le memo de l'indexeur : quota TronGrid épuisé | `TronGridProvider.java:159-162, 205, 400, 413` | [R] |
| I21 | Réponses HTTP lues sans limite de taille | `HttpJsonTransport.java:109` | [R] |
| I22 | Limitation de débit TronGrid (HTTP 403) possiblement classée comme permanente | `HttpJsonTransport.java:123-128` | [?] |
| I23 | Un échec de la restauration du journal empêche la reprise des historiques et la purge | `Bootstrap.java:139-143, 388-404` | [R] |
| I24 | `GET /alerts` et le tableau de bord chargent **toutes** les alertes et tout leur historique, puis paginent en mémoire ; aucune rétention des alertes ni des livraisons | `AlertFacade.java:73`, `JdbcAlertRepository.java:125, 150-162` | [V] |
| I25 | Plafonds mémoire du moteur de règles par règle (pas de plafond global) ; l'éviction LRU des clés de groupe est exploitable pour échapper à un seuil | `RuleEngine.java:348-355`, `EngineConfig.java:29` | [R] |
| I26 | Tolérance de retard unique (2 min) pour tous les réseaux, et horodatage futur non borné qui fait avancer la référence de temps | `EngineConfig.java:29`, `RuleEngine.java:107, 113` | [R] |

### Sécurité et exploitation

| # | Constat | Où | Statut |
|---|---|---|---|
| I27 | Audience OIDC facultative : sans `audience`, seul l'émetteur est vérifié (jeton d'une autre application du même IdP accepté) | `SecurityConfiguration.java:130-137` | [V] |
| I28 | `PATCH /users/{id}` (rôle) sans effet sur les droits réels : `UserDirectory` n'est consulté par aucune règle de sécurité ; fausse assurance d'autorisation | `AdminController.java:110-120` | [V] |
| I29 | Clés API statiques de configuration sans expiration, et jamais révoquées quand on les retire de la configuration | `ApiKeyService.java:101-104, 157-158` | [R] |
| I30 | Console et proxy API publiés sur **toutes les interfaces** en HTTP (`"4200:8080"`), alors que l'API seule est limitée à 127.0.0.1 | `docker-compose.yml:114` | [V] |
| I31 | `deploy/smoke-test.sh` reconstruit les images **sous les tags de production** : sur cette machine, le redémarrage suivant lancerait la branche en cours | `smoke-test.sh:28` + tags fixes de `docker-compose.yml` | [V] |
| I32 | Actions CI épinglées par tag (pas par SHA) pour `checkout`, `setup-java`, `setup-node`, `upload-artifact` et `codeql` ; `npx license-checker` hors lockfile | `ci.yml:22-151, 84` | [R] |
| I33 | `Bearer oci_…` probablement refusé quand OIDC est actif (le filtre JWT passe avant le filtre clé API) | `ApiKeyAuthenticationFilter.java:71`, `SecurityConfiguration.java:65` | [?] |
| I34 | Démo non isolée de la production sur la même base (`pgdata`) : historiques et journal restaurés sans vérifier le mode | `AddressHistoryService.java:123-126` | [R] |
| I35 | La liste de surveillance modifie la portée des règles comportementales sans audit (`watchlist:write` est un droit d'analyste) | `WatchlistController.java` | [R] |
| I36 | Clé API et jeton OIDC en `localStorage`, sans expiration | `settings.service.ts:12, 25, 39` | [R] |

## 4. Mineurs (résumé)

**Fiabilité :**
- disjoncteur bloqué en HALF_OPEN sur une `Error` ;
- `Retry-After` plafonné à 5 s ;
- `eth_getBlockReceipts` désactivé pour toujours après un seul nœud de secours ;
- données TronGrid non défendues (un NPE fait échouer la page) ;
- cache `isContract` faux pour un bloc historique ;
- réinclusion EVM rarement reconnue (le `logIndex` change) ;
- transitions d'alerte en plusieurs transactions ;
- upsert des livraisons sans contrôle du propriétaire du bail ;
- `FreezeBackfillService` bloqué en RUNNING sur une `Error` ;
- `trackedWallets` et `lastViewed` non bornés.

**Qualité des données :**
- seuil anti-poussière nul pour les jetons sans seuil configuré ;
- clés de groupe des règles jointes par `|` sans échappement ;
- 500 identifiants d'événements au plus par alerte ;
- seuil COUNT supérieur à `maxEntriesPerKey` accepté sans avertissement ;
- dates du graphe interprétées en UTC sans le dire.

**Exploitation :**
- `If-Match` non exigé, alors qu'un ETag est servi ;
- création et mise à jour de règles non auditées ;
- champs de signalement sans longueur maximale ;
- `/actuator/prometheus` ouvert à toute adresse privée ;
- nginx garde l'IP de `app` résolue une seule fois ;
- images de base sans digest ;
- mots de passe par défaut faibles (Postgres, Grafana) ;
- `cancel-in-progress` actif sur `main`.

**Corrigé pendant la revue :** `deploy/backup/pg-backup.sh` (non commité) pouvait jeter un dump valide à cause du
couple `pipefail` + `grep -q` (SIGPIPE). Corrigé et retesté : dump de 436 Ko produit à 12 h 43.

## 5. Forces

- **Sécurité de base solide :**
  - clés API HMAC + pepper, comparaison en temps constant ;
  - secrets chiffrés AES-GCM avec AAD et identifiant de clé ;
  - SSRF complet : IP vérifiée, pas de rebinding DNS, pas de redirection ;
  - signature des webhooks correcte ;
  - SQL entièrement paramétré ;
  - erreurs 5xx génériques ;
  - en-tête CSP strict, contrôlé en CI ;
  - conteneurs non-root, en lecture seule, avec `cap_drop`.
- **DSL de règles sûr :** pas d'exécution de code, YAML durci, regex avec budget de pas.
- **Arithmétique exacte :** `BigInteger`/`BigDecimal` partout, montants manipulés en chaînes côté frontend.
- **Explicabilité :** preuves par signal, attribution marginale du score, textes en langage clair, anti-poussière
  explicite pour l'exposition.
- **Mémoire bornée** (étape 3b), accumulateurs compacts documentés, comptage de toutes les évictions.
- **Isolation des secrets du transport :** une clé n'est jamais envoyée à un autre point d'accès.

## 6. Tests manquants (prioritaires)

1. Réorganisation au passage suivant avec le stockage PostgreSQL (B1).
2. Flux de faux jeton dans chaque détecteur comportemental (B2).
3. Âge et nombre de transactions d'un wallet évincé (B3).
4. Retrait après redémarrage ; retrait via le connecteur interne ; retrait sur agrégation ; réinclusion notifiée.
5. Réorganisation d'un gel avec contreparties exposées.
6. Coût de `remove` et `replay` à grande échelle ; RPC indisponible pendant un enrichissement d'approbation.
7. OIDC activé (audience, `Bearer oci_…`).
8. Graphe de la console : changement de filtre sur la même racine ; aucun spec pour `investigation`, `graph-canvas`,
   `wallet`, `settings`.

## 7. Ordre de correction proposé

| Priorité | Lot | Contenu |
|---|---|---|
| 1 | Exactitude | B1 (réorganisations en PostgreSQL), B2 (faux jetons), B3 (âge 0), I1-I2 (retraits d'alertes), I11 (graphe) |
| 2 | Disponibilité | I15 (RPC hors verrou), I17 (`Throwable` dans `guard`), I16 (retrait en O(1)), I18 (envois parallèles par connecteur), I24 (pagination SQL) |
| 3 | Sécurité et exploitation | I27 (audience obligatoire), I28 (rôles effectifs ou 501), I30 (port 4200 sur 127.0.0.1), I31 (tags du smoke-test), I32 (SHA des actions), I29 (clés de configuration) |
| 4 | Fournisseurs | I19-I22 (erreurs JSON-RPC, TronGrid, tailles de réponse), I10 (nœud en retard) |
| 5 | Reste | importants restants, puis mineurs |

Chaque correction s'accompagne de son test (§ 6). Aucune n'est faite sans validation.
