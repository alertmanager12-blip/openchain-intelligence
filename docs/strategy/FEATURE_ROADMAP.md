# Feuille de route

> Complexité : **S** ≤ 3 j · **M** ≤ 2 sem · **L** ≤ 6 sem · **XL** > 6 sem (une personne expérimentée, ordre de
> grandeur, à recalibrer après la phase 0). Chaque phase se termine par : compilation, tests verts (0 sauté),
> test de fumée, mise à jour de la doc, validation explicite avant la phase suivante.

## Vue d'ensemble

| Phase | Objectif | Complexité | Dépend de |
|---|---|---|---|
| 0 | Audit et consolidation : rien d'affiché qui ne marche pas | L | — |
| 1 | Intelligence core fiable : persistance, bruit mesuré, sources réelles | XL | 0 |
| 2 | Graph intelligence : traçage, chemins, relations | L | 1 (flux persistés) |
| 3 | Investigation autonome reproductible | XL | 1, 2 |
| 4 | Signaux prédictifs et simulation | L | 1, 3 |
| 5 | Écosystème agents : MCP, SDK | M | 0 (contrat API), 3 pour les outils d'investigation |
| 6 | Intelligence collaborative | L | 1 (labels avec provenance) |

**Recommandation d'ordonnancement** : un **MCP minimal en lecture seule** (portefeuille, risque, alertes) peut être
livré dès la fin de la phase 1 (complexité S-M), parce que c'est un prérequis du marché (cinq concurrents en ont un)
et qu'il réutilise l'API. *Décision à valider.*

---

## Phase 0 — Audit et consolidation

**But** : supprimer tout écart entre ce qui est affiché et ce qui marche ; rendre l'instance réelle exploitable.

| # | Tâche | Défaut | Modules | Critère de réussite | Complexité |
|---|---|---|---|---|---|
| 0.1 | Routage par défaut des notifications : remplacer `default-webhook` par un connecteur configurable (`OCI_DEFAULT_CONNECTOR`) ; à défaut, ne **pas** créer de livraison vouée à l'échec et l'afficher clairement (alerte « non notifiée : aucun connecteur ») ; requeue des dead-letters après configuration | D1 | alerting, app, rules | 0 nouveau dead-letter « unknown connector » en production ; test d'intégration | S |
| 0.2 | Désactiver par défaut `contract-exploit` (ne peut pas se déclencher) et marquer « nécessite CONTRACT_CALL » ; refuser l'activation d'une règle dont un champ n'est jamais produit (avertissement bloquant ou explicite) | D4 | alerting, rules | aucune règle active muette ; test du validateur | S |
| 0.3 | Onglet Transactions EVM : servir les transactions depuis les flux indexés + historique d'adresse au lieu du 503 ; message clair sinon | D5 | app, frontend | onglet fonctionnel sur Ethereum réel ; test | S |
| 0.4 | TRON sans solde historique : afficher dans l'UI et l'API que `wallet-draining`/RapidDrain sont inactifs sur TRON réel ; étudier le solde courant comme approximation **marquée** (ADR à part si retenu) | D6 | app, frontend, docs | plus de silence non expliqué | S |
| 0.5 | `isContract` réel (code EVM via `eth_getCode` déjà en cache ; TRON `getcontract`) ou champ retiré | D11 | app | aucune valeur codée en dur | S |
| 0.6 | Retirer Bitcoin/Solana de l'UI et du mock (ou marquer « non supporté ») | D12 | frontend | aucune option sans fournisseur | S |
| 0.7 | Défauts de déploiement : `OCI_DEMO_MODE=false` par défaut hors `docker compose --profile demo` ; publier 8080 sur 127.0.0.1 ; Swagger configurable (désactivé en prod par défaut) ; mot de passe Grafana obligatoire | D9 | deploy | test de fumée adapté ; checklist sécurité à jour | S |
| 0.8 | Câbler `FailoverProvider` (liste d'URL par chaîne) | D13 | app | bascule testée en intégration | S |
| 0.9 | Observabilité minimale : Micrometer Prometheus (dépendance justifiée : endpoint déjà attendu par la stack compose), métriques d'indexation (retard, blocs/s, requêtes RPC, erreurs), d'alertes et de livraisons | D10 | app, deploy | `/actuator/prometheus` scruté, tableau Grafana fourni | M |
| 0.10 | Mesure du bruit : rapport par règle (alertes/jour, % classées faux positif) exposé dans l'API et la console ; seuils du pack revus (`fund-splitting`, `suspicious-wallet`, `suspicious-approval`) avec exclusions documentées | D3 | alerting, app, rules | volume d'alertes ouvertes/jour divisé par ≥ 10 sur l'instance réelle, sans désactiver de règle | M |
| 0.11 | Aligner doc et code : Java 21 compile / 25 runtime, Node, JaCoCo (ajouter ou retirer la promesse), V5 dans `data-model.md`, exemple de risque, ids, Swagger ; contrat API : implémenter **ou** marquer « non implémenté » rate limiting, Idempotency-Key, If-Match, keyset | D7, D15 | docs, app | 0 écart dans la liste de l'audit | M |
| 0.12 | Licences : vérification bloquante en CI (Maven + npm) | D16 | CI | job rouge sur licence interdite | S |
| 0.13 | Connecteurs : câbler EMAIL (SMTP configurable) ; supprimer PUSH sans transport | D14 | alerting, app | test SMTP (GreenMail ou serveur local) ; plus de code mort | M |
| 0.14 | Implémenter ou retirer les 6 `SignalType` jamais émis | — | intelligence | aucune énumération morte | S |

**Livrables** : défauts D1-D16 fermés ou explicitement documentés ; rapport de bruit avant/après ; CHANGELOG.
**Risques** : 0.10 peut révéler que certaines règles du pack sont structurellement bruyantes sur mainnet → les retirer
du pack « actif par défaut » plutôt que de cacher le bruit.
**Tests** : unitaires pour chaque correctif ; intégration (Testcontainers) pour 0.1, 0.3 ; test de fumée étendu
(routage des notifications, onglet transactions).

---

## Phase 1 — Intelligence core fiable

| Fonctionnalité | Modules | Critère d'acceptation | Complexité |
|---|---|---|---|
| Persistance de l'état dérivé (checkpoints, flux, profils, signaux, scores, historique pour dry-run) — ADR-0024 | app, intelligence, blockchain | redémarrage sans perte ni re-indexation ; reprise au bloc suivant ; test de redémarrage en CI | XL |
| Labels avec provenance complète (source, fiabilité, preuve, expiration, révocation) + import à chaud (API admin) | intelligence, app | chaque label affiché avec sa source et sa date ; révocation effective | M |
| Sources réelles : OFAC SDN (adresses crypto), gels d'émetteur (déjà), TagPacks GraphSense, listes partenaires (CSV) | app | jeu de sanctions à jour quotidiennement, date de mise à jour affichée | M |
| Version du moteur et niveau de preuve sur **toutes** les sorties (signal, alerte, score) | core, intelligence, alerting | champ présent et testé partout | S |
| API de décision « screening » : `POST /screening` (adresse ou transaction → verdict + raisons + limites), latence cible p95 < 300 ms sur données en base | app | contrat OpenAPI + test de charge local | M |
| Rate limiting et Idempotency-Key réels | app | 429 + en-têtes ; rejeu idempotent | M |
| Login OIDC dans la console (PKCE) + rôles effectifs | frontend, app | plus de clé en `localStorage` en mode OIDC ; rôle VIEWER ne peut pas écrire | M |
| Jeu de vérité terrain : cas étiquetés (gels confirmés par l'émetteur, adresses OFAC, incidents publics) pour mesurer précision/rappel des règles | intelligence, docs | tableau de bord qualité publié | L |

**Risques** : volumétrie PostgreSQL (partitionnement), coût des sources de labels, licence des listes (vérifier les
conditions de réutilisation de chaque source).

---

## Phase 2 — Graph intelligence

| Fonctionnalité | Critère d'acceptation | Complexité |
|---|---|---|
| Traçage des fonds amont/aval sur flux persistés, > 3 sauts avec budget (nœuds, temps) et arrêt sur service identifié | résultat paginé, `truncated` explicite, temps borné | L |
| Recherche de chemins entre deux adresses | chemins avec montants et dates, sources | M |
| Relations entre portefeuilles (co-financement, fan-in/fan-out commun, réutilisation de contreparties) en **inférences** avec confiance et justification | chaque relation porte son niveau `INFERRED` et ses preuves | L |
| Visualisation interactive (Cytoscape existant) : filtres temps/token, expansion à la demande, export (PNG existant + JSON) | utilisable sur 500 nœuds | M |

**Risque** : faux liens (adresses de service, routeurs) → exclusions par labels de service ; jamais d'identité.

---

## Phase 3 — Investigation autonome (ADR-0022)

| Fonctionnalité | Critère d'acceptation | Complexité |
|---|---|---|
| Objet investigation persistant, exécution en arrière-plan, interruption/reprise | reprise après redémarrage testée | L |
| Plan d'investigation déterministe (historique → contreparties → chemins → signaux → labels → corrélation) | même entrée + mêmes données = même résultat (test de rejouabilité) | L |
| Rapport structuré (JSON + PDF/HTML) : assertions typées, preuves, sources, limites, version | aucun énoncé sans provenance | M |
| Validation humaine des assertions (accepter/rejeter, commentaire) | journalisée | M |
| Règles proposées à partir d'une investigation (brouillon YAML, jamais activé automatiquement) | brouillon valide, testé par dry-run | M |
| Assistance IA *optionnelle* : résumé rédigé à partir des seules assertions, citant leurs identifiants | aucune affirmation hors assertions (test) | M |

---

## Phase 4 — Signaux prédictifs et simulation

| Fonctionnalité | Critère d'acceptation | Complexité |
|---|---|---|
| Signaux précurseurs : dispersion inhabituelle, réveil après dormance, multiplication de nouvelles contreparties, changement de stratégie, mouvements coordonnés | chaque signal = hypothèse `PREDICTED` avec confiance, signaux sources, limites, **expiration** | L |
| Évaluation a posteriori des prédictions (réalisée / non réalisée à expiration) | taux publié par type | M |
| Simulation de règles sur historique persistant (extension de `RuleTester`) : impact d'une modification de règle | rapport avant/après sans effet de bord | M |
| What-if graphe : « qui serait exposé si X était étiqueté sanctionné ? » | résultat marqué `SIMULATED`, jamais mélangé aux faits | M |

---

## Phase 5 — Écosystème agents (ADR-0025)

| Fonctionnalité | Critère d'acceptation | Complexité |
|---|---|---|
| Serveur MCP (lecture puis écriture), mêmes scopes que l'API | test d'autorisation par outil | M |
| SDK Java / Python / TypeScript générés depuis l'OpenAPI, publiés | tests de contrat générés | M |
| Règles et alertes « as code » : export/import complet de configuration (règles, connecteurs sans secrets) | aller-retour sans perte | S |
| Webhooks : catalogue d'événements versionné (déjà signé) | doc + exemples de vérification | S |

---

## Phase 6 — Intelligence collaborative

| Fonctionnalité | Critère d'acceptation | Complexité |
|---|---|---|
| Modération des signalements (file, preuves, décision journalisée) | aucun signalement publié sans décision | M |
| Échange de labels/IOC au format TagPack (import/export) avec provenance | interopérable avec GraphSense | M |
| Réputation des contributeurs et révocation | label révoqué retiré partout | M |
| Partage d'investigations (export signé) et de règles (pack communautaire versionné) | vérification de signature | M |

**Règle** : une information communautaire non vérifiée est toujours affichée comme telle.
