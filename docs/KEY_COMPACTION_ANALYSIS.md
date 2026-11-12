# Étape 3 : analyse du compactage des identifiants

Date : 2026-11-11. **Analyse seule : aucun changement de comportement, aucun code de production modifié.** Sources : le
code, un histogramme de classes du heap de production (SIGQUIT sur l'instance en cours, après GC complet, image
299db4f) et un banc de mesure **hors dépôt** (`scratchpad/keyprobe/KeyProbe.java`, JDK 25 comme le conteneur ; les
bancs existants n'ont pas été touchés). Valeurs synthétiques, formes de production.

## 1. Constat de production (mesuré)

Histogramme du 2026-11-11 vers 11 h 40 : **heap vivant 2,02 Go** ; 548 502 flux, 300 008 profils, 344 612 signaux.

| Classe | Instances | Taille | Lecture |
|---|---:|---:|---|
| `byte[]` + `String` | 9,36 M chaînes | **1 000 Mo (50 %)** | identifiants, adresses, textes |
| `LinkedHashMap$Entry` | 3,48 M | 139 Mo | surtout clés de dédoublonnage des profils |
| `HashMap$Node[]` (tables) | 1,23 M | 128 Mo | une table par petite map, souvent presque vide |
| `RecentKeys` | 602 648 | 39 Mo | 2 par profil (événements, hachages de transaction) |
| `Address` | 1,50 M | 36 Mo (+ leurs chaînes) | une nouvelle instance par événement décodé |
| `ValueFlow` | 549 789 | 26 Mo | |
| `Token` | 549 800 | 18 Mo (+ leurs chaînes) | **un jeton par flux**, pour quelques dizaines de jetons distincts |
| `WalletProfileAccumulator` | 301 324 | 27 Mo | |

## 2. Structures qui conservent des identifiants

| Structure | Contenu | Borne | Rôle | Exactitude requise |
|---|---|---|---|---|
| `InMemoryFlowStore.ids` | `eventId` de chaque flux | plafond des flux (400 000 + exemptés) | dédoublonnage primaire ; `remove` lors d'un retrait | exacte |
| `ValueFlow.eventId`, `.txHash` | 2 chaînes par flux (`txHash` n'est pas partagé avec `eventId`) | idem | identité du flux, preuves, API | exacte |
| Profil `seenEvents` | `eventId` des flux et clés `réseau:hash:tx` des transactions | 4 096 par profil | dédoublonnage secondaire (rejeu après éviction du flux : reprise, historique, réinclusion) ; condition du `revert` | exacte |
| Profil `txHashes` | hachages de transaction et compteur | 4 096 par profil | nombre de transactions ; `revert` | exacte |
| Profil `tokens` | clé de jeton `réseau:contrat`, **recalculée en chaîne neuve** par profil | par profil | totaux par jeton | exacte |
| Pipeline `seenEvents` | `eventId` des événements hors transferts (approbations, changements d'admin, gels) | 500 000 | dédoublonnage ; `retractEvent` | exacte |
| Pipeline `retractedIds` | `eventId` retirés par réorganisation | 100 000 | détection de réinclusion | exacte |
| `BalanceBook` | `eventId` → solde avant | 200 000 | preuves de solde ; `forget` au retrait | exacte |
| Signaux | `txHash` dans les preuves ; `findByEvidenceReference` | plafond des signaux | retrait des signaux dépendants d'une réorganisation | exacte |
| Fenêtre de réorganisation de l'indexeur | événements des derniers blocs | profondeur max. par réseau | rétractation | exacte |
| `EventDedupCache` (alertes) | 200 000 empreintes | 200 000 | dédoublonnage des alertes | exacte |

Toutes ces structures exigent une **égalité exacte** : un faux doublon ferait disparaître un événement réel, sans
erreur visible.

## 3. Consommation (mesures de laboratoire, octets)

| Objet | Coût |
|---|---:|
| `txHash` (64 hex) | 100 |
| `eventId` TRON / Ethereum | 108 / 116 |
| adresse TRON / Ethereum | 76 / 84 |
| `Token` neuf (contrat et symbole) + `Address` neuve | 190 |

| Structure de clés (par clé, ensemble de k clés) | k = 1 | k = 4 | k = 16 | k = 256 |
|---|---:|---:|---:|---:|
| `LinkedHashMap`, chaîne gardée ailleurs (flux encore en mémoire) | 188 | 77 | 53 | 48 |
| `LinkedHashMap`, **chaîne gardée par le seul profil** (flux évincé) | 300 | 189 | 165 | 160 |
| empreinte 64 bits seule (anneau + index ouvert) | 172 | 43 | 29 | 25 |
| empreinte 64 bits + identifiant binaire exact (32 o + index) | 356 | 89 | 67 | 61 |

Profils réels (`WalletProfileAccumulator`) : **≈ 1 240 o fixes par profil**, puis ≈ 360 o par flux appliqué (dont
≈ 210 o de chaînes `eventId` et `txHash` une fois le flux évincé).

Stock de flux, 200 000 flux à chaînes réellement décodées :

| | Octets par flux |
|---|---:|
| un `Token` et des `Address` neufs par événement (production) | **818** |
| `Token` et `Address` canoniques (une instance par valeur) | **487** (−40 %) |

## 4. Doublons entre profils et stock de flux

1. **Partage par référence** : tant qu'un flux est en mémoire, son `eventId` et son `txHash` sont les mêmes objets
   dans le stock et dans les profils. Seule l'entrée de table est dupliquée : ≈ 48 o par profil.
2. **Chaînes orphelines** : un profil vit 7 jours, un flux 24 heures. Après l'éviction du flux, le profil est seul à
   garder ses deux chaînes (≈ 210 o par flux). Estimation par soustraction des détenteurs connus : **0,5 à 2,2 M
   chaînes, soit ≈ 50 à 230 Mo**. Confiance faible : pas de vidage du heap (pas de `jcmd` dans l'image, VM Docker
   limitée).
3. **Duplications de contenu** (objets distincts, même valeur) :
   - un `Token` par flux pour quelques dizaines de jetons ;
   - une `Address` par événement : 1,50 M instances pour ≈ 550 000 flux et 300 000 profils ;
   - la clé `réseau:contrat` recréée par profil (368 721 `Tok`, ≈ 30 Mo estimés) ;
   - les clés `réseau:hash:tx` créées une fois par partie ;
   - `eventId` contient `txHash`, sans partage.

Ordre de grandeur des clés de dédoublonnage des profils : ≈ 2,4 à 3,1 M entrées réparties sur 602 648 tables, soit
**≈ 230 à 450 Mo** au total (11 à 22 % du heap), avec les chaînes orphelines.

## 5. Options

### A. Instances canoniques de `Token` et `Address` (et de la clé de jeton)

Une seule instance par valeur : pour les jetons, une table bornée par (réseau, contrat) ; pour les adresses, l'adresse
déjà détenue par le profil, ou un interneur borné. L'égalité des records est inchangée, seule l'identité des objets
change. **Exacte, sans risque de fusion.** Gain mesuré : −331 o par flux, soit **≈ 180 Mo** pour 550 000 flux, plus
≈ 30 Mo de clés de jeton. CPU : une recherche dans une table par événement.

### B. Empreinte 64 bits seule — **rejetée**

Gain de ≈ 135 à 190 Mo sur les clés des profils. Mais une collision fait passer un événement distinct pour un
doublon, **silencieusement**. Probabilité par insertion : n / 2⁶⁴ (n ≤ 4 096), soit ≈ 1e-6 par an au débit actuel
pour les profils, et ≈ 2e-4 par an pour un ensemble global de 550 000 identifiants. Faible, mais non nulle et
invisible : contraire à la règle fixée.

### C. Empreinte 64 bits comme index + identifiant binaire exact

L'empreinte sert seulement à la recherche. L'égalité est décidée sur l'identifiant exact : 32 octets du hachage, réseau,
index. Une collision coûte une comparaison de plus ; elle est comptée, **jamais fusionnée**. Les formats non
hexadécimaux (signatures Solana en base58) gardent leur chaîne exacte. Le banc l'a vérifié : 2 M identifiants, 0
collision détectée, résultats identiques au modèle de référence. Gain : **≈ 50 à 250 Mo**, qui dépend de la part de
chaînes orphelines, non mesurée. Tant que le flux est en mémoire, cette clé coûte plus que l'entrée actuelle
(61 contre 48 o) : le gain ne vient que des clés orphelines et des tables.

### D. C + `ValueFlow` sans chaîne `eventId` stockée (identifiant dérivé) et index exact du stock

Supprime ≈ 108 o par flux (≈ 60 Mo). Rend aussi `remove(eventId)` en O(1) : le retrait parcourt aujourd'hui tout le
stock, environ 1,1 M éléments par flux retiré, sous verrou. **Coût du parcours estimé, non mesuré.** Gain total
**≈ 110 à 300 Mo**. Changement d'un record central utilisé dans tous les modules (journal, API, preuves) : complexité
et risque élevés.

### E. Identifiants numériques internés (dictionnaire global `eventId` → entier) — **reportée**

Les références tombent à 4 octets, mais le dictionnaire garde l'identifiant exact et doit savoir quand plus personne ne
s'en sert (comptage de références entre stock, profils, pipeline et `BalanceBook`). Une erreur de comptage
réutiliserait un numéro et **fusionnerait deux événements**. Gain comparable à D, risque supérieur.

### Hors périmètre, à signaler

- **F. En-têtes d'objets compacts** (`-XX:+UseCompactObjectHeaders`, option de production du JDK 25 du conteneur) :
  −7 à −11 % mesurés en laboratoire (profils 1 597 → 1 486 o ; stock 818 → 728 o par flux), sans code. **Changerait
  les conditions de la mesure de 12 heures** : à tester séparément, après elle.
- **G. Coût fixe des profils** : ≈ 1 240 o par profil × 300 000 ≈ **370 Mo**. Ce sont les tables créées vides pour
  chaque profil, alors que la plupart n'ont que quelques flux. C'est le plus gros bloc identifié, mais il ne relève pas
  des clés.

## 6. Comparaison chiffrée

| Option | Gain estimé | Fusion silencieuse possible | Retraits et réinclusions | Complexité | CPU par événement (banc, prototypes non optimisés) |
|---|---:|---|---|---|---|
| actuel | – | non | garantis | – | ajout 95 ns, recherche 42 ns |
| **A** canoniques | **≈ 210 Mo** | non | inchangés | faible | + 1 recherche |
| B 64 bits seuls | 135–190 Mo | **oui** | faux doublon possible | moyenne | ajout 281 ns |
| **C** 64 bits + exact | 50–250 Mo | non | identiques si l'égalité est exacte | moyenne | ajout 494 ns, recherche 572 ns |
| D C + `eventId` dérivé | 110–300 Mo | non | identiques ; retrait O(1) | élevée | idem C, retrait plus rapide |
| E identifiants internés | ≈ D | oui en cas d'erreur de comptage | risque | élevée | – |
| F en-têtes compacts | ≈ −7 à −11 % du heap | non | inchangés | option JVM | à mesurer |

CPU : au débit actuel (≈ 150 événements par seconde en rattrapage, 2 côtés), même 600 ns par opération représentent
moins de 0,02 % d'un cœur. Le coût porte sur la complexité et le risque, pas sur le CPU. Moins d'objets réduit aussi
le marquage du GC (bénéfice attendu, non mesuré).

## 7. Garanties pour les réorganisations

Quelle que soit l'option retenue, ces invariants doivent être prouvés par des tests avant toute intégration :

1. `flows.add` refuse exactement les identifiants déjà présents, et seulement eux.
2. `revert` d'un profil ne s'applique qu'à un flux réellement retenu, et le restaure exactement.
3. `retractedIds` reconnaît exactement une réinclusion.
4. `seenEvents.remove` du pipeline et `BalanceBook.forget` retirent exactement l'identifiant visé.

Tests prévus :

- **Test différentiel** contre le modèle de référence (`LinkedHashMap<String>`), sur des suites aléatoires
  d'ajouts, de retraits, de réinclusions et d'évictions au plafond.
- **Test de collision forcée** : une empreinte constante pour tous les identifiants. Le comportement doit rester
  identique au modèle, seulement plus lent, et chaque collision doit être comptée.
- Banc de détection et scénarios de réorganisation R01a–e inchangés ; `mvn verify`.

## 8. Recommandation (à valider)

1. **A d'abord.** C'est le gain le plus important et le plus sûr, exact par construction, sans effet sur le
   dédoublonnage ni les retraits.
2. **Mesurer la part de chaînes orphelines avant de décider C ou D.** Il faut un compteur de diagnostic (clés de profil
   dont le flux n'est plus en mémoire) ou une analyse d'un vidage du heap. L'un ou l'autre nécessite ton autorisation.
3. **B rejetée. E reportée.**
4. **F et G** à instruire séparément, après la mesure de 12 heures.

## 9. Limites

- L'attribution des chaînes par structure est une estimation (pas de vidage du heap). Les totaux par classe sont mesurés.
- Les coûts CPU viennent de prototypes non optimisés (C cherche linéairement son emplacement dans l'anneau). Ce sont
  des majorants.
- Le gain de A dépend de la réutilisation réelle des adresses : le banc utilise 58 000 adresses distinctes pour
  200 000 flux.
