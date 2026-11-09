# Risques techniques et de sécurité

> Probabilité / impact : **H** élevé · **M** moyen · **B** bas. « Constaté » = observé sur l'instance réelle ou dans le
> code, pas supposé.

## 1. Données blockchain

| Risque | P / I | Constat | Mitigation |
|---|---|---|---|
| Nœud non-archive : historique limité | H / H | **constaté** : PublicNode refuse `eth_getLogs` au-delà d'environ 10 000-20 000 blocs (~2 jours) | arrêt propre et message (fait le 2026-11-08) ; Cloud avec archive ; source Erigon `ots_` (ADR-0020 étape 2) |
| Latence et quotas des fournisseurs | H / M | **constaté** : TronGrid ~0,8 s par appel ; TRON décrochait | mémo de bloc, préchargement, budgets (faits) ; failover à câbler (D13) |
| Transactions internes non indexées | H / M | **constaté** : flux contractuels invisibles pour exposition et drain | traces (`debug_trace*`, `trace_block`) en option ; limite affichée |
| Pas de solde historique sur TRON | H / M | **constaté** : wallet-draining inactif sur TRON réel | affichage explicite (0.4) ; étude d'approximation marquée |
| Réorganisations | B / M | gérées (fenêtre, rétractations, tests) | conserver |
| Perte de l'état au redémarrage | H / H | **constaté** (D2) | ADR-0024 |

## 2. Qualité de l'intelligence

| Risque | P / I | Constat | Mitigation |
|---|---|---|---|
| Faux positifs massifs | H / H | **constaté** : ≈ 3 900 alertes ouvertes en 3 jours sur 3 règles | mesure par règle, seuils, exclusions, vérité terrain (0.10, phase 1) |
| Attribution insuffisante | H / H | aucun label réel livré | sources ouvertes, TagPacks, labels internes ; positionnement en complément |
| Association probabiliste perçue comme identité (diffamation, préjudice) | M / H | — | niveaux épistémiques (ADR-0023), avertissement sur chaque notification (déjà présent), vocabulaire « comportement », jamais « criminel » |
| Prédictions non évaluées | M / M | — | expiration + taux de réalisation publié |
| IA qui invente des faits | M / H | — | IA limitée à la rédaction à partir d'assertions identifiées ; test « aucune affirmation hors assertions » |

## 3. Sécurité applicative

| Risque | P / I | Constat | Mitigation |
|---|---|---|---|
| Défauts de déploiement (démo par défaut, 8080 exposé, Swagger public, Grafana `admin`) | M / H | **constaté** (D9) | 0.7 |
| Pas de rate limiting / idempotence | M / M | **constaté** (D7) | phase 1 |
| Jeton console en `localStorage` (exposé à un XSS) | M / H | **constaté** (D8) ; CSP stricte en atténuation | OIDC PKCE, jetons courts |
| Rôles sans effet | M / M | **constaté** | phase 1 |
| Pas de rotation des secrets ni de la clé maître | M / M | TODO `WebhookRegistry:44` | procédure de rotation |
| Pas de journal d'audit persistant | M / M | TODO `ApiKeyService:37` | table `audit_log` |
| SSRF via webhooks | B / H | garde SSRF en place et testée | conserver |
| Injection de prompt via données on-chain (noms de tokens, mémos) dans un MCP/LLM | M / H | — | données renvoyées typées, jamais interprétées comme instructions ; documenté ; filtrage de longueur |
| Agent avec trop de permissions | M / H | — | scopes par outil MCP, écriture opt-in, audit |
| Chaîne d'approvisionnement (dépendances npm/Maven) | M / H | CodeQL en CI ; vérification de licences non bloquante | bloquante (0.12), Dependabot/Renovate, SBOM |

## 4. Exploitation et échelle

| Risque | P / I | Constat | Mitigation |
|---|---|---|---|
| Mono-instance | M / H | par conception | HA en Enterprise ; claim d'outbox déjà multi-instance |
| Croissance des données (~290 000 événements en 3 jours, couverture partielle) | H / M | **constaté** | partitionnement, rétention, seuil de bascule ClickHouse (ADR-0004) |
| Pas de métriques exportées | H / M | **constaté** (D10) | 0.9 |
| Notifications perdues silencieusement | H / H | **constaté** : 5 675 dead-letters | 0.1 + alerte sur le taux de dead-letters |

## 5. Juridique et conformité

| Risque | Mitigation |
|---|---|
| Données personnelles dans les alertes et commentaires (RGPD) | rétention configurable, export/suppression, base protégée (déjà noté dans `security-hardening.md`) |
| Conditions de réutilisation des listes externes | vérification par source avant redistribution |
| Usage par des acteurs malveillants (contourner la détection en lisant les règles) | assumé : la sécurité par l'obscurité n'est pas notre modèle ; les seuils sont configurables par instance |
