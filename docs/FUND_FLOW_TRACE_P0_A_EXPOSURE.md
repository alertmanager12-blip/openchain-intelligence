# Fund Flow Trace P0-A — correction de `/exposure`

Statut : **préparation, aucune modification de code** (2026-11-12, code de `94ea42c`, pendant la mesure mémoire de 12 h).
Légende : **[F]** fait vérifié dans le code ou en base, avec sa référence ; **[P]** proposition.

## 1. Les deux implémentations

| | Endpoint `GET /api/v1/wallets/{a}/exposure` | Détecteur `ExposureDetector` |
|---|---|---|
| Code | `oci-app/.../analysis/InvestigationService.java:266-321` [F] | `oci-intelligence/.../detectors/ExposureDetector.java` [F] |
| Quand | à la demande, dans la requête HTTP | à chaque flux ingéré, sur le sujet |
| Sortie | `ExposureDto{direct[], indirect[]}`, chaque entrée `{category, hops, share, counterparty, evidenceLevel, source}` (`Dto.java:108-112`) [F] | signaux `*_EXPOSURE`, `INDIRECT_EXPOSURE`, `DUSTING` avec preuves (chemin, hashes, montants) [F] |
| Consommateurs | onglet « Exposure » de la page wallet (`wallet.html:138-140`) [F] ; contrat `docs/api/README.md:101` [F] | score de risque, règles, alertes [F] |
| Tests | `InvestigationApiTest:84` ne vérifie que `$.direct` est un tableau : **aucun test de sémantique** [F] | `DetectorsTest`, `DetectorSummariesTest`, banc `BenchScenarios` [F] |

## 2. Divergences, ligne par ligne

| # | Règle | Détecteur [F] | Endpoint [F] | Conséquence |
|---|---|---|---|---|
| E1 | Faux jetons | `counts()` / `traceable()` excluent `token.isUnverified()` ; un contrat inconnu du `TokenRegistry` reçoit le symbole `UNKNOWN` (`TokenRegistry.java:10-26`), donc un faux « USDT » est exclu | aucun filtre | n'importe qui peut créer de l'exposition avec un contrat sans valeur dont l'émetteur est une adresse sanctionnée |
| E2 | Poussière | entrées sous le seuil par symbole exclues (`DetectionConfig.java:106-111` : 10 USDT/USDC, 0,005 ETH, 50 TRX…) ; si c'est le seul lien → signal `DUSTING` sans poids | aucun filtre | une poussière reçue d'une adresse sanctionnée apparaît comme exposition directe |
| E3 | Chronologie (indirect) | un flux amont n'est suivi que s'il n'est pas postérieur au flux aval (`g.timestamp().isAfter(downstream.timestamp())` exclu) | aucune contrainte de temps (`InvestigationService.java:291-305`) | des fonds reçus **après** la sortie étudiée sont comptés comme origine |
| E4 | Mélange d'actifs | montants groupés par symbole, jamais additionnés entre jetons | `volume.merge(..., f.amount(), BigDecimal::add)` et `inTotal.add(f.amount())` additionnent les montants humains de **tous** les jetons (`:272-273`, `:293-294`) | 1 ETH et 1 USDT pèsent pareil ; une part « 35 % » n'a pas d'unité |
| E5 | Arrêt de la propagation | arrêt à toute adresse risquée (la source est trouvée) et aux catégories `stopAt` (EXCHANGE, BRIDGE) | arrêt aux EXCHANGE et BRIDGE, mais **continue à travers** une adresse risquée | chemins qui traversent la source listée et la comptent plusieurs fois |
| E6 | Catégories directes | seulement les catégories risquées (`findRisky`) | toutes les étiquettes, y compris EXCHANGE, DEFI_PROTOCOL, OTHER (`:276-277`) | « exposition » à un exchange mélangée avec l'exposition à risque (utile comme information, pas comme exposition) |
| E7 | Grandeur calculée | indirect : montant **borné** par le plus petit saut (« at most X traced »), confiance = fiabilité × 0,5^(sauts−1) | indirect : part = produit des parts de volume entrant, sans unité ni date | deux chiffres différents pour la même question |
| E8 | Budget | 5 000 flux visités, 20 chemins | 5 000 flux visités, aucune limite de chemins ; seuil de part 0,001 | ordre de grandeur comparable |
| E9 | Ensemble visité | une adresse n'est visitée qu'une fois (premier chemin BFS) | idem | même limite : un deuxième chemin vers la même source est ignoré |
| E10 | Formulation du vide | — | « No exposure to labelled categories was found. » (`wallet.html:139`) même si l'adresse n'est couverte que sur quelques heures | une absence de données est lue comme une absence d'exposition |

Points communs à corriger dans les deux (hors P0-A, notés pour P0-B) :
- les données lues sont la mémoire de travail (24 h au plus, plafonnée), plus l'historique de l'adresse s'il a été chargé ;
  ni l'endpoint ni le détecteur ne disent ce qui n'a pas été vu ;
- égalité des horodatages : deux flux du même bloc sont considérés comme simultanés et le flux amont est accepté, sans
  ordre intrabloc (pas d'index de log dans `ValueFlow`).

## 3. Correction proposée [P]

Principe : **une seule logique de qualification des flux**, extraite du détecteur, utilisée par l'endpoint. L'endpoint
ne calcule pas de « part de volume » tant qu'elle n'est pas définie par jeton.

1. Extraire de `ExposureDetector` une fonction pure `ExposurePaths.find(subject, flows, labels, cfg)` (règles E1 à E5,
   chronologie, budgets) ; le détecteur et l'endpoint l'appellent. Aucune modification de comportement du détecteur :
   vérifié par le banc existant et un test différentiel (mêmes chemins avant/après l'extraction).
2. Réponse de l'endpoint, additive (v1 non cassante) :
   - `direct[]` : seulement les catégories risquées ; par entrée, montants **par jeton** reçus et envoyés (entier brut +
     décimales), nombre de transferts, hashes (jusqu'à N, puis compteur), niveau de preuve de l'étiquette ;
   - `indirect[]` : chemin complet (adresses et hashes par saut), montant borné par le plus petit saut et son jeton,
     confiance, nombre de sauts ;
   - `ignored[]` : liens écartés (poussière, jeton non vérifié) avec leur raison, comme le signal `DUSTING` ;
   - `counterparties[]` (séparé) : étiquettes non risquées (exchange, protocole), pour l'information ;
   - `coverage` : plage couverte de l'adresse (voir P0-B) et liste des limites ;
   - `share` : conservé pour compatibilité, **calculé par jeton** (part du volume de ce jeton), marqué déprécié dans le
     contrat ; jamais une somme entre jetons.
3. Texte du vide : « Aucune exposition trouvée dans les données couvertes (du … au …) » ; si la couverture est partielle,
   le dire avant le résultat.
4. `depth` : bornes inchangées (1 à 3).

## 4. Cas de régression à écrire avant toute modification [P]

Données synthétiques, dans `oci-app` (endpoint) et `oci-intelligence` (fonction pure) ; résultats attendus exacts.

| # | Scénario | Attendu |
|---|---|---|
| R1 | S (sanctionnée) → A : 1 000 USDT | direct : SANCTIONED, 1 000 USDT reçus, 1 hash |
| R2 | S → A : 1 USDT (sous le seuil 10) | pas d'exposition ; `ignored` : poussière |
| R3 | S → A : 5 000 d'un contrat non référencé (`UNKNOWN`) | pas d'exposition ; `ignored` : jeton non vérifié |
| R4 | S → B (t1) ; B → A (t2 > t1) | indirect, 2 sauts, borné par le plus petit montant |
| R5 | B → A (t1) ; S → B (t2 > t1) | **pas** d'exposition indirecte (fonds reçus après la sortie) |
| R6 | S → B 100 USDT ; X → B 1 ETH ; B → A 100 USDT | aucune addition USDT + ETH ; montant tracé en USDT seulement |
| R7 | S → E (exchange) → A | arrêt à l'exchange ; pas d'exposition indirecte |
| R8 | S → S2 (aussi listée) → A | un seul chemin, arrêt à la première adresse listée rencontrée en remontant |
| R9 | A → C (exchange) uniquement | `direct` vide, `counterparties` : exchange |
| R10 | A sans historique chargé, seulement 2 h en mémoire | réponse avec `coverage` partielle et texte du vide qualifié |
| R11 | Flux répété (même `eventId`) | résultat identique (idempotence) |
| R12 | Réorganisation : le flux S → A est retiré | plus d'exposition ; l'appel suivant ne le montre plus |
| R13 | Différentiel | sur les 12 scénarios du banc de détection, l'endpoint et le détecteur produisent les mêmes chemins |
| R14 | Même bloc : B → A et S → B dans le même bloc | comportement documenté et testé (accepté aujourd'hui ; à revoir quand l'index de log sera disponible) |

## 5. Risques

- Changement visible pour les utilisateurs : l'onglet affichera moins d'expositions (poussière, faux jetons et fonds
  postérieurs disparaissent). C'est la correction voulue ; l'annoncer dans le CHANGELOG.
- Le champ `share` change de sens (par jeton). Garder l'ancien nom avec la nouvelle définition documentée ou ajouter
  `shareByToken` : **décision à prendre**. Proposition : nouveau champ, `share` déprécié.
- Coût : identique (mêmes budgets) ; la fonction pure évite de dupliquer la logique.

## 6. Dépendances

- Aucune pour la correction elle-même (E1 à E9).
- `coverage` dépend de P0-B (modèle de couverture). Sans P0-B, l'endpoint peut déjà dire « données en mémoire depuis
  <date du plus ancien flux de l'adresse> », ce qui est exact mais incomplet.
