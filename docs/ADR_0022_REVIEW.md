# Revue de l'ADR-0022 — investigations persistantes, reprenables, rejouables

Revue du 2026-11-12 sur le code de `94ea42c`. L'ADR n'est **pas modifié** ; son statut reste « proposed ». **[F]** fait
vérifié ; **[P]** proposition de la revue. Revue précédente : `docs/strategy/ADR_REVIEW_0021-0025.md` (recommandait de
différer en phase 3). Cette revue la réexamine à la lumière de Fund Flow Trace.

## 1. Décision proposée par l'ADR

- **Nouveau module.** Un module `oci-investigation` en Java pur.
- **Contenu persisté d'une investigation.**
  - Entrée : adresse ou transaction.
  - Paramètres : profondeur, fenêtre, budgets.
  - Plan d'étapes déterministe et statut.
  - Version du moteur.
  - Couverture des sources.
  - Graphe gelé.
  - Assertions typées (ADR-0023).
  - Décisions humaines.
- **Exécution** asynchrone, reprenable et idempotente, avec les mêmes mécanismes que l'historique d'adresse (ADR-0020).
- **Rapports** (JSON, puis HTML et PDF) produits uniquement à partir des assertions stockées.
- **Assistance IA** facultative : elle résume en citant les identifiants des assertions et n'ajoute aucun fait.

## 2. Problème résolu

Aujourd'hui [F], les « investigations » ne sont que des lectures sans état sur la mémoire de travail : le graphe, l'exposition
et la page wallet. Rien n'est conservé ; relancer la même requête une heure plus tard donne un autre résultat, sans trace de
l'écart. Aucun rapport n'est opposable à un auditeur ou à un régulateur. C'est directement le défaut D6 de
`FUND_FLOW_TRACE_ARCHITECTURE.md`.

## 3. Alternatives

| Option | Description | Verdict |
|---|---|---|
| A. ADR tel quel | module dédié, artefact complet, plan d'étapes, décisions humaines, rapports | cible juste, mais périmètre large |
| B. Instantané minimal | stocker seulement la requête et la réponse JSON (graphe, exposition) avec la date | reproductible « en lecture », mais pas rejouable ni explicable ; pas de couverture ni d'assertions |
| C. Artefact de traçage d'abord [P] | sous-ensemble de A limité à Fund Flow Trace : requête, limites, version du moteur, couverture, flux gelés (preuves), résultat du moteur P0-D, statut, `scope_id` ; décisions humaines et rapports HTML plus tard | **recommandé** |
| D. Ne rien persister, tout recalculer | — | contraire à la reproductibilité ; rejeté |

## 4. Avantages et inconvénients

| | Option A (ADR) | Option C (recommandée) |
|---|---|---|
| Avantages | reproductibilité testable ; audit ; IA encadrée | même garantie pour le traçage, livrable en P1 ; s'étend vers A sans migration destructive |
| Inconvénients | module, plan d'étapes, état de validation humaine : plusieurs semaines avant toute valeur visible | décisions humaines et rapports HTML/PDF reportés |

## 5. Conséquences sur les modules existants

- **`oci-intelligence`** : le moteur pur (P0-D) reste ici ; l'ADR prévoit un module séparé. **Proposition** : garder le
  moteur dans `oci-intelligence` (il n'a pas d'I/O) ; l'orchestration et la persistance vont dans `oci-app` (ou plus tard
  dans `oci-investigation`).
- **`oci-app`** :
  - tables `traces`, `trace_nodes`, `trace_flows`, `trace_attributions`, `trace_assertions` (migration additive avec
    `scope_id`) ;
  - service asynchrone ;
  - API `/api/v1/traces` ;
  - permission `investigation:write` à créer (seule `investigation:read` existe [F]).
- **`AddressHistoryService`** : réutilisé pour rapatrier les sauts. Son mécanisme de reprise [F] est le bon modèle.
- **Interface** : nouvelle page, ou évolution de la page « Fund flow graph ».

## 6. Mémoire et performances

- Le traçage ne doit **rien ajouter à la mémoire de travail** de l'ingestion : ses flux restent dans sa propre structure,
  bornée par ses limites (estimée à 14 Mo pour 20 000 flux, à mesurer).
- Stockage : quelques milliers à quelques centaines de milliers de lignes `trace_flows` par traçage, borné par les limites.
  Il faut une rétention par scope (à décider, ADR-0026 § 4).
- Rejouer « mêmes entrées, mêmes données » suppose des flux persistés :
  - soit gelés dans le traçage, ce qui est toujours possible ;
  - soit relus dans le journal ou le périmètre (voir `FLOW_JOURNAL_CAPACITY_ANALYSIS.md`).

  Le gel des preuves suffit pour **expliquer** un résultat. **Recalculer** sur des données nouvelles produit un nouveau
  traçage, avec un diff.

## 7. Risques de sécurité

- Les traçages révèlent l'activité d'investigation d'un client. L'isolation par `scope_id` est obligatoire dès la
  création (voir `FUND_FLOW_TRACE_TENANT_ISOLATION.md`). L'ADR ne le dit pas.
- Les exports doivent être audités et isolés ; leurs liens ne doivent pas être devinables.
- Les textes produits à partir de données de chaîne (noms de jetons, mémos) ne doivent jamais être interprétés comme des
  instructions par une IA de résumé (ADR-0025).
- Un attaquant peut épuiser les quotas RPC en lançant des traçages profonds. Il faut des budgets par traçage et par scope,
  et une file bornée.

## 8. Compatibilité avec les objectifs de ChainSentinel

Elle est forte : cet ADR est la condition pour qu'un dossier d'investigation soit « justifié par des preuves vérifiables »
(vision Fund Flow Trace) et pour ne jamais présenter une analyse partielle comme complète.

## 9. Modifications nécessaires avant acceptation [P]

1. Limiter la première étape à l'**artefact de traçage** (option C) ; renvoyer à une étape ultérieure le plan d'étapes
   générique, les décisions humaines et les rapports HTML/PDF.
2. Ajouter explicitement :
   - `scope_id` sur toutes les tables, et l'isolation prouvée par des tests négatifs ;
   - le **modèle de couverture** (P0-B) comme partie du résultat ;
   - le statut `STALE` après une réorganisation, et le diff entre deux exécutions.
3. Préciser que « rejouable » signifie « explicable à partir des preuves gelées ». Un recalcul est une nouvelle version.
4. Préciser le placement du moteur (`oci-intelligence`, pur) et de l'orchestration (`oci-app`).
5. Définir la rétention des traçages et des exports.

**Recommandation : accepter avec ces amendements, à mettre en œuvre en P1, après la validation de la mesure mémoire,
de l'ADR-0023 amendé et de l'ADR-0026.**
