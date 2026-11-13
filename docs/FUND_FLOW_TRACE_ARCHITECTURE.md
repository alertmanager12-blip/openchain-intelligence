# Fund Flow Trace — audit et architecture cible

Statut : **audit et conception, aucune implémentation.** Rédigé le 2026-11-12 sur le code de `94ea42c`. À valider avant
tout développement. Ce document s'appuie sur les ADR existants et ne les modifie pas : ADR-0005 (stockage du graphe,
accepté), ADR-0020 (historique d'adresse à la demande, accepté), ADR-0022 (investigations reproductibles, proposé),
ADR-0023 (niveaux épistémiques, proposé), ADR-0025 (MCP, proposé), ADR-0026 (périmètre client, proposé).

---

## 1. Diagnostic : ce qui existe réellement

### 1.1 Inventaire

| Composant | Où | Ce qu'il fait réellement | État |
|---|---|---|---|
| Graphe de flux | `InvestigationService.graph`, `GET /api/v1/graph/{address}` | BFS non orienté depuis une adresse, profondeur 1 à 3, 2 à 500 nœuds, filtres jeton et dates ; arêtes agrégées par (source, cible, jeton) avec montant total, nombre de transferts et **dernier** hash seulement | fonctionne, sur une source de données très limitée (§ 1.2) |
| Page « Fund flow graph » | `frontend/.../investigation/` (Cytoscape) | saisie adresse + réseau, profondeur, jeton, dates ; expansion d'un nœud (nouvel appel profondeur 1) ; chemin A* vers la racine **sans tenir compte du sens** ; inspecteur ; export PNG ; mention « tronqué » | fonctionne ; pas de sens, pas de hash de transaction en entrée, pas de timeline, pas d'export de dossier |
| Exposition (API) | `InvestigationService.exposure`, `GET /wallets/{a}/exposure` | saut 1 dans les deux sens (part du volume), puis BFS **entrant** jusqu'à 3 sauts, part = produit des parts entrantes, arrêt aux exchanges et bridges, 5 000 flux visités | fonctionne, mais **incohérent avec le détecteur** (§ 1.3) |
| Détecteur d'exposition | `ExposureDetector` (flux continu) | exposition directe et indirecte **cohérente dans le temps** (chaque flux amont antérieur au flux aval), filtre anti-poussière, jetons non vérifiés exclus, confiance décroissante par saut, preuve = chemin complet | fonctionne, correct sur le plan méthodologique |
| Analyse de transaction | `GET /transactions/{hash}/analysis` | reçu du fournisseur, transferts, signaux liés à ce hash, risque des parties | fonctionne ; n'est pas un point d'entrée de traçage |
| Historique d'adresse | `AddressHistoryService` (ADR-0020), tables `address_history_*` | rapatriement à la demande, paginé et reprenable, de l'historique d'**une** adresse (eth_getLogs ERC-20, TronGrid) ; plafonds 200 pages / 5 000 événements ; couverture exposée | fonctionne pour l'adresse consultée ; **jamais déclenché pour les sauts suivants** |
| Détecteurs de motifs | `detection/detectors/` | FanOut (dispersion), FastForwarding (transit rapide, mule), Structuring, CircularFlow (DFS borné), RapidDrain (vidage ; pas sur TRON), DormantReactivation, Exposure | fonctionnent en flux continu, sur la mémoire de travail uniquement |
| Réorganisations | `IntelligencePipeline` + V10 | retrait exact des flux, signaux et scores ; état de preuve des alertes VALID / PARTIALLY_WITHDRAWN / WITHDRAWN | fonctionne pour la mémoire et les alertes ; **un graphe déjà affiché n'est pas invalidé** |
| Journal des flux | `value_flows` (V9) | écriture différée des flux pour le redémarrage, 7 jours | **désactivé en production** (`OCI_PERSISTENCE_INTELLIGENCE_JOURNAL=false` par défaut ; dernière ligne du 2026-11-09) ; aucun index par adresse |
| `address_edges` + CTE récursive | ADR-0005 (accepté) | — | **jamais implémenté** |

### 1.2 Le problème central : la source de données

Le graphe et l'exposition lisent `intelligence.flows()`, c'est-à-dire **la mémoire de travail** (ADR-0024) :

- flux des dernières 24 h au plus, plafonnés à 400 000. Mesuré en production : les plafonds sont la limite effective,
  donc la fenêtre réelle est souvent **plus courte que 24 h** ;
- plus les flux des adresses dont l'historique a été chargé (70 adresses en base, 31 157 événements) ;
- après un redémarrage, rien d'autre : le journal est désactivé. Un arrêt de plusieurs heures crée en outre un trou,
  puisque la reprise saute au-delà de 7 200 blocs. Le 2026-11-12, TRON 87 899 299..87 943 187 et Ethereum
  26 386 430..26 400 192 ne sont pas indexés.

Conséquence : **un traçage multi-sauts au-delà de quelques heures est aujourd'hui impossible**, sauf si chaque adresse
intermédiaire a été ouverte à la main, ce qui déclenche son historique. Rien ne le signale à l'analyste : la réponse
indique seulement `truncated` quand le plafond de nœuds est atteint, jamais « les données de ce saut couvrent seulement
les 9 dernières heures ». Une analyse partielle peut donc être lue comme complète, ce qui contredit la règle
fondamentale.

### 1.3 Défauts et doublons

| # | Constat | Effet |
|---|---|---|
| D1 | Endpoint d'exposition ≠ `ExposureDetector` : pas de cohérence temporelle, pas de filtre poussière, jetons non vérifiés comptés, montants de jetons différents additionnés comme des nombres (`BigDecimal` USDT + TRX + jeton inconnu) | une exposition « 35 % » peut venir de poussière, d'un faux jeton ou de fonds reçus **après** la sortie étudiée ; deux chiffres différents pour la même question |
| D2 | Le graphe est non orienté et n'a pas de notion de sens (avant / arrière) | « d'où viennent les fonds » et « où vont-ils » sont mêlés ; le chemin A* ignore le sens et peut relier deux adresses sans flux de valeur possible entre elles |
| D3 | Arêtes agrégées sans ordre temporel ; seul le dernier hash est gardé | impossible de vérifier qu'un chemin est chronologiquement possible ; preuve incomplète (un seul hash pour N transferts) |
| D4 | `ValueFlow` ne porte ni bloc ni index de log | la preuve « bloc + horodatage » demandée n'existe pas pour le flux live ; seul l'historique la porte (et `-1` pour TronGrid TRC-20) |
| D5 | Aucune attribution de fonds (taint) | « quelle part vient de X » n'a pas de réponse ; l'exposition proportionnelle n'est pas une attribution (pas de solde, pas d'ordre) |
| D6 | Aucun résultat persisté ni reproductible | relancer le même graphe une heure plus tard donne un autre résultat (purge, nouveaux flux) sans trace de l'écart |
| D7 | Calcul synchrone dans le thread HTTP, sans délai maximal ni annulation ; `involving()` copie et retrie la liste à chaque nœud | pour une adresse d'exchange (des dizaines de milliers de flux), une requête profondeur 3 peut coûter cher et tient les verrous du magasin de flux (contention avec l'ingestion) |
| D8 | Pas de périmètre client (ADR-0026 non implémenté) | toute clé `investigation:read` peut tracer n'importe quelle adresse ; acceptable en mono-instance, bloquant en multi-client |
| D11 | Double comptage TRC-20 : un transfert vu en direct et par l'historique TronGrid a deux `eventId` (`TronGridProvider.java:505-516`) | montants et expositions surestimés aux bords des plages ; voir P0-B § 1.5 |
| D12 | Blocs sautés à la reprise (> 7 200 blocs) seulement journalisés, jamais exposés | trou de couverture invisible pour l'analyste ; voir P0-B § 1.3 |
| D9 | Limites connues non reliées au traçage : transactions internes EVM et TRC-10 non indexés, pas de solde historique sur TRON | annoncées sur la page wallet, absentes du graphe |
| D10 | Motifs manquants : consolidation (fan-in), peel chain, financement coordonné | trois des dix comportements demandés n'ont pas de détecteur |

### 1.4 Ce qui fonctionne et doit être conservé

- `ValueFlow` comme seule arête de propagation (« pas de flux de valeur, pas de propagation »), idempotent par `eventId` ;
- la méthodologie de `ExposureDetector` : cohérence temporelle, anti-poussière, arrêt aux catégories, preuve par chemin ;
- `AddressHistorySource` (SPI ADR-0020) : pagination, reprise, couverture déclarée, plafonds ;
- la gestion des réorganisations, et le modèle d'état de preuve des alertes (V10) pour invalider un résultat ;
- la page Cytoscape : rendu, expansion, inspecteur, thème, accessibilité ;
- les niveaux de preuve (`EvidenceLevel`) et l'avertissement « un lien n'est pas une intention partagée ».

---

## 2. Architecture cible

### 2.1 Principes

1. **À la demande, hors du chemin d'ingestion.** Un traçage est un travail asynchrone, borné, annulable, qui ne lit
   jamais sous verrou plus d'une page à la fois et ne bloque pas les indexeurs.
2. **Les sources sont hiérarchisées et la couverture est déclarée par adresse et par saut.** Ordre de lecture :
   mémoire de travail → flux persistés → historique d'adresse stocké → rapatriement à la demande (ADR-0020), dans les
   budgets. Chaque nœud porte la plage de blocs et de dates réellement couverte.
3. **Le résultat est un artefact (ADR-0022).** Les paramètres, la version du moteur, la couverture, les flux utilisés (gelés)
   et les assertions typées (ADR-0023) sont persistés ; un rapport est généré uniquement à partir d'eux.
4. **Faits, signaux, hypothèses et conclusions sont des types distincts.** Un flux est OBSERVED ; un chemin, une
   attribution ou un motif est INFERRED, avec sa méthode et sa confiance ; une étiquette de sanction est CONFIRMED
   seulement si sa source l'est. Le moteur ne produit **jamais** une conclusion de culpabilité.
5. **Jamais de fusion d'actifs.** Les montants ne s'additionnent qu'au sein d'un même jeton (même `tokenKey`) ; un
   changement d'actif (swap, bridge) interrompt la propagation tant qu'il n'est pas modélisé explicitement.

### 2.2 Composants

```
oci-core          Assertion, EvidenceRef (ADR-0023) ; TraceRequest / TraceLimits (valeurs)
oci-intelligence  trace/ : TraceEngine (algorithmes purs, sans I/O), AttributionMethod (FIFO, proportionnelle),
                  PatternDetectors sur un graphe borné (fan-in, peel chain, financement coordonné)
oci-app           trace/ : TraceService (orchestration, budgets, file de travaux), FlowSource (lecture hiérarchisée),
                  TraceRepository (PostgreSQL), TraceController (REST), branchement HistoryService pour les sauts
frontend          features/trace/ : formulaire, graphe orienté, timeline, chemins, attribution, dossier
```

`TraceEngine` ne connaît que des interfaces : `FlowSource.page(address, direction, token, window, cursor)` et `Clock`.
Les tests synthétiques l'exercent sans base ni réseau.

### 2.3 Flux de traitement

```
POST /traces ──► validation, périmètre, limites ──► trace PENDING (persistée) ──► file (1 exécuteur par instance,
                                                                                  N traces simultanées au plus)
  boucle par saut (BFS orienté, frontière triée par montant attribuable décroissant) :
    1. FlowSource lit la page suivante des flux de l'adresse (sens, jeton, fenêtre) depuis : mémoire → value_flows
       → address_history_events ; si la couverture est insuffisante et le budget le permet, demande un rapatriement
       ADR-0020 (asynchrone ; le saut passe en « en attente de données »)
    2. filtres : poussière, jetons non vérifiés, cohérence temporelle, catégories d'arrêt (exchange, bridge, mixer)
    3. attribution du montant transmis (§ 4) ; arrêt quand la part attribuable < seuil
    4. enregistrement : nœuds, arêtes (une par flux, pas d'agrégation à la source), couverture, raisons d'arrêt
    5. contrôle des budgets (profondeur, nœuds, flux, durée, requêtes RPC) → arrêt motivé si dépassé
  fin : détection de motifs sur le graphe borné, assertions, statut COMPLETE | PARTIAL | CANCELLED | FAILED
```

Un traçage reste **PARTIAL** dès qu'une limite ou un manque de couverture a réduit le résultat, quelle qu'en soit la
cause ; la liste des raisons est dans la réponse et dans le dossier.

### 2.4 Réorganisations et réinclusions

- Un traçage n'utilise par défaut que des flux confirmés (profondeur de confirmation du réseau) ; option pour inclure
  les flux non confirmés, marqués comme tels.
- Chaque flux gelé dans un traçage garde son `eventId`, son bloc et son hash de bloc s'ils sont connus. Quand le pipeline
  retire un flux (réorganisation), les traçages qui le citent passent à `STALE` avec l'identifiant du flux retiré (même
  mécanique que l'état de preuve des alertes V10) ; une réinclusion le signale aussi. Le résultat n'est jamais modifié
  silencieusement : on relance le traçage, et l'écart entre les deux versions est conservé.

---

## 3. Modèles de données

### 3.1 Prérequis sur les flux

- Ajouter à `ValueFlow` `blockNumber` et `logIndex` (nullable pour les sources qui ne les donnent pas), déjà connus
  dans `ChainEvent` et dans `address_history_events`. **Impact mémoire à mesurer** (environ 16 o par flux, soit 6 Mo pour
  400 000 flux) : à faire après la mesure de 12 h, pas avant.
- Index par adresse sur les flux persistés : `value_flows (network, from_address, ts)` et `(network, to_address, ts)`,
  colonnes `block_number`, `log_index`. Une migration additive, utile seulement si le journal est activé (décision
  d'exploitation séparée ; volume mesuré en 2026-11 : environ 4,8 M flux par jour pour Ethereum + TRON, soit environ 30
  à 35 M lignes sur 7 jours, à dimensionner avant).

### 3.2 Tables du traçage (migration additive, `scope_id` dès le départ, ADR-0026)

| Table | Contenu |
|---|---|
| `traces` | id, scope_id, créateur, entrée (adresse ou hash + réseau), sens, paramètres et limites, méthode d'attribution, version du moteur, statut, raisons d'arrêt, compteurs (nœuds, flux, requêtes, durée), créé / terminé |
| `trace_nodes` | trace_id, adresse, saut, rôle (racine, intermédiaire, source, destination, service), étiquettes au moment du traçage, couverture (bloc et date de début et fin, source de données), raison d'arrêt éventuelle |
| `trace_flows` | trace_id, event_id, tx_hash, bloc, index de log, horodatage, de, vers, jeton (clé, symbole, décimales), montant brut, provenance (mémoire, journal, historique, RPC), confirmé ou non, saut |
| `trace_attributions` | trace_id, flux, part attribuée à la source (fraction exacte et montant brut), méthode, pool d'origine (source suivie, solde préexistant inconnu, autre) |
| `trace_assertions` | trace_id, type (PATH, ATTRIBUTION, PATTERN, EXPOSURE, COVERAGE_GAP), niveau (ADR-0023), confiance, références de preuves (event ids, hashes), texte généré à partir des champs |

Montants : toujours en entier brut (`numeric`) avec les décimales du jeton ; affichage converti à la fin, jamais de
`double` dans un calcul.

---

## 4. Algorithmes

### 4.1 Parcours

- **Avant (forward)** : depuis l'adresse, flux sortants postérieurs à l'instant d'entrée des fonds suivis ; pour une
  entrée par hash : les transferts de la transaction donnent les points de départ.
- **Arrière (backward)** : flux entrants antérieurs à l'instant de sortie étudié.
- **Les deux** : deux parcours indépendants, fusionnés à l'affichage, jamais mélangés dans l'attribution.
- BFS orienté par saut, frontière triée par montant attribuable décroissant (les branches les plus significatives d'abord
  quand le budget est limité) ; ensemble des (adresse, jeton, fenêtre) déjà visités pour éviter les répétitions ;
  **cycles détectés** (adresse déjà sur le chemin courant) et enregistrés comme assertion, pas parcourus à nouveau.
- Cohérence temporelle stricte : le flux du saut k+1 est postérieur ou égal (même bloc : ordre des logs) à celui du saut k.
- Arrêts motivés : catégorie d'arrêt (exchange, bridge, mixer : on ne peut pas suivre des fonds à travers un service
  mutualisé sans hypothèse forte), part attribuable < seuil, poussière, jeton non vérifié, budget, couverture absente.
- Chemin entre deux adresses : recherche orientée et temporellement cohérente dans le graphe du traçage (et non A* non
  orienté sur le graphe affiché).

### 4.2 Attribution (« quelle part vient de la source S »)

Hypothèse de départ commune : par jeton et par adresse, les fonds forment un **pool** alimenté par des entrées datées ;
une sortie consomme le pool selon une règle. Le solde présent avant le premier flux connu est un pool **« origine
inconnue »** :

- EVM : solde au bloc de début via `balanceOf` / `eth_getBalance` au bloc (nœud archive requis ; sinon inconnu) ;
- TRON : pas de solde historique disponible → la part préexistante est **inconnue**, et l'attribution le dit.

| Méthode | Règle | Quand l'utiliser | Limites (affichées avec le résultat) |
|---|---|---|---|
| **Proportionnelle (haircut)** — par défaut | chaque sortie emporte la composition du pool à son instant (part de S = solde issu de S / solde total) | mélange de fonds, analyse prudente ; c'est la méthode des outils de conformité | dilue vite ; une part non nulle ne signifie pas que « ces » fonds sont ceux de S |
| **FIFO** | les entrées les plus anciennes partent en premier | jurisprudence et pratiques de traçage de fonds volés ; flux séquentiels simples | sensible à l'ordre intrabloc ; arbitraire quand les fonds sont mélangés |
| **Poison (binaire)** | toute sortie d'un pool qui a reçu de S est marquée | **jamais pour attribuer un montant** ; seulement pour borner « exposition possible » | surestime massivement ; affiché comme borne haute |

Règles communes :

- attribution **par jeton** : une sortie en TRX ne transporte pas de l'USDT reçu ; un swap interrompt (P2 : modéliser les
  swaps connus des DEX avec le taux observé, en assertion INFERRED) ;
- les frais (gas) ne sont pas des flux de valeur suivis ; les montants sont exacts en entiers bruts, avec des fractions
  rationnelles (numérateur/dénominateur `BigInteger`) pour éviter la dérive des arrondis sur plusieurs sauts ;
- couverture incomplète d'un pool (historique tronqué) → la part inconnue augmente et le statut de l'attribution devient
  « borne basse » ;
- chaque attribution est une assertion INFERRED avec la méthode, les flux d'entrée et de sortie utilisés et la part
  d'origine inconnue.

### 4.3 Motifs (sur le graphe borné du traçage, pas sur toute la chaîne)

| Motif | Existant | Cible |
|---|---|---|
| Dispersion rapide | FanOut | réutilisé tel quel |
| Consolidation | — | **nouveau** fan-in : N sources distinctes vers une adresse dans une fenêtre, part du volume |
| Peel chain | — | **nouveau** : chaîne de sauts où chaque adresse reçoit un montant, en renvoie la majeure partie vers une adresse nouvelle et détache une petite part ; longueur ≥ k, régularité des parts |
| Mule / transit rapide | FastForwarding | réutilisé ; présenté comme « transit rapide », pas comme « mule » |
| Structuration | Structuring | réutilisé |
| Circulation circulaire | CircularFlow | réutilisé (cycles déjà détectés par le parcours) |
| Financement coordonné | — | **nouveau** : plusieurs adresses financées par une même source dans une fenêtre courte, puis comportement similaire |
| Adresses à risque, sanctions | Exposure (détecteur) | méthodologie du détecteur, avec attribution réelle au lieu de la part de volume |
| Vidage rapide | RapidDrain | réutilisé ; indisponible sur TRON, dit explicitement |

Chaque motif est une hypothèse d'investigation (INFERRED, confiance justifiée), jamais une conclusion. La conclusion
« suffisamment étayée » n'existe que par validation humaine (ADR-0022) ou par une source CONFIRMED (sanction, gel
d'émetteur).

---

## 5. API (REST, puis MCP par ADR-0025)

| Méthode | Chemin | Permission | Rôle |
|---|---|---|---|
| POST | `/api/v1/traces` | `investigation:write` | lance : entrée (adresse ou hash), réseau, sens, profondeur, fenêtre, jeton, méthode, limites ; 202 + id |
| GET | `/api/v1/traces/{id}` | `investigation:read` | statut, progression, compteurs, raisons d'arrêt, couverture |
| GET | `/api/v1/traces/{id}/graph` | `investigation:read` | nœuds et arêtes orientées, paginés |
| GET | `/api/v1/traces/{id}/paths?to=` | `investigation:read` | chemins orientés et temporels vers une adresse, avec leurs preuves |
| GET | `/api/v1/traces/{id}/attribution` | `investigation:read` | parts par destination, méthode, part d'origine inconnue |
| GET | `/api/v1/traces/{id}/evidence` | `investigation:read` | flux (tx, bloc, horodatage, montant, provenance), paginés |
| POST | `/api/v1/traces/{id}/cancel` | `investigation:write` | annulation (le résultat partiel est conservé, statut CANCELLED) |
| GET | `/api/v1/traces/{id}/export?format=json\|csv\|html` | `investigation:read` | dossier généré uniquement à partir des données stockées |

Les endpoints actuels `/graph` et `/exposure` restent (compatibilité) ; `/exposure` est aligné sur la méthodologie du
détecteur (D1) et documenté comme exposition, pas comme attribution.

---

## 6. Interface

Une page « Trace » qui remplace à terme « Fund flow graph » (la page actuelle reste tant que la nouvelle n'est pas
équivalente) :

1. barre d'entrée : adresse **ou** hash, réseau, sens (Avant / Arrière / Les deux), profondeur, période, jeton ; bouton
   Lancer ; statut et progression en direct, Annuler ;
2. graphe **orienté**, disposé en couches par saut (gauche = sources, droite = destinations), arêtes épaissies selon le
   **montant attribué** ; regroupement des feuilles nombreuses (« 214 adresses, 3,2 % ») et des services (exchange) ; les
   nœuds non couverts ou tronqués sont visuellement distincts ;
3. filtres : montant minimal, actif, dates, niveau de risque, motif ;
4. timeline synchronisée avec le graphe (sélection d'une période = surlignage des flux) ;
5. panneau de détail : pour un nœud, couverture, étiquettes, attribution, motifs ; pour une arête, chaque transaction
   (hash, bloc, date, montant) avec lien explorateur ;
6. chemin entre deux adresses : orienté et chronologique, avec la liste des preuves ;
7. bandeau de couverture permanent : « Partiel : 3 adresses non couvertes, budget de nœuds atteint au saut 4 » ;
8. export du dossier (JSON, CSV des preuves, HTML imprimable).

Pas de cartes ni de textes décoratifs ; le texte d'avertissement actuel est conservé.

---

## 7. Performance et limites

| Limite | Défaut proposé | Plafond configurable | Raison |
|---|---|---|---|
| Profondeur | 3 | 8 | au-delà, l'attribution proportionnelle est négligeable sur la plupart des cas |
| Nœuds | 500 | 5 000 | lisibilité et mémoire du travail |
| Flux lus | 20 000 | 200 000 | coût de lecture borné |
| Fenêtre | 30 jours | 365 jours | couverture des historiques |
| Requêtes RPC / historique | 200 | 2 000 | quota des fournisseurs (TronGrid limite déjà : HTTP 429 observé) |
| Durée | 60 s | 15 min | travail asynchrone ; au-delà, résultat partiel |
| Traçages simultanés | 2 par instance | 8 | ne pas concurrencer l'ingestion |
| Part attribuable minimale | 0,1 % | — | élagage |

- Lecture paginée et triée par adresse (pas de N+1 : une requête par adresse et par page, index § 3.1) ; jamais
  `involving()` complet sur une adresse de service : au-delà de K flux, lecture par fenêtres et arrêt motivé.
- Mémoire du travail bornée par les limites ci-dessus (ordre de grandeur : 200 000 flux × environ 700 o ≈ 140 Mo au pire,
  à mesurer ; défaut 20 000 flux ≈ 14 Mo) ; les flux d'un traçage ne sont **pas** ajoutés à la mémoire de travail de
  l'ingestion.
- Métriques : `oci_trace_duration_seconds`, `oci_trace_flows_read`, `oci_trace_stops_total{reason}`,
  `oci_trace_rpc_requests_total`, `oci_trace_active`.

---

## 8. Sécurité et isolation

- Permissions : `investigation:read` existe déjà et protège `/graph` et `/exposure` (`SecurityConfiguration.java`) ;
  ajouter `investigation:write` (lancer, annuler) et `investigation:export`. Détail : `FUND_FLOW_TRACE_TENANT_ISOLATION.md`.
- `scope_id` sur toutes les tables du traçage dès la première migration ; filtrage dans les repositories (ADR-0026) ;
  test d'autorisation par endpoint prouvant qu'une clé du périmètre A ne lit jamais un traçage du périmètre B.
- Les données de chaîne et les étiquettes publiques sont partagées ; les traçages, attributions et validations ne le sont
  jamais.
- Entrées validées (adresse par réseau, hash, bornes) ; limites imposées côté serveur, jamais par le client.
- Journal d'audit : création, annulation, export d'un traçage.
- Les textes produits sont générés à partir des champs typés ; aucune donnée de chaîne n'est interprétée comme une
  instruction (ADR-0025).

---

## 9. Stratégie de tests

Moteur pur (`oci-intelligence`), données synthétiques, résultats exacts attendus :

| Scénario | Vérifications |
|---|---|
| A → B → C ; A → B → C → D | chemins, ordre temporel, montants exacts, profondeur, preuves (hash, bloc) |
| Dispersion A → B/C/D ; consolidation B/C/D → A | parts exactes, motifs détectés, regroupement |
| Plusieurs sources → une destination | attribution par source, somme = 100 % moins la part inconnue |
| Circulation circulaire | cycle détecté une fois, pas de boucle infinie, assertion produite |
| Peel chain | détection, longueur, parts détachées |
| Fonds mélangés avec solde préexistant | proportionnelle et FIFO : valeurs exactes attendues, part « inconnue » |
| Flux postérieur à la sortie | **non** attribué (régression du défaut D1) |
| Actifs différents | aucune addition entre jetons ; arrêt au changement d'actif |
| Exposition directe et indirecte à une adresse signalée | niveau, confiance, chemin ; poussière et faux jeton ignorés |
| Historique incomplet | statut PARTIAL, raison COVERAGE_GAP, attribution en borne basse |
| Réorganisation puis réinclusion | traçage STALE, identifiant du flux retiré, relance identique au cas sans réorganisation |
| Flux répétés | idempotence par `eventId` : résultat identique |
| Budgets | arrêt exact à chaque limite, raison correcte, résultat partiel exploitable |
| Annulation, délai | arrêt propre, statut, pas de fuite de travail |
| Isolation | clé de périmètre A sur traçage B : 404 |

Plus : différentiel « endpoint `/exposure` aligné » contre `ExposureDetector` sur le banc existant ; test de charge sur une
adresse d'exchange synthétique (100 000 flux) vérifiant la durée, la mémoire et l'absence de contention avec l'ingestion.

---

## 10. Risques et arbitrages

| Risque / arbitrage | Position proposée |
|---|---|
| Couverture des données : sans journal ni historique, le traçage multi-sauts n'a presque rien à lire | P0 : rapatrier l'historique des sauts à la demande (ADR-0020), dans les budgets ; activer le journal est une décision d'exploitation séparée (volume, disque) |
| Quotas RPC (TronGrid 429, eth_getLogs) | budget de requêtes par traçage, file globale, reprise ; dire « en attente de données » plutôt qu'échouer |
| Solde préexistant inconnu (TRON, nœud non archive) | part « origine inconnue » explicite ; jamais supposée nulle |
| Services mutualisés (exchanges, bridges) | arrêt motivé ; pas de traversée sans donnée de dépôt / retrait |
| Transactions internes EVM non indexées, TRC-10 non indexés | annoncés dans chaque traçage concerné ; adaptateur Erigon `ots_` (ADR-0020 B) en P2 |
| Mémoire : la mesure de 12 h est en cours | rien n'entre dans la mémoire de travail ; `blockNumber` dans `ValueFlow` attend la mesure |
| Lisibilité au-delà de quelques centaines de nœuds | couches par saut, regroupement, élagage par part attribuable |
| Coût de maintenance d'une deuxième page de graphe | la nouvelle page remplace l'ancienne quand elle est équivalente |
| Faux sentiment de preuve | niveaux ADR-0023 visibles partout ; pas de mot « coupable » ; validation humaine pour toute conclusion |

---

## 11. Plan de réalisation priorisé

### P0 — fiabilité de ce qui existe et fondations (sans dépendance à de nouvelles décisions)

1. Corriger l'endpoint `/exposure` (D1) : cohérence temporelle, poussière, jetons vérifiés, pas d'addition entre jetons,
   aligné sur `ExposureDetector`, test différentiel.
2. Déclarer la couverture dans `/graph` et `/exposure` : plage réellement couverte par adresse (mémoire, historique) et
   avertissement « analyse partielle » quand une adresse n'est pas couverte ; garder les **hashes** de chaque arête
   (pas seulement le dernier).
3. Graphe orienté : paramètre `direction` (in / out / both) et chemin orienté et chronologique dans l'interface.
4. Moteur `TraceEngine` pur (parcours orienté, cohérence temporelle, cycles, budgets, attribution proportionnelle et FIFO,
   pool « origine inconnue ») avec la batterie de tests synthétiques du § 9.

### P1 — traçage comme artefact

5. Tables du traçage avec `scope_id`, `TraceService` asynchrone, annulation, délai, métriques ; API § 5.
6. `FlowSource` hiérarchisé, avec rapatriement d'historique des sauts dans les budgets.
7. Bloc, index de log et hash de bloc dans les preuves (`ValueFlow` après la mesure mémoire ; index `value_flows`).
8. Invalidation par réorganisation (STALE) et relance comparée.
9. Nouvelle page « Trace » : sens, hash en entrée, graphe en couches, timeline, attribution, preuves, couverture, export
   JSON et CSV.
10. Motifs nouveaux : consolidation, peel chain, financement coordonné, avec bancs synthétiques (précision et rappel).

### P2 — profondeur et intégration

11. Assertions typées ADR-0023 dans tous les résultats et export HTML / PDF du dossier.
12. Outils MCP en lecture (ADR-0025) sur les mêmes cas d'usage.
13. Swaps DEX connus et bridges : propagation entre actifs, en INFERRED.
14. Transactions internes EVM (adaptateur Erigon `ots_`), soldes historiques par nœud archive.
15. Passage à un stockage de graphe dédié (ADR-0005, AGE) seulement si les mesures montrent que PostgreSQL ne suffit pas.

Prérequis de décision avant P1 : validation des ADR 0022, 0023 et 0026 (aujourd'hui « proposés »), et décision
d'exploitation sur l'activation du journal des flux.
