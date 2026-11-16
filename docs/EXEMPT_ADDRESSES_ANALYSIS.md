# Étape 2 : analyse des adresses exemptées de purge

Date : 2026-11-11. **Analyse seule : aucune modification du comportement du système.** Sources : base PostgreSQL de
production (tables `address_history_progress`, `address_history_events`, `watchlist`), métriques Prometheus, série de
la mesure 3b (`docs/ADR-0024-STEP-3B-VALIDATION.md`), code (`AddressHistoryService.historyAddresses`,
`Bootstrap.trimWorkingSet`) et une mesure en laboratoire (`FlowFootprintProbe`, banc de mesure non commité). La clé
d'API de l'instance n'a pas été utilisée (accès refusé, non contourné) : aucune mesure par adresse via l'API.

**Correction** : le rapport 3b indiquait 10 historiques chargés ; il y en a **65** (affichage tronqué de ma requête).
Corrigé dans le rapport 3b.

## 1. Ce qui rend une adresse exemptée

`Bootstrap.trimWorkingSet` exempte une adresse si elle est dans la liste de surveillance **ou** dans
`address_history_progress` (`AddressHistoryService.historyAddresses()`, **tous statuts confondus**). Une ligne y est
créée **dès qu'une fiche de wallet, ses transactions ou son risque sont consultés** (`InvestigationService.ensureHistory`).
Conséquence : toute adresse consultée une fois devient exemptée **pour toujours**, que son historique ait été chargé ou
non. Les flux d'une adresse exemptée ne sont jamais purgés ni comptés dans le plafond de 400 000.

## 2. Nombre et motifs (mesurés, 2026-11-11)

| Motif | Adresses | Événements d'historique chargés | Apport analytique de l'exemption |
|---|---:|---:|---|
| Liste de surveillance | 1 | – | indispensable (périmètre client, ADR-0026) |
| Historique TRON terminé (COMPLETE) | 24 | 5 759 | réel : historique complet de wallets consultés |
| Historique TRON tronqué (TRUNCATED, plafond de 5 000 atteint) | 6 | 25 398 | partiel : adresses très actives, historique incomplet |
| Historique Ethereum tronqué sans événement | 18 | 0 | **aucun** : rien n'a été chargé |
| Historique en échec (FAILED) | 17 (12 Ethereum, 5 TRON) | 0 | **aucun** |
| **Total distinct** | **65** (la seule adresse surveillée est aussi un historique en échec) | 31 157 (41 Mo en base) | |

Sur 65 adresses exemptées, **35 (54 %) le sont sans aucun historique chargé**. Deux lignes TRON portent une adresse au
format Ethereum (`0x…`), issues d'une détection de réseau erronée : sans flux, donc sans coût, mais sans objet.

## 3. Évolution dans le temps

| Jour | Nouvelles adresses exemptées | Cumul |
|---|---:|---:|
| 2026-11-07 | 35 | 35 |
| 2026-11-08 | 8 | 43 |
| 2026-11-09 | 10 | 53 |
| 2026-11-10 | 8 | 61 |
| 2026-11-11 (matin) | 4 | 65 |

Le nombre d'adresses croît avec l'usage de la console (4 à 10 par jour) et ne décroît jamais. Le nombre de **flux**
exemptés croît avec l'activité de ces adresses. Borne basse mesurée : le plafond des flux purgeables est exact (étape
3b), donc après une purge, total − 400 000 ≤ flux exemptés.

| Moment | Flux exemptés (borne basse) |
|---|---:|
| 2026-11-10 20:52 | 34 710 |
| 21:54 | 48 748 |
| 22:54 | 69 382 |
| 2026-11-11 09:11 (après la mise en veille, rattrapage) | 74 977 |
| 09:57 | **104 601** |

Rythme : ≈ 17 700/h pendant la mesure 3b, ≈ 38 000/h pendant le rattrapage qui a suivi le réveil.

## 4. Consommation mémoire

**Mesurée en laboratoire** (`FlowFootprintProbe`, objets construits comme en production : un identifiant, une adresse
et un jeton par événement) : **703 octets par flux** dans le stock de flux ; **1 881 octets** en comptant le profil d'une
contrepartie nouvelle à chaque flux (pire cas ; ces profils-là sont plafonnés par ailleurs) ; **1 057 octets par
signal** (4 éléments de preuve).

**Estimée en production** (borne basse × coût mesuré) :

| Moment | Flux exemptés | Mémoire des flux exemptés | Données vivantes après GC | Part |
|---|---:|---:|---:|---:|
| fin de la mesure 3b (23:04) | ≥ 72 784 | ≥ 51 Mo | 1 876 Mo | ≥ 2,7 % |
| 2026-11-11 09:57 | ≥ 104 601 | ≥ 74 Mo | ≈ 2 050 Mo (09:15) | ≥ 3,6 % |

À cela s'ajoutent les profils des 65 adresses exemptées (non évincés ; bornés par adresse depuis l'étape 3b, au plus
≈ 1 à 2 Mo pour une adresse très active, dominés par les clés de dédoublonnage) : **estimation** de 6 à 15 Mo pour les
6 adresses très actives, non mesurée.

**Contribution à la croissance** pendant la mesure 3b (estimation, coûts de laboratoire) : flux exemptés ≈ 12 Mo/h,
signaux ≈ 59 Mo/h, total ≈ 71 Mo/h, cohérent avec les 67 Mo/h mesurés. Les adresses exemptées ne sont donc **pas** la
première cause de croissance (c'étaient les signaux, corrigés à l'étape 1), mais leur coût **n'a pas de borne** et croît
avec l'usage.

## 5. Ce qui doit rester exempté

| Catégorie | À conserver ? | Raison |
|---|---|---|
| Liste de surveillance | **Oui, indispensable** | cœur de l'analyse approfondie (ADR-0026) ; ses flux sont la mémoire d'enquête du client |
| Historiques terminés (COMPLETE), récemment consultés | **Oui, pour un temps** | une investigation en cours a besoin de l'historique chargé ; les événements sont aussi en base et rejouables |
| Historiques tronqués avec événements (TRON très actifs) | **Oui, mais bornés** | utiles à l'enquête, mais ce sont eux qui accumulent des flux sans fin |
| Historiques sans événement (tronqués vides, en échec) | **Non** | l'exemption ne conserve rien d'historique ; elle ne fait que garder indéfiniment des flux en direct |
| Lignes invalides (adresse `0x` sur TRON) | **Non** | sans objet |

## 6. Doublons entre mécanismes de conservation

1. **Liste de surveillance et historique** : la seule adresse surveillée a aussi une ligne d'historique (en échec) ; deux
   motifs pour une même exemption, dont un vide.
2. **Base et mémoire** : les 31 157 événements d'historique sont en base (41 Mo, rejoués au démarrage) **et** gardés en
   mémoire comme flux exemptés. La copie en base est nécessaire (reprise après redémarrage) ; la copie en mémoire est la
   copie de travail. Le doublon est justifié tant que l'adresse est en cours d'analyse, pas au-delà.
3. **Exemption sans données** : 35 adresses exemptées au titre de l'historique sans en avoir (motif confondu avec
   « fiche consultée »).
4. Pas de doublon d'objets en mémoire entre le stock de flux et les profils : les identifiants sont partagés par
   référence (seules les entrées des tables sont dupliquées ; voir l'histogramme du rapport 3b § 3.4).

## 7. Proposition de budget (à valider ; rien n'est implémenté)

Principe : l'exemption doit correspondre à une **raison analytique** et avoir une **limite visible**.

| Règle | Proposition | Fondée sur |
|---|---|---|
| R-E1 | N'exempter au titre de l'historique que les adresses dont l'historique a **chargé des événements** (COMPLETE, ou TRUNCATED avec événements) | § 2 : 35 adresses exemptées pour rien |
| R-E2 | Exemption « historique » **limitée dans le temps** : 7 jours après la dernière consultation (nouveau champ `last_viewed_at`) ; les événements restent en base et sont rejoués à la prochaine consultation | § 3 : croissance sans fin avec l'usage ; § 6.2 |
| R-E3 | **Budget par adresse exemptée** : au plus 10 000 flux les plus récents en mémoire (au-delà, en base pour les historiques ; pour la liste de surveillance, la persistance relève de l'ADR-0026) ; dépassement compté et signalé dans la couverture de l'analyse | § 2 et § 3 : adresses TRON très actives |
| R-E4 | **Budget global** des flux exemptés : 150 000 (≈ 105 Mo à 703 octets, ≈ 3,5 % du heap de 3 Go), hors liste de surveillance ; au-delà, l'adresse historique la moins récemment consultée perd son exemption la première | § 4 |
| R-E5 | **Métriques** avant toute limite : flux, signaux et adresses exemptés par motif (`oci_working_set_exempt{reason,kind}`) ; à inclure dans la build de la mesure de 12 heures pour calibrer R-E3 et R-E4 sur des données de production par adresse | limite de cette analyse (pas de mesure par adresse) |

Ordre proposé : R-E5 (instrumentation, sans changement de comportement) dans la build de la mesure de 12 heures ;
R-E1 (correction de motif) ; puis R-E2 à R-E4 calibrées sur les mesures par motif. Le chiffrage de R-E3 et R-E4 est
provisoire tant que R-E5 n'a pas mesuré la répartition réelle par adresse.

## 8. Limites de cette analyse

- Pas de mesure par adresse en production (pas d'accès à l'API ; aucune métrique par motif) : la répartition des flux
  exemptés entre les 65 adresses n'est pas connue.
- Les flux exemptés sont une borne basse.
- Les coûts unitaires viennent du laboratoire (objets équivalents à ceux de la production, JVM différente du conteneur).
- Les signaux et les profils des adresses exemptées ne sont pas chiffrés séparément.
