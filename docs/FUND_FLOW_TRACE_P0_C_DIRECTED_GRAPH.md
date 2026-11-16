# Fund Flow Trace P0-C — graphe orienté et chronologique

Statut : **C-1 (serveur) implémenté le 2026-11-16** : `GET /graph?direction=out|in|both` sur le moteur P0-D (G1 à G6,
G11 avec l'arrêt `SERVICE_LIKE` au-delà de 5 000 flux, `asOfBlock` de G7), tests T1 à T7 et T10 (`DirectedGraphApiTest`) ;
sans `direction`, ancien graphe conservé et déprécié. C-2 fait le même jour : `GET /graph/{a}/path` (chemin au plus tôt,
orienté, chronologique, un jeton, `TracePath`, T9) et `POST /graph/verify` (T8). C-3 fait le même jour (console : sens Out/In/Both, chemin serveur, arrêts
structurels affichés, filtre de dates par chevauchement, expansion dans le même sens, « Check for changes ») ; non
vérifié visuellement dans un navigateur. P0-C complet.

Préparation (2026-11-12, code de `94ea42c`). **[F]** fait vérifié ; **[P]** proposition.

## 1. Comportement actuel

### 1.1 Serveur : `InvestigationService.graph` (`:349-399`) [F]

- Parcours en largeur par niveau depuis la racine, profondeur 1 à 3 (`InvestigationController.java:137`), 2 à 500 nœuds
  (défaut 80).
- À chaque nœud : `flows().involving(src)`, c'est-à-dire **entrants et sortants confondus** ; le voisin est
  `counterparty(src)`. Le parcours ignore donc le sens : depuis A, on atteint C par A → B ← C (C a envoyé à B), alors
  qu'aucune valeur de A ne peut avoir atteint C.
- Les arêtes, elles, **gardent le sens** : clé `(from, to, tokenKey)`. Elles agrègent montant, nombre de transferts et
  ne gardent que le **dernier** hash et le dernier horodatage.
- Aucune contrainte chronologique entre sauts : A → B le 10, puis B → C le 3 forme un chemin A → B → C.
- Cycles : la carte `nodeDepth` empêche de revisiter un nœud (pas de boucle infinie), mais un cycle n'est ni détecté ni
  signalé.
- Filtres : jeton par symbole **ou** contrat (`equalsIgnoreCase`), dates par flux. Une limite de nœuds atteinte donne
  `truncated = true`, sans dire où.
- `involving()` copie et retrie la liste de chaque nœud sous le verrou du magasin de flux (`FlowStore.java`, défaut
  `involving`) : coût proportionnel au nombre de flux d'une adresse de service.
- Réorganisation : le graphe est recalculé à chaque appel, il reflète donc le retrait ; il n'existe ni version ni
  invalidation d'un graphe déjà affiché.

### 1.2 Interface : `graph-canvas.ts`, `investigation.ts` [F]

- **Chemin** : `cy.elements().aStar({ root, goal, directed: false })` (`graph-canvas.ts:174`), puis « N hops from the
  root, direction ignored ». C'est un plus court chemin en nombre d'arêtes, **non orienté**, sans dates ni montants.
- Expansion d'un nœud : nouvel appel profondeur 1 sur ce nœud (défaut 80 nœuds), nœuds ajoutés avec `depth = parent + 1`
  même s'ils sont plus proches de la racine par un autre chemin.
- Filtre de dates côté client sur `edge.timestamp`, qui est l'horodatage du **dernier** transfert de l'arête : une arête
  dont des transferts tombent dans la plage peut disparaître, et inversement (`investigation.ts:35-39`).
- Les couleurs distinguent « vers la racine », « depuis la racine », « entre autres wallets » : le sens est affiché, mais
  pas utilisé par le parcours ni par le chemin.
- Le graphe chargé et ses expansions ne sont pas invalidés après une réorganisation.

## 2. Ce qui doit changer

| # | Exigence | Changement [P] |
|---|---|---|
| G1 | A → B ≠ B → A | paramètre `direction = out \| in \| both` ; `out` ne suit que `outgoing(n)`, `in` que `incoming(n)` ; `both` = deux parcours séparés, fusionnés à l'affichage, chacun avec sa profondeur |
| G2 | Chronologie | en `out` : le flux du saut k+1 a lieu au plus tôt au moment du flux du saut k qui a amené la valeur ; en `in` : au plus tard. Le parcours propage, par nœud et par jeton, l'instant de référence (le plus ancien instant d'arrivée en `out`, le plus récent instant de départ en `in`) |
| G3 | Cycles | détection quand un voisin est déjà sur le chemin courant : arête marquée `cycle`, assertion produite, pas de nouveau parcours |
| G4 | Chemins reproductibles | ordre de parcours total et stable : (instant, `eventId`) ; même entrée + mêmes flux ⇒ même graphe (testé) |
| G5 | Preuves | voir P0-B : chaque arête porte tous ses `eventId` (paginés à l'API), plus le premier et le dernier instant |
| G6 | Limites | profondeur, nœuds, flux lus, durée ; chaque arrêt est rattaché au nœud concerné (`stoppedAt: {address, reason}`), pas seulement un booléen global |
| G7 | Réorganisations | la réponse porte `asOf` (dernier bloc indexé par réseau) ; les flux non confirmés sont marqués ; un graphe affiché peut être revérifié (`/graph/.../verify` : même requête, liste des preuves disparues) |
| G8 | Chemin dans l'interface | remplacé par un chemin **orienté et chronologique** calculé par le serveur sur les flux (pas sur les arêtes agrégées), avec la liste des transactions, et le message « aucun chemin de valeur possible dans les données couvertes » quand il n'y en a pas |
| G9 | Expansion | appel avec le même sens et le même instant de référence que le nœud ; profondeur recalculée au plus court ; limite d'expansion signalée |
| G10 | Filtre de dates client | appliqué sur la liste des instants de l'arête (premier/dernier ou preuves), pas sur le dernier seulement |
| G11 | Coût | lecture paginée par adresse et par fenêtre de temps ; au-delà de K flux pour une adresse, arrêt motivé (service probable) au lieu de tout copier |

Ce qui ne change pas : la page, Cytoscape, l'inspecteur, l'export PNG, l'avertissement « un lien n'est pas une intention
partagée », les bornes 1 à 3 de l'endpoint actuel (le moteur P0-D aura ses propres limites).

## 3. Compatibilité

- `GET /graph/{address}` sans `direction` garde le comportement actuel (non orienté) pendant une version, marqué déprécié ;
  l'interface passe à `direction` explicite (défaut `both`, affiché).
- `GraphEdgeDto` : champs ajoutés (`firstTimestamp`, `eventCount`, `evidence[]` paginé, `cycle`), `txHash` (dernier)
  conservé.

## 4. Cas de test [P]

| # | Données | Requête | Attendu |
|---|---|---|---|
| T1 | A → B (t1), B → C (t2>t1) | out depuis A, profondeur 2 | A → B → C |
| T2 | A → B (t2), B → C (t1<t2) | out depuis A | C **absent** (antérieur) |
| T3 | A → B, C → B | out depuis A | C absent (sens) ; `both` : C visible du côté `in` de B seulement si B est racine |
| T4 | A → B → C → A | out depuis A | cycle détecté une fois, aucune boucle |
| T5 | même bloc, A → B et B → C | out | règle documentée (accepté) et testée |
| T6 | B a 50 000 flux | out | arrêt motivé à B, résultat partiel exploitable |
| T7 | deux exécutions, mêmes données | — | graphes identiques (ordre, ids) |
| T8 | retrait d'un flux A → B | relance | l'arête disparaît ; `verify` liste la preuve retirée |
| T9 | chemin A ⇝ D | serveur | chemin orienté, chronologique, avec hashes ; aucun chemin si seul un chemin non orienté existe |
| T10 | filtre jeton USDT, arête mixte USDT/TRX | — | aucune addition entre jetons (déjà vrai : clé par `tokenKey`) |

## 5. Dépendances et risques

- Dépend de P0-B pour les preuves complètes et la couverture ; G1 à G4, G6, G8 et G9 peuvent être faits avant.
- Le moteur P0-D réalise G1 à G4 et G6 ; l'endpoint actuel peut l'appeler sur les flux en mémoire, ce qui évite une
  deuxième implémentation.
- Risque : un graphe orienté montre **moins** de nœuds que l'actuel. C'est exact ; l'interface doit l'expliquer (« les
  adresses reliées seulement par un sens contraire ne sont pas affichées », avec bascule `both`).
