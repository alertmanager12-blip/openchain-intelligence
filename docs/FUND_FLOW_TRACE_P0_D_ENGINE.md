# Fund Flow Trace P0-D — moteur de traçage pur

Statut : **conception, aucune implémentation** (2026-11-12, code de `94ea42c`). **[F]** fait vérifié ; **[P]** proposition.

Précision : ChainSentinel n'utilise **pas MongoDB**. La persistance est PostgreSQL (Flyway, `oci-app`) ; les flux de travail
sont en mémoire (`oci-intelligence`). Le moteur est indépendant de PostgreSQL, du RPC, du HTTP et d'Angular.

## 1. Place dans l'architecture [P]

- Module `oci-intelligence`, paquet `io.openchain.intelligence.trace` : Java pur, aucune dépendance Spring, aucune I/O,
  aucune horloge implicite (l'instant de référence est un paramètre).
- Entrées : une requête et un **fournisseur de flux** déjà normalisés (`TraceFlowSource`), interface implémentée plus tard
  par `oci-app` (mémoire, journal, historique) ; dans les tests, une liste en mémoire.
- Sorties : un `TraceResult` immuable et déterministe.
- Réutilise : `Address`, `Token`, `Network` (`oci-core`), la qualification des flux de `ExposureDetector` une fois
  extraite (P0-A), les seuils de poussière de `DetectionConfig` [F].

## 2. Contrat [P]

```java
record TraceRequest(Network network, Seed seed,            // adresse, ou transaction (liste de transferts de départ)
                    Direction direction,                    // FORWARD, BACKWARD
                    Window window,                          // [from, to] en temps et/ou en blocs
                    Optional<String> tokenKey,              // null = tous, chacun suivi séparément
                    AttributionMethod method,               // PROPORTIONAL (défaut), FIFO
                    TraceLimits limits,                     // profondeur, nœuds, flux lus, requêtes source, budget logique
                    Set<LabelCategory> stopAt,              // défaut : EXCHANGE, BRIDGE (comme ExposureDetector)
                    BigInteger minShareNum, BigInteger minShareDen)  // élagage

record TraceFlow(String eventId, Network network, String txHash, Long blockNumber, String blockHash, Integer logIndex,
                 Instant timestamp, Address from, Address to, TokenRef token, BigInteger rawAmount,
                 Provenance provenance, boolean confirmed)

interface TraceFlowSource {
  Page outgoing(Address a, String tokenKey, Window w, String cursor);   // triés par (instant, bloc, log, eventId)
  Page incoming(Address a, String tokenKey, Window w, String cursor);
  Coverage coverage(Address a, Window w);                              // modèle P0-B
  Optional<OpeningBalance> openingBalance(Address a, String tokenKey, Instant at);  // vide = inconnu
  List<Label> labels(Address a);
}

record TraceResult(Status status /* COMPLETE, PARTIAL */, List<Node> nodes, List<Edge> edges, List<Path> paths,
                   List<Attribution> attributions, List<Finding> findings, List<Stop> stops,
                   Map<Address, Coverage> coverage, Counters counters, String engineVersion)
```

Un `Finding` est typé selon ADR-0023 (OBSERVED pour un flux, INFERRED pour un chemin, une attribution ou un motif,
CONFIRMED seulement si l'étiquette l'est) et cite ses `eventId`.

## 3. Algorithme [P]

1. **Normalisation et dédoublonnage** (avant tout calcul) :
   - identité d'un transfert = `(network, txHash, logIndex)` quand l'index est connu ; sinon
     `(network, txHash, from, to, tokenKey, rawAmount, rang d'occurrence dans la transaction)` ;
   - un transfert vu par deux sources (cas TRC-20 live + TronGrid, P0-B § 1.5) est fusionné : une seule valeur, les deux
     identifiants gardés comme preuves ;
   - deux transferts réellement identiques dans une même transaction restent deux (rang d'occurrence) ; quand le rang ne
     peut pas être établi (sources sans index de log et pages différentes), le résultat le signale comme incertitude.
2. **Parcours orienté** (P0-C G1-G4) : BFS par saut, frontière triée par montant attribuable décroissant puis
   `(instant, eventId)` pour la stabilité ; contrainte chronologique ; cycles détectés sur le chemin courant ; ensemble
   visité par `(adresse, tokenKey)`.
3. **Attribution par pool** : pour chaque `(adresse, tokenKey)` visitée, reconstitution chronologique du pool :
   - solde d'ouverture : connu → pool « préexistant » ; inconnu → pool « origine inconnue » de taille non bornée,
     l'attribution devient un **intervalle** (borne basse = hypothèse « le solde préexistant part en premier »,
     borne haute = hypothèse « il ne part jamais ») ;
   - PROPORTIONAL : chaque sortie emporte la composition du pool à son instant ;
   - FIFO : chaque sortie consomme les entrées les plus anciennes ;
   - fractions exactes (`BigInteger` numérateur/dénominateur), arrondi uniquement à l'affichage ;
   - jamais de mélange entre `tokenKey` ; un changement d'actif arrête la propagation (raison `ASSET_CHANGE`).
4. **Arrêts motivés** : `DEPTH`, `NODES`, `FLOWS_READ`, `BUDGET`, `STOP_CATEGORY`, `BELOW_MIN_SHARE`, `DUST`,
   `UNVERIFIED_TOKEN`, `COVERAGE_GAP`, `ASSET_CHANGE`, `CYCLE`, `SERVICE_LIKE` (trop de flux) ; chacun rattaché à un nœud.
5. **Motifs** sur le graphe borné : dispersion, consolidation, peel chain, transit rapide, circulation, exposition
   (règles § 5) ; ce sont des `Finding` INFERRED.
6. **Statut** : `COMPLETE` seulement si aucun arrêt autre que `STOP_CATEGORY`/`BELOW_MIN_SHARE`/`DUST`/`UNVERIFIED_TOKEN`
   et toutes les couvertures `COMPLETE` ; sinon `PARTIAL` avec la liste des causes.

Déterminisme : même requête + mêmes flux ⇒ même résultat, octet pour octet (tri total, aucune table de hachage dans
l'ordre de sortie, aucune horloge).

Réorganisations : le moteur ne lit que ce que la source lui donne. Un flux retiré n'est plus fourni ; une réinclusion
revient éventuellement avec un autre bloc et un autre `logIndex` (donc un autre `eventId` live) : l'identité par
`(txHash, from, to, token, montant, rang)` permet de reconnaître le même transfert et de comparer deux exécutions
(`TraceDiff` : preuves retirées, ajoutées, déplacées).

## 4. Limites par défaut [P]

Profondeur 3 (plafond 8), nœuds 500 (5 000), flux lus 20 000 (200 000), part minimale 0,1 %, budget logique en nombre
d'opérations (le délai réel et l'annulation sont gérés par l'appelant, P1).

## 5. Les 15 scénarios synthétiques [P]

Notation : `X → Y : montant jeton @t`. Seuils de poussière de `DetectionConfig` ; USDT 6 décimales. Chaque scénario
vérifie les **chemins**, les **montants exacts**, les **preuves** (eventId, hash, bloc), les **arrêts** et l'**absence de
faux positif** indiqués.

| # | Scénario | Données | Requête | Attendu |
|---|---|---|---|---|
| 1 | A → B → C | A→B 100 USDT @1 ; B→C 100 @2 | FORWARD A, prof. 2 | chemin A→B→C ; C reçoit 100 attribués à A (les deux méthodes) ; 2 preuves ; COMPLETE |
| 2 | Plusieurs sauts | A→B→C→D→E, 100 chacun, @1..4 | FORWARD A, prof. 3 | arrêt `DEPTH` à D ; E absent ; PARTIAL |
| 3 | Dispersion | A→B 30, A→C 30, A→D 40 @1 | FORWARD A, prof. 1 | parts 30/30/40 % ; **pas** de motif dispersion (3 < 20 destinataires, seuil FanOut) |
| 4 | Consolidation | B→A 30, C→A 30, D→A 40 @1 | BACKWARD A | sources 30/30/40 % ; motif consolidation **seulement** si le seuil est atteint (variante à 25 sources : détecté) |
| 5 | Sources multiples | S1→A 60 @1 ; S2→A 40 @2 ; A→X 50 @3 | FORWARD S1 | PROPORTIONAL : X reçoit 30 de S1 ; FIFO : 50 de S1 |
| 6 | Circulation | A→B 100 @1 ; B→C 100 @2 ; C→A 100 @3 | FORWARD A | cycle détecté une fois (`CYCLE`) ; fin du parcours ; un `Finding` circulation |
| 7 | Peel chain | A→B 1000 @1 ; B→C 950, B→p1 50 @2 ; C→D 900, C→p2 50 @3 ; D→E 850, D→p3 50 @4 | FORWARD A, prof. 4 | motif peel chain (≥ 3 détachements réguliers) ; parts exactes vers p1..p3 ; variante à découpages irréguliers : **pas** de motif |
| 8 | Solde préexistant | A ouvre avec 100 (connu) ; S→A 100 @1 ; A→X 100 @2 | FORWARD S | PROPORTIONAL : 50 de S ; FIFO : 0 ; variante solde **inconnu** : intervalle [0 ; 100], marqué borne |
| 9 | Fonds mélangés | S→A 100 USDT @1 ; Y→A 1 ETH @1 ; A→X 100 USDT @2 ; A→Z 1 ETH @2 | FORWARD S | X : 100 % de S ; Z : **0** (aucun mélange d'actifs) ; arrêt `ASSET_CHANGE` absent (pas de swap) |
| 10 | Exposition directe | S (SANCTIONED, CONFIRMED) → A 1 000 USDT | BACKWARD A, prof. 1 | exposition directe CONFIRMED (étiquette) avec le hash ; variante 1 USDT : `DUST`, aucune exposition ; variante jeton `UNKNOWN` : `UNVERIFIED_TOKEN` |
| 11 | Exposition indirecte | S→B 500 @1 ; B→C 400 @2 ; C→A 300 @3 | BACKWARD A, prof. 3 | 3 sauts, montant attribué exact, confiance INFERRED ; variante S→B @4 (après) : **aucune** exposition |
| 12 | Historique incomplet | comme 1, couverture de B `TRUNCATED` | FORWARD A | résultat PARTIAL, arrêt `COVERAGE_GAP` à B, attribution marquée borne basse |
| 13 | Flux dupliqués | A→B fourni deux fois (même eventId) + même transfert TRC-20 sous id live et id historique | FORWARD A | compté **une fois** ; deux identifiants en preuve ; montants identiques au scénario 1 |
| 14 | Réorganisation | exécution 1 avec B→C ; exécution 2 sans (retiré) | FORWARD A | exécution 2 : chemin A→B ; `TraceDiff` liste la preuve retirée ; aucune donnée fantôme |
| 15 | Réinclusion | B→C revient dans un autre bloc, autre `logIndex` | FORWARD A | chemin et montants identiques à l'exécution 1 ; preuve mise à jour (nouveau bloc) ; `TraceDiff` : « déplacée » |

Tests transverses : déterminisme (deux exécutions, mêmes octets) ; chaque limite atteinte exactement ; aucun arrêt muet
(toute réduction de couverture a une raison) ; fractions exactes sur 8 sauts sans dérive.

## 6. Hors périmètre de P0-D

Persistance, API asynchrone, annulation et délai réel, interface, rapatriement d'historique, isolation (le moteur ne voit
que ce que l'appelant lui donne ; l'isolation est faite par l'appelant, voir `FUND_FLOW_TRACE_TENANT_ISOLATION.md`).

## 7. Risques

- Identité des transferts sans index de log (TRC-20 TronGrid) : le rang d'occurrence est fragile entre pages ; le moteur
  doit le signaler plutôt que deviner.
- L'attribution proportionnelle et FIFO sont des conventions, pas des faits : toujours affichées avec la méthode, la part
  inconnue et l'avertissement.
- Coût mémoire d'un traçage : borné par les limites (20 000 flux ≈ 14 Mo estimés à 700 o par flux, à mesurer) ; jamais
  dans la mémoire de travail de l'ingestion.
