# Revue des ADR 0021 à 0025

Date : 2026-11-10. Objet : revue individuelle demandée avant toute acceptation. Aucune de ces ADR n'est considérée comme
acceptée tant que la décision n'est pas notée ici et dans l'ADR elle-même.

Contexte nouveau depuis leur rédaction (2026-11-09) :

- **Mesure du 2026-11-10** (ADR-0024 étapes 3a et 3b) : garder la chaîne entière en mémoire avec l'analyse complète ne
  tient pas. Avec la seule purge par l'âge, le heap a atteint 2,9 Go sur 3 Go en 3 h (269 GC complets) ; avec les
  plafonds, la mémoire est tenue mais les détecteurs ne voient plus qu'environ 2 h de flux en régime normal
  (400 000 flux pour environ 4,8 millions par jour).
- **Orientation validée sur le principe** : « périmètre client », c'est-à-dire analyse approfondie et persistée sur la
  liste de surveillance et ses contreparties, contrôle léger sur le reste de la chaîne. Conçue dans l'ADR-0026
  (proposée), sans implémentation avant validation.
- **État réel de l'instance** : liste de surveillance vide (0 adresse), 7 921 alertes en base, journal (étape 2)
  désactivé.

Légende des recommandations : **accepter**, **accepter avec amendement**, **différer** (rester « proposée »),
**remplacer**.

---

## ADR-0021 — Premier marché : surveillance auto-hébergée des rails stablecoin (TRON puis EVM)

**Décision proposée.** Concentrer le produit sur la surveillance temps réel, auto-hébergée, des stablecoins (USDT/USDC)
sur TRON puis EVM, pour fintechs, PSP, exchanges de taille moyenne. Nouvelles chaînes seulement à la demande d'un pilote.

| | |
|---|---|
| Avantages | Repose sur ce qui fonctionne déjà (indexation TRON et Ethereum, transferts USDT/USDC, approbations, gels d'émetteur). Occupe un créneau documenté : pas d'offre auto-hébergée chez Chainalysis, TRM, Elliptic, Arkham, Blockaid ; seule offre on-premises trouvée à 380 k USD/an (Breadcrumbs). Donne un critère pour refuser des fonctionnalités. |
| Inconvénients | Marché adressable plus petit qu'un généraliste. L'attribution (identité des adresses) reste le point faible, compensé seulement par des sources ouvertes. Les données de marché sont de la recherche documentaire, pas des entretiens clients. |
| Dépendances | Aucune technique. Validation commerciale : 10 à 15 entretiens et 2 à 3 pilotes (MONETIZATION_STRATEGY.md § 6). |
| Impact sur l'architecture cible | Fixe le périmètre fonctionnel de l'ADR-0026 (portefeuilles d'un PSP = liste de surveillance) et de `POST /screening`. Justifie de ne pas investir dans Bitcoin/Solana. |
| Ce qui manque pour décider | Aucun entretien client mené à ce jour. |

**Recommandation : accepter comme hypothèse de travail**, avec un critère de remise en cause explicite : si les
entretiens ne confirment pas le besoin (au moins 3 prospects prêts à piloter), l'ADR est réouverte. Elle guide déjà les
priorités que tu as fixées ; la laisser « proposée » laisse ces priorités sans fondement écrit.

---

## ADR-0022 — Investigations persistantes, reprenables, rejouables

**Décision proposée.** Un module `oci-investigation` : une investigation est un objet persisté (entrée, paramètres, plan
déterministe, version du moteur, couverture des sources, graphe figé, assertions typées, décisions humaines), exécuté de
façon asynchrone, reprenable et idempotente ; rapports générés uniquement depuis les assertions stockées.

| | |
|---|---|
| Avantages | Reproductibilité vérifiable (mêmes entrées et mêmes données, même résultat) ; rapports opposables à un auditeur ou un régulateur ; l'IA ne peut que résumer des assertions citées. |
| Inconvénients | Module et stockage nouveaux ; croissance du stockage par investigation (bornée par des budgets) ; plusieurs semaines de travail sans valeur visible avant que le screening existe. |
| Dépendances | ADR-0023 (assertions typées), ADR-0026 (données persistées du périmètre), historique d'adresse (ADR-0020). Sans flux persistés, une investigation ne peut pas être rejouée. |
| Impact sur l'architecture cible | Un module de domaine en plus ; l'isolation entre clients (ADR-0026) doit s'appliquer aux investigations dès leur création. |
| Ce qui manque pour décider | Un scénario client précis (qui lit le rapport, avec quelle obligation). |

**Recommandation : différer** (phase 3). Conserver le principe « rapport généré uniquement depuis des assertions
stockées » et l'appliquer dès maintenant aux verdicts de `POST /screening` (traçabilité, exigence 4 de l'ADR-0026).

---

## ADR-0023 — Niveaux épistémiques pour chaque assertion

**Décision proposée.** Un type `Assertion` dans `oci-core` avec un niveau parmi OBSERVED, INFERRED, PREDICTED,
CONFIRMED, SIMULATED, une confiance, des références de preuve, des sources, la version du moteur, des dates de création
et d'expiration. `EvidenceLevel` actuel : ANOMALY et RISK_SIGNAL deviennent INFERRED, CONFIRMED_BY_SOURCE devient CONFIRMED.

| | |
|---|---|
| Avantages | Un seul vocabulaire pour l'API, la console, le MCP et les rapports. Empêche de présenter une association probable comme une identité. Répond directement à tes exigences 4 (traçabilité) et 5 (distinguer « aucun signal » de « analyse complète sans risque ») si on y ajoute la couverture. |
| Inconvénients | Ajouts sur de nombreux DTO (additifs, non cassants en v1). PREDICTED et SIMULATED n'ont aujourd'hui aucun producteur : les introduire maintenant crée du vocabulaire mort (le contraire de la tâche 0.14 de la phase 0). |
| Dépendances | Aucune en amont. En aval : ADR-0022, ADR-0026 (verdicts), `POST /screening`, ADR-0025. |
| Impact sur l'architecture cible | Type transverse dans `oci-core` ; chaque sortie (signal, score, verdict) porte son niveau et sa provenance. |
| Ce qui manque pour décider | Rien de bloquant. |

**Recommandation : accepter avec amendement** : n'introduire maintenant que OBSERVED, INFERRED et CONFIRMED (ceux qui ont
des producteurs), plus un **état de couverture** distinct du niveau (voir ADR-0026 § Verdicts). PREDICTED et SIMULATED
arrivent avec leur premier producteur (phase 4).

---

## ADR-0024 — Persister l'état dérivé de la chaîne dans PostgreSQL

**État du fichier.** Le fichier indique « accepted (2026-11-09) » alors que l'acceptation n'a pas été donnée
explicitement. **À confirmer ou corriger.**

**Contenu réel.** Étape 1 (points de reprise de l'indexeur) faite et en production. Étape 2 (journal complet des flux,
signaux et scores) faite mais **désactivée** : elle ne passe pas à l'échelle (316 s de rechargement pour 50 000 flux,
environ 4,8 millions de flux par jour). Étapes 3a (purge par l'âge) et 3b (plafonds, accumulateurs compacts) faites sur
une branche non poussée, en cours de validation (`docs/ADR-0024-STEP-3B-VALIDATION.md`).

| | |
|---|---|
| Avantages | Étape 1 : reprise sans trou après redémarrage (prouvé en production). Étapes 3a/3b : mémoire tenue sans GC complet sur la mesure en cours, au lieu de 269 GC complets. |
| Inconvénients | L'idée initiale « tout persister » est contredite par la mesure : le volume d'une chaîne entière ne se persiste ni ne se recharge à ce coût. Les plafonds réduisent fortement l'historique visible des détecteurs. Le plafond des signaux ne borne pas (défaut identifié, correction proposée). |
| Dépendances | Prérequis de l'ADR-0022 (historique rejouable) et de l'ADR-0026. |
| Impact sur l'architecture cible | L'état dérivé de la chaîne entière reste **en mémoire et borné** ; ce qui doit survivre et être rejoué relève du périmètre client (ADR-0026). |

**Recommandation : accepter avec amendement** : accepter les étapes 1, 3a et 3b (cette dernière une fois validée par
la mesure) ; **remplacer** l'étape 2 et l'étape 3c par l'ADR-0026 (persistance limitée au périmètre client) ; conserver
le code du journal tant que l'ADR-0026 n'est pas implémentée, désactivé par défaut.

---

## ADR-0025 — Serveur MCP comme adaptateur fin de l'API REST

**Décision proposée.** Un serveur MCP dans `oci-app` dont les outils appellent les mêmes cas d'usage que l'API REST, avec
le **même droit par opération** ; aucune logique métier dans la couche MCP ; outils en lecture seule d'abord ; les
données de la chaîne marquées comme contenu non fiable (injection de prompt via noms de jetons ou mémos) ; SDK générés
depuis OpenAPI.

| | |
|---|---|
| Avantages | Coût faible (réutilise l'API) ; un seul modèle d'autorisation ; parité avec le marché (5 concurrents ont un MCP). |
| Inconvénients | Un transport de plus à sécuriser et tester (test d'autorisation par outil). Peu de valeur tant que l'API n'expose pas un verdict de screening exploitable. |
| Dépendances | API stable ; `POST /screening` ; ADR-0023 pour que les réponses portent leur niveau. Outils d'investigation : ADR-0022. |
| Impact sur l'architecture cible | Une façade de plus sur `oci-app`, sans nouveau domaine. Soumise à l'isolation entre clients (ADR-0026). |
| Ce qui manque pour décider | Rien de bloquant sur le principe. |

**Recommandation : accepter sur le principe, implémenter après `POST /screening`**, conformément à ta priorité 6. Lecture
seule uniquement dans un premier temps.

---

## Synthèse

| ADR | Recommandation | Bloque quoi si non décidée |
|---|---|---|
| 0021 | Accepter comme hypothèse, avec critère de remise en cause | Le fondement écrit des priorités |
| 0022 | Différer (phase 3) | Rien à court terme |
| 0023 | Accepter avec amendement (3 niveaux + état de couverture) | La conception des verdicts de l'ADR-0026 et de `POST /screening` |
| 0024 | Accepter étapes 1, 3a, 3b ; remplacer 2 et 3c par l'ADR-0026 ; corriger le statut du fichier | La PR des étapes 3a et 3b |
| 0025 | Accepter le principe, implémenter après le screening | Rien à court terme |

Décisions attendues de ta part, ADR par ADR. Je mets à jour le statut de chaque fichier seulement après ta réponse.
