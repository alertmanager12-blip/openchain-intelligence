# Analyse des écarts : existant → cible

> Audit en lecture seule du dépôt à `44c0990` (2026-11-09) + mesures sur l'instance de production locale (Ethereum
> mainnet via PublicNode, TRON via TronGrid, PostgreSQL). Chaque constat renvoie au code ; les points marquants ont été
> revérifiés à la main.

## 1. État réel du projet

| Domaine | Volume | Tests |
|---|---|---|
| `oci-core` (modèle, SPI fournisseurs, signaux, risque) | 23 fichiers / 731 lignes | 1 classe |
| `oci-blockchain` (TRON, EVM, indexeur, transport résilient, backfill de gels) | 40 / 3 547 | 16 classes |
| `oci-intelligence` (10 détecteurs, profils, labels, scoring) | 42 / 2 316 | 9 classes |
| `oci-alerting` (moteur de règles YAML, alertes, outbox, 4 connecteurs actifs) | 70 / 5 086 | 14 classes |
| `oci-app` (API REST, sécurité, persistance, ingestion, historique d'adresse) | 74 / 8 435 | 24 classes |
| `frontend` (Angular 22, Material) | ~10 000 lignes | 22 fichiers de specs |

Build : `mvn clean verify` 474 tests, 0 sauté (Testcontainers PostgreSQL exécutés) ; frontend 130 tests.

**Mesures de production (instance réelle, ~3 jours)** :

| Indicateur | Valeur |
|---|---|
| Événements indexés / transferts | 289 349 / 283 787 |
| Portefeuilles suivis | 178 678 |
| Alertes totales / ouvertes | 6 309 / 4 065 |
| Alertes par règle (hors faux positifs classés) | fund-splitting ≈ 1 970, suspicious-wallet ≈ 1 380, suspicious-approval ≈ 580, large-usdt-transfer 79, wallet-draining 54 (depuis le seuil 1.2.0) |
| Notifications livrées / en échec définitif | 1 461 / **5 675** (`unknown connector 'default-webhook'`) |
| Retard d'indexation | Ethereum à jour ; TRON à jour après correctifs du 2026-11-07 |

## 2. Forces

1. **Ingestion réelle robuste** : TRON (TronGrid) et EVM (JSON-RPC) avec gestion des réorganisations, confirmations,
   retry/backoff, disjoncteur, limiteur de débit, threads virtuels, budget de requêtes de solde.
2. **Couverture stablecoin spécifique** : transferts USDT/USDC, natifs, approbations, **gels/dégels d'émetteur**
   (sources officielles Tether/Circle) + backfill historique des gels.
3. **Explicabilité déjà en place** : chaque contribution de risque expose facteur, source, poids, confiance, âge,
   points, niveau de preuve et phrase lisible ; méthode et version du modèle (`oci-risk-1`).
4. **Moteur de règles déclaratif sérieux** : schéma JSON, compilation, fenêtres glissantes en temps d'événement,
   séquences, déduplication, rétractation sur réorganisation, dry-run, tests embarqués dans chaque règle, regex
   protégée contre le ReDoS.
5. **Sécurité de base saine** : clés API HMAC + poivre, scopes par opération, secrets des connecteurs chiffrés
   (AES-256-GCM), garde SSRF sur les webhooks, signature HMAC des webhooks, CSP stricte, conteneurs non-root
   en lecture seule.
6. **Distinction des niveaux de preuve** amorcée : `EvidenceLevel` = ANOMALY / RISK_SIGNAL / CONFIRMED_BY_SOURCE,
   validée par le moteur de règles.

## 3. Écarts par pilier de la cible

### A. Autonomous Investigation Engine — **largement absent**
| Existant | Manque |
|---|---|
| Analyse de portefeuille (profil, flux, contreparties, signaux, risque, couverture d'historique) ; exposition BFS ≤ 3 sauts ; graphe BFS ≤ 3 sauts / 500 nœuds ; historique d'adresse à la demande, asynchrone, reprenable (ADR-0020) | objet « investigation » persistant ; exécution en arrière-plan d'un plan multi-étapes ; corrélation inter-signaux ; rapport structuré exportable ; proposition de règles ; rejouabilité ; validation humaine |

### B. Living Wallet Intelligence — **partiel**
| Existant | Manque |
|---|---|
| profils (premier/dernier vu, volumes, contreparties) ; détecteurs anomalie de montant, dormance, fast-forwarding, fan-out, flux circulaires | profils **en mémoire seulement** (perdus au redémarrage) ; base comportementale courte (indexation depuis tête-100) ; aucune relation entre portefeuilles (clustering, similarité) ; `isContract` toujours `false` (`InvestigationService.java:166,169,345`) |

### C. Predictive Threat Intelligence — **absent**
Aucun objet « prédiction » ; six `SignalType` déclarés jamais émis (REPETITIVE_TRANSFERS, BASELINE_DEVIATION,
ADDRESS_PROLIFERATION, DANGEROUS_ADMIN_PERMISSION, MINT_CAPABILITY, MALICIOUS_CONTRACT_INTERACTION).

### D. Threat Simulation — **embryonnaire**
`RuleTester` dry-run (règle sur événements passés, sans effet de bord) existe mais ne lit que `EventHistory`
(100 000 événements **en mémoire**). Aucune simulation de chemins, d'exposition hypothétique ou d'impact d'une
nouvelle information de menace.

### E. Explainable Intelligence — **présent pour le score, incomplet ailleurs**
Manquent : contradictions et limites explicites dans la réponse ; version du moteur sur les alertes et signaux (pas
seulement le score) ; sources de données nommées (quel nœud, quelle couverture) ; niveau épistémique
observé/inféré/prédit/confirmé.

### F. Collaborative Intelligence — **embryonnaire**
`POST /labels/reports` stocke en PENDING, **jamais modéré ni publié** (`LabelReportStore.java:12`) ; aucun import de
labels à chaud ; aucun jeu de labels réel livré (5 entrées fictives de démo) ; pas de réputation ni de révocation.

### G. Agent-Native — **partiel**
API REST versionnée (`/api/v1`), OpenAPI générée (springdoc), webhooks signés, règles en code (YAML import/export).
Manquent : serveur MCP, SDK, et une partie du **contrat API documenté non implémenté** (voir § 4).

## 4. Défauts et écarts à corriger (phase 0)

Classés par impact. ✅ = revérifié à la main.

| # | Défaut | Impact | Preuve |
|---|---|---|---|
| D1 | **Toutes les règles du pack routent vers `default-webhook`**, qui n'existe qu'en mode démo → notifications perdues | critique : 5 675 alertes jamais notifiées en production ✅ | `rules/*/*.yaml`, `DeliveryService:75-76` |
| D2 | **État indexé non persistant** : flux, profils, signaux, scores, points de reprise de l'indexeur, historique pour dry-run, labels | critique : redémarrage = perte d'intelligence, reprise depuis le bloc de départ | `ModulesConfiguration:57-66`, `ChainIngestionService:164` (`InMemoryCheckpointStore`) |
| D3 | **Bruit d'alertes** : fund-splitting, suspicious-wallet, suspicious-approval ≈ 3 900 alertes ouvertes en 3 jours | critique pour l'adoption (un PSP ne peut pas trier ça) ✅ | base de production |
| D4 | `contract-exploit` livrée **active mais ne peut jamais se déclencher** (CONTRACT_CALL et `contract.outflow_ratio` jamais produits) | trompeur ✅ | `rules/ethereum/contract-exploit.yaml:7`, `PopulatedFields` |
| D5 | Onglet « Transactions » du portefeuille **toujours en erreur sur EVM réel** (`getAddressTransactions` lève une exception → 503) | fonctionnalité affichée cassée ✅ | `EvmJsonRpcProvider.java:151-154` |
| D6 | `wallet-draining` et RapidDrain **ne se déclenchent jamais sur TRON réel** (pas de solde historique TronGrid) | silencieux pour l'utilisateur ✅ | `TronGridProvider` sans `getBalanceAt` |
| D7 | Contrat API documenté non implémenté : rate limiting, `Idempotency-Key`, `If-Match`/412, curseurs keyset, tenant | écart doc/code | `ApiException:41`, `RuleController:186`, `CursorPage:9` |
| D8 | Console sans login OIDC : clé collée et stockée en `localStorage` ; rôles utilisateurs sans effet sur l'autorisation | sécurité / UX | `settings.service.ts:16,25`, `UserDirectory:12` |
| D9 | Défauts de déploiement risqués : `OCI_DEMO_MODE=true` par défaut, port 8080 exposé sur toutes les interfaces, Swagger public, mot de passe Grafana `admin` | sécurité | `docker-compose.yml` |
| D10 | Observabilité annoncée mais absente : Prometheus scrute `/actuator/prometheus` inexistant ; pas d'OpenTelemetry | exploitation | `installation.md:53` |
| D11 | `isContract` toujours `false` dans profil, DTO et graphe | donnée fausse affichée ✅ | `InvestigationService.java:166,169,345` |
| D12 | Bitcoin/Solana dans l'enum et le mock frontend, sans fournisseur | confusion | `core/models.ts:7-8`, `Bootstrap.startRealChain` |
| D13 | `FailoverProvider` testé mais non branché (un seul point d'accès RPC) | disponibilité | `Bootstrap:293` |
| D14 | `EmailConnector`, `PushConnector` non câblés | code mort ou à câbler | `WebhookRegistry` |
| D15 | Écarts doc/code : Java 21 vs runtime 25, Node 22 vs 24 en CI, JaCoCo et e2e nocturne annoncés mais absents, V5 non documentée, exemple de risque obsolète | crédibilité | `testing-strategy.md`, `data-model.md`, `docs/api/README.md` |
| D16 | Vérification des licences en CI non bloquante | conformité | `.github/workflows/ci.yml` |

## 5. Ce qui peut être conservé, amélioré, remplacé ou supprimé

| Composant | Décision proposée | Raison |
|---|---|---|
| Transport résilient, adaptateurs TRON/EVM, indexeur à fenêtre de réorg | **conserver** | éprouvés en production réelle |
| Moteur de règles, schéma, RuleTester | **conserver**, étendre (simulation) | cœur du différenciateur « règles ouvertes » |
| RiskScoringEngine | **conserver**, ajouter niveau épistémique et version sur toutes les sorties | déjà explicable |
| Détecteurs | **améliorer** : seuils réglables, mesure du bruit, base comportementale persistante | bruit mesuré |
| Stores en mémoire de l'état indexé | **remplacer** par PostgreSQL (ADR-0024 proposé) | D2 |
| `InMemoryCheckpointStore` en production | **remplacer** par un store JDBC | D2 |
| Graphe BFS en mémoire | **améliorer** (persistance des flux, pagination, multi-sauts > 3 avec garde-fous) ; ADR-0005 garde PostgreSQL d'abord | phase 2 |
| `EmailConnector` / `PushConnector` | **câbler EMAIL** (demande fréquente des PSP), **supprimer PUSH** s'il n'a pas de transport réel | pas de code mort |
| `FailoverProvider` | **câbler** (plusieurs URL RPC) | D13 |
| SignalTypes jamais émis | **implémenter ou retirer** au cas par cas | pas de promesse vide |
| Mock frontend | **conserver pour le dev**, retirer bitcoin/solana | D12 |
| Rapport d'audit PDF du 2026-11-06 | **archiver** (remplacé par ces documents) | redondance |

## 6. Hypothèses non vérifiables dans le code

- Qualité de détection réelle (précision/rappel) : aucune vérité terrain disponible ; à construire (phase 1).
- Tenue en charge au-delà d'une instance : jamais testée ; `ChainIngestionService` est mono-instance par conception.
