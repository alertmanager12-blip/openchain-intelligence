# Étape 3 : option A (instances partagées) et diagnostic des clés orphelines — rapport d'implémentation

Date : 2026-11-11. Branche `wip/adr-0024-step-3a`, **non commité, non déployé**. Origine :
`docs/KEY_COMPACTION_ANALYSIS.md` (options A et diagnostic). Les options B, C, D et E ne sont pas implémentées. Aucun
plafond, aucune règle de purge ou d'exemption, aucune configuration de production n'a été modifié. Aucune migration.

## 1. Résumé

- **Jetons** : les flux en mémoire partagent une seule instance par jeton **égal**. L'égalité porte sur le record
  complet (réseau, contrat, symbole, décimales). La table est bornée à 4 096 jetons, et le moins récemment utilisé est
  oublié en premier. Les descripteurs de monnaie native sont désormais un par réseau, au lieu d'une instance par appel.
- **Adresses** : un flux reprend l'instance d'adresse déjà détenue par le profil du wallet. **Aucune table d'adresses** :
  la référence vit exactement aussi longtemps que le profil, qui est borné et purgé par l'ADR-0024.
- **Diagnostic** des clés de dédoublonnage gardées par les profils après l'éviction de leur flux : lecture seule,
  calculé uniquement à la demande (endpoint d'administration).
- Seule l'**identité des objets** change. L'égalité, le hachage, les identifiants d'événements, le dédoublonnage, les
  retraits et les réinclusions sont inchangés, ce que vérifient les tests différentiels.

## 2. Classes modifiées

| Fichier | Changement |
|---|---|
| `oci-intelligence/.../memory/CanonicalInstances.java` (nouveau) | table bornée des jetons (clé = égalité complète, hachage injectable pour les tests) ; adresses via une source externe ; `flow(f)` rend `f` ou un flux **égal** construit sur les instances partagées ; statistiques |
| `oci-intelligence/.../IntelligencePipeline.java` | `onFlow` partage les instances avant `flows.add` ; constructeur optionnel avec `CanonicalInstances` (`NONE` = référence des tests) ; `canonical(f)` ; `canonicalInstances()` |
| `oci-intelligence/.../profile/WalletProfileService.java` | `canonicalAddress(a)` (instance du profil, ou `a`) ; `orphanKeys(...)` (diagnostic) |
| `oci-intelligence/.../profile/WalletProfileAccumulator.java` | accesseurs en lecture : `address()`, copies des clés (diagnostic) |
| `oci-intelligence/.../profile/OrphanKeyReport.java` (nouveau) | résultat du diagnostic |
| `oci-intelligence/.../flow/NativeTokens.java` | une instance par réseau (égale à `Token.nativeOf`) |
| `oci-app/.../chain/ChainIngestionService.java` | le rejeu du journal au redémarrage (`restoreIntelligence`) partage aussi les instances |
| `oci-app/.../api/ProfileKeyDiagnosticController.java` (nouveau) | `GET /api/v1/admin/working-set/profile-keys` (permission `keys:admin`) |
| `oci-app/.../config/MetricsConfiguration.java` | jauges `oci_canonical_*` |
| `docs/guides/installation.md`, `CHANGELOG.md` | documentation |

Points d'entrée vérifiés : flux en direct et historique (`onFlow`), rejeu du journal (`restoreIntelligence`), valeur
native (`onTransaction`, via `onFlow`). J'ai cherché toute comparaison par identité (`==`) sur `Address` et `Token` dans
le code de production : il n'y en a aucune.

## 3. Stratégie de partage et durée de vie des références

| | Jetons | Adresses |
|---|---|---|
| Identité canonique | record `Token` complet : deux jetons de même symbole mais de contrat, réseau ou décimales différents restent **distincts** | record `Address` complet (réseau + valeur) : la même valeur sur deux réseaux reste distincte |
| Structure | `LinkedHashMap` en ordre d'accès, ≤ 4 096 entrées, sous verrou | aucune : instance détenue par le profil (`ConcurrentHashMap` existante) |
| Durée de vie | tant que le jeton fait partie des 4 096 plus récemment utilisés ; oublié, il cesse seulement d'être partagé | celle du profil ; après l'éviction du profil, aucune référence ne subsiste dans le mécanisme (test de libération) |
| Mémoire maximale | ≈ 4 096 × (entrée + clé + jeton) ≈ 1 Mo | 0 |
| Sécurité | substitution seulement par une instance **égale** | substitution refusée si la source renvoie une valeur différente (défensif) |
| Concurrence | verrou court | best effort : deux fils qui voient en même temps une adresse sans profil gardent chacun leur instance (égale) |

## 4. Tests différentiels

`CanonicalInstancesTest.sharingInstancesChangesNothingObservable` : la même suite d'événements, construits à chaque
fois avec des objets neufs comme le fait un décodeur, passe par un pipeline sans partage (`NONE`, comportement
antérieur) et par le pipeline par défaut.

La suite comprend 1 928 transferts, 131 rejeux, 134 transactions natives, 137 retraits par réorganisation, 30
réinclusions et 140 évictions (flux puis profils inactifs). Elle mêle 6 jetons volontairement proches (même symbole,
même contrat sur un autre réseau, autres décimales) et des adresses identiques sur Ethereum et Polygon. Des soldes sont
enregistrés puis oubliés au retrait.

Éléments comparés, **4 487 au total** :

- après chaque opération : doublon ou non, signaux créés, scores, et pour les retraits les côtés de profil annulés et
  les signaux retirés, recalculés ou ajoutés ;
- à la fin, pour chaque adresse : flux entrants et sortants, profil complet, dernier score, signaux ;
- puis tous les signaux, le solde enregistré de chaque événement et la taille des stocks.

**Résultat : 4 487 sur 4 487 identiques.** La suite produit 510 signaux. Avec le partage : 2 216 recherches de jeton
partagées et 4 368 adresses remplacées par l'instance du profil.

## 5. Collision forcée

- **Pipeline complet**, toutes les empreintes de jeton identiques (hachage constant), puis la même chose avec une table
  de 2 jetons seulement, qui oublie et réenregistre sans cesse : **4 487 sur 4 487 éléments identiques** à la référence.
- **Jetons proches** : même symbole sur un autre contrat, même contrat sur un autre réseau, même contrat avec d'autres
  décimales, même contrat avec un autre symbole, natifs de chaque réseau. Avec un hachage constant, chaque valeur garde
  sa propre instance ; deux valeurs distinctes ne partagent **jamais** une instance.
- **Adresses** : deux adresses dont les codes de hachage sont égaux (`"Aa"` et `"BB"`), et la même valeur sur Ethereum
  et Polygon. Elles ne sont jamais confondues, et une source qui renverrait une autre valeur est ignorée.

## 6. Banc de réorganisations R01a–e

`DetectionBenchTest` relancé sans aucune modification des scénarios ni des attentes :

| Scénario | Résultat |
|---|---|
| R01a — entrée retirée | 1/1 |
| R01b — structuration toujours valide | 1/1 |
| R01c — structuration invalidée | 1/1 |
| R01d — exposition retirée | 2/2 |
| R01e — réinclusion | 1/1 |

Bilan global du banc **identique à avant** : 16 vrais positifs, 0 faux positif, 8 faux négatifs (capacités sans
détecteur), 11 vrais négatifs ; précision 1,00, rappel 0,67.

## 7. Autres tests et `mvn verify`

| Test | Vérifie |
|---|---|
| éviction et réinclusion | couvertes par le test différentiel (140 évictions, 137 retraits, 30 réinclusions) |
| `tokenTableStaysBoundedAndConcurrentLookupsAgree` | 1 000 jetons pour une capacité de 100 : la table reste à 100 ; 8 fils × 20 000 recherches : toujours la bonne valeur, une seule instance par valeur |
| `concurrentIngestionGivesTheSameProfilesAsSequentialIngestionWithoutSharing` | 6 fils, 20 000 flux : profils et flux **identiques** à une ingestion séquentielle sans partage ; au plus une instance par fil pour une même adresse |
| `canonicalAddressesAreReleasedWithTheirProfileAndFlows` | une fois le profil et ses flux évincés, l'adresse est libérée par le GC (référence faible) : le mécanisme ne retient rien |
| profils et soldes | profils complets et soldes de chaque événement comparés dans le test différentiel |
| `OrphanKeyReportTest` | comptage exact des clés orphelines, des clés de transaction et des répartitions ; lecture seule |
| `ProfileKeyDiagnosticTest` | endpoint : 401 sans clé, 403 en lecture et en analyste, 200 en administrateur |
| `mvn -B clean verify` | **BUILD SUCCESS, 563 tests, 0 échec, 0 ignoré** (554 avant, + 9) |

## 8. Comparaison mémoire avant/après

**Laboratoire uniquement.** Même flux synthétique de 200 000 transferts TRON, objets neufs par événement comme un
décodeur, 2 jetons, 2 000 adresses très actives et 120 000 adresses moins actives. Détecteurs par défaut, JDK 25.
Histogramme de classes interne (`DiagnosticCommand`, après GC complet).

| | Sans partage | Avec partage | Écart |
|---|---:|---:|---:|
| Instances `Token` | 200 000 | **2** | −199 998 |
| Instances `Address` | 400 000 | **97 588** (= nombre de profils) | −75,6 % |
| Chaînes (`String`) | 1 864 982 | 1 162 634 | −37,7 % |
| Octets de chaînes (`byte[]` + `String`) | 184,8 Mo | 135,0 Mo | −49,8 Mo |
| Objets au total | 10 317 850 | 8 402 587 | −18,6 % |
| **Heap vivant** | **463,5 Mo** | **399,3 Mo** | **−64,3 Mo (−13,9 %), −321 o par flux** |
| Coût de la stratégie (table des jetons) | – | 2 entrées (≤ ≈ 1 Mo au plafond) | |
| Durée d'ingestion (1 essai chacun, non significatif) | 196,7 s | 136,5 s | |

Le résultat est identique dans les deux cas : 97 588 profils et 29 411 signaux.

**Production : non mesurée** (rien n'est déployé). Le gain de ≈ 210 Mo estimé dans l'analyse **n'est pas acquis**. Le
laboratoire montre −321 o par flux : extrapoler à 550 000 flux donnerait ≈ 175 Mo, mais la part réelle dépend de la
réutilisation des adresses et des jetons en production. À vérifier par l'histogramme de la mesure de 12 heures.

## 9. Diagnostic des clés orphelines

**Méthode.** `GET /api/v1/admin/working-set/profile-keys`, calculé uniquement à la demande. Pour chaque profil :

1. Les clés sont copiées sous le verrou de ce seul profil, puis vérifiées hors verrou contre le stock de flux. Aucun
   verrou n'est jamais tenu sur deux structures à la fois.
2. Une clé d'événement est **orpheline** si son flux n'est plus en mémoire. Les clés de transaction (`…:tx`) sont
   comptées à part : ce ne sont jamais des identifiants de flux.
3. Un hachage de transaction est orphelin si aucune clé d'événement vivante du même profil ne le porte.

**Résultat retourné** :

- nombre de clés orphelines ;
- estimation du nombre d'identifiants **distincts** (une clé partagée par deux profils compte deux fois dans les clés).
  L'estimation se fait par échantillonnage : 1 identifiant sur 64, sur le hachage ;
- taille majorée (entrée + chaîne Latin-1) ;
- répartition des profils par nombre d'orphelins (0, 1, 2–4, 5–16, 17–256, 257–4 096, plus) ;
- répartition des orphelins par inactivité du profil (≤ 1 h, 6 h, 24 h, 3 j, 7 j, plus). Une clé est au moins aussi
  ancienne que l'inactivité de son profil : c'est une **borne basse** de sa durée de conservation ;
- statistiques du partage des instances.

**Validation en laboratoire** (après éviction de la moitié la plus ancienne des 200 000 flux) :

- 97 588 profils, 399 981 clés d'événements, dont 199 992 orphelines ;
- 100 000 flux évincés en réalité, contre 103 936 identifiants distincts estimés (erreur de l'échantillonnage : 4 %) ;
- taille majorée : 32,0 Mo ;
- 199 992 hachages de transaction orphelins ;
- résultats identiques avec et sans partage.

**Coût.**

- Aucun coût permanent : aucune mémoire et aucune tâche périodique.
- Par appel, en laboratoire : 207 à 264 ms pour 400 000 clés.
- En production, ≈ 2 s **estimées** pour ≈ 2,7 M clés (extrapolation linéaire).
- Mémoire temporaire par appel : copie des clés d'un seul profil (≤ 8 192 références) et échantillon de 1/64 des
  orphelins, soit **≤ 2 Mo estimés**.
- Le diagnostic appelle `flows.contains`, synchronisé, une fois par clé : l'ingestion peut être ralentie pendant ces
  ≈ 2 s.

**Limites de précision.**

- Les tailles sont **majorées** : une chaîne aussi détenue ailleurs (l'autre profil, le livre des soldes) est comptée
  pour chaque détenteur ; l'estimation des identifiants distincts corrige en partie.
- La durée de conservation n'est connue que comme borne basse, par l'inactivité du profil : les clés ne portent pas de date.
- Le résultat est un instantané : il change pendant le calcul si l'ingestion continue.

**Pas de vidage du heap en production**, comme demandé. Je ne le propose pas, car ce diagnostic suffit à décider
entre C et D.

## 10. Risques résiduels et limites

1. **Partage incomplet sous concurrence.** Une adresse sans profil, vue en même temps par deux fils, garde deux
   instances égales jusqu'à la création du profil. Seule la mémoire est concernée, sans effet fonctionnel (test de
   concurrence).
2. **Profil recréé.** Après l'éviction puis le retour d'un wallet, les nouveaux flux partagent une nouvelle instance ;
   les anciens flux encore en mémoire gardent l'ancienne. Gain un peu moindre, aucun effet fonctionnel.
3. **Hors du partage :** les adresses des transactions natives au niveau du profil (déjà l'instance du profil), les
   adresses des signaux et des étiquettes créées hors flux, et la clé de jeton `réseau:contrat` recalculée par profil
   (≈ 30 Mo estimés dans l'analyse). Non traité ici, pour rester dans le périmètre.
4. **Le gain de production n'est pas mesuré.** Il dépend de la réutilisation réelle des adresses.
5. **Le diagnostic** ralentit brièvement l'ingestion pendant son calcul. À appeler hors période de rattrapage.
6. Les mesures de laboratoire viennent d'**un seul essai** par variante. Les durées d'ingestion ne sont pas
   significatives.

Les bancs de mesure de cette étape sont hors du dépôt (scratchpad) : `KeyProbe.java` et `CanonProbe.java`. Les bancs
exploratoires existants n'ont pas été modifiés.
