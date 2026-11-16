# Fund Flow Trace — décisions à valider

Synthèse du 2026-11-12, préparée pendant la mesure mémoire de 12 h. Code de référence : `94ea42c`. Aucune implémentation,
aucune migration, aucun ADR modifié, journal non activé. Les détails et les références au code sont dans les documents
cités ; **[F]** fait vérifié, **[E]** estimation, **[R]** recommandation.

Documents sources :
- `FUND_FLOW_TRACE_ARCHITECTURE.md` ;
- P0-A `…_P0_A_EXPOSURE.md`, P0-B `…_P0_B_COVERAGE.md`, P0-C `…_P0_C_DIRECTED_GRAPH.md`, P0-D `…_P0_D_ENGINE.md` ;
- `FUND_FLOW_TRACE_TENANT_ISOLATION.md`, `FLOW_JOURNAL_CAPACITY_ANALYSIS.md` ;
- `ADR_0022_REVIEW.md`, `ADR_0023_REVIEW.md`, `ADR_0026_REVIEW.md`.

---

## D1. Isolation multi-client : périmètre minimal

**Problème.** Aucune notion de client n'existe [F] (`V1__operator_state.sql:5`) : aucune colonne, aucun principal, aucun
test d'accès croisé. Une fuite existe déjà en mono-client : les adresses consultées par quiconque
(`address_history_progress`) sont chargées et visibles de toutes les clés [F].

**Options.**

| Option | Avantages | Inconvénients |
|---|---|---|
| a. Une instance par client, sans `scope_id` | rien à coder maintenant | passage au multi-client = réécriture ; aucune preuve testée |
| b. Une instance par client **et** `scope_id` dès les tables du traçage, principal portant le scope, filtrage dans les repositories, 12 tests négatifs | isolation prouvée dès la première API de traçage ; extension naturelle | migration et plomberie (clés, utilisateurs, tables du traçage) |
| c. b + RLS PostgreSQL et `scope_id` sur toutes les tables existantes | défense en profondeur complète | coût élevé maintenant, bénéfice nul tant qu'une instance sert un client |

**[R] Option b**, avec la RLS sur les tables du traçage dès leur création. Les autres tables (alertes, règles, liste de
surveillance) suivent avant toute offre multi-client hébergée.

**Conséquences.**
- Mémoire : nulle (métadonnée).
- Performances : un prédicat de plus par requête indexée.
- Sécurité : condition préalable à toute API de traçage. Les tests N1 à N12 font partie de la définition de « terminé ».

## D2. ADR-0022 : persistance des investigations

**Problème.** Les résultats ne sont ni conservés ni reproductibles [F]. Une même requête donne un autre résultat une heure
plus tard, sans trace de l'écart.

**Options.**
- a. ADR tel quel : module, plan d'étapes, décisions humaines, rapports.
- b. Instantané JSON seulement.
- c. **Artefact de traçage** : requête, limites, version du moteur, couverture, flux gelés, résultat, statut, `scope_id`,
  statut `STALE` après une réorganisation et diff entre deux exécutions.
- d. Ne rien persister.

**[R] Option c** : accepter l'ADR amendé. « Rejouable » signifie « explicable depuis les preuves gelées » ; un recalcul
est une nouvelle version.

**Conséquences.**
- Mémoire : rien dans la mémoire de travail ; un traçage est borné par ses limites (≈ 14 Mo pour 20 000 flux [E]).
- Performances : asynchrone, hors du chemin d'ingestion.
- Sécurité : isolation D1 obligatoire, exports audités, budgets contre l'épuisement des quotas RPC.

## D3. ADR-0023 : niveaux de preuve et couverture

**Problème.** `EvidenceLevel` (ANOMALY, RISK_SIGNAL, CONFIRMED_BY_SOURCE) ne distingue pas un fait observé d'une
inférence [F]. PREDICTED et SIMULATED n'ont aucun producteur [F].

**Options.**
- a. Cinq niveaux, comme le propose l'ADR.
- b. Statu quo, avec un niveau OBSERVED ajouté.
- c. **Trois niveaux** (OBSERVED, INFERRED, CONFIRMED) et **une couverture comme axe séparé**.

**[R] Option c**, avec trois règles :
- passage à CONFIRMED seulement par une source nommée et fiable, ou par une validation humaine tracée ;
- projection construite à la lecture ;
- « aucun résultat » interdit sans couverture COMPLETE.

**Conséquences.**
- Mémoire : nulle si projection à la lecture. Point d'attention : `Signal$Evidence` pèse déjà 43 Mo dans l'histogramme
  de référence [F].
- Performances : négligeables.
- Sécurité : réduit le risque de sur-affirmation, qui est un risque juridique.

## D4. ADR-0026 : périmètre client et partage des données publiques

**Problème.**
- L'analyse de la chaîne entière ne regarde qu'environ 2 h en arrière en régime établi [F].
- La liste de surveillance de production est vide [F].
- Persister toute la chaîne coûte trop cher (D5).

**Options.**
- a. ADR tel quel.
- b. Journal de toute la chaîne.
- c. Instance par client sans `scope_id`.
- d. **ADR amendé.**

**[R] Option d**, avec quatre amendements :
- traçages et exports isolés ;
- demandes d'historique liées au scope qui les fait ;
- **partage des événements publics rapatriés**, sans partage de la couverture affichée ni de l'intérêt d'un client ;
- trous d'indexation déclarés dans les verdicts.

**Décision connexe à trancher : limites de traçage.**
- Fait constaté : l'ADR-0026 fixe 3 sauts et 500 nœuds pour l'analyse à la demande, alors que P0-D et l'architecture
  proposent 3 sauts et 500 nœuds **par défaut**, avec des plafonds configurables de 8 sauts et 5 000 nœuds.
- **[R]** Garder les valeurs de l'ADR comme plafonds pour l'API publique. Les plafonds plus élevés ne s'appliquent qu'à
  une investigation explicitement lancée par une permission `investigation:write`, avec des budgets et dans un
  traitement asynchrone.

**Conséquences.**
- Mémoire : C ≤ 300 o par entrée selon l'ADR [E]. À confirmer par l'histogramme de la mesure en cours, car le profil
  complet a aujourd'hui environ 1 240 o de surcoût fixe [F].
- Disque : 1,3 à 12 Go sur 13 mois [E].
- Sécurité : risque d'accès croisé et de divulgation par effet de bord (voir D1).

## D5. Journal PostgreSQL : rétention, index, partitions, disque

**Problème.** Le journal est désactivé [F]. Son rechargement a pris 316 s pour 50 000 flux [F]. Sa purge est un `DELETE`
unique [F]. Il n'a aucun index par adresse [F].

**Faits mesurés.**
- 474 o par ligne avec 2 index [F] ; environ 650 o avec les index d'adresse [E].
- 4,8 M flux par jour [F].
- Disque de la VM Docker : 126 Go, **33 Go libres** [F].
- PostgreSQL avec ses réglages par défaut (`shared_buffers` 128 Mo) [F].

**Options.**

| Option | Disque [E] | Avantages | Inconvénients |
|---|---|---|---|
| a. Chaîne entière, 48 h | 6 à 7 Go | traçage sur 2 jours | profondeur faible |
| b. Chaîne entière, 7 jours | 25 à 28 Go | traçage sur 7 jours | **75 % du disque libre de la VM** ; disque à agrandir |
| c. Chaîne entière, forte charge, 14 jours | 85 à 95 Go | — | instance dédiée |
| d. **Périmètre client (ADR-0026), 13 mois** | 1,3 à 12 Go | profond là où c'est utile, peu coûteux | hors périmètre : mémoire et historique à la demande seulement |

**[R] Option d.** Quelle que soit l'option, quatre prérequis :
- partitions journalières, purge par `DROP` ;
- index `(network, from_address, ts)` et `(network, to_address, ts)` ;
- filigrane de couverture et plus de rechargement complet au démarrage ;
- une mesure dédiée de 48 h avant toute rétention plus longue.

**Conséquences.**
- Mémoire : aucune si le rechargement complet est supprimé.
- Performances : environ 56 écritures par seconde en moyenne pour toute la chaîne, faible pour PostgreSQL. L'impact
  sur les indexeurs (même VM) est **à mesurer**.
- Sécurité : flux du périmètre isolés par `scope_id`.

## D6. `/exposure` : le champ `share`

**Problème.** Aujourd'hui `share` additionne les montants humains de **tous les jetons** [F]
(`InvestigationService.java:272-273`, `:293-294`). Il n'a donc pas d'unité.

**Options.**

| Option | Avantages | Inconvénients |
|---|---|---|
| a. Garder `share` et changer son sens (part par jeton) | un seul champ | consommateurs existants trompés en silence |
| b. **Nouveau champ `shareByToken` (ou montants par jeton) ; `share` conservé, déprécié et documenté** | changement explicite, v1 non cassée | deux champs pendant une version |
| c. Supprimer `share` | net | rupture du contrat v1 |

**[R] Option b**, avec les montants exacts par jeton, et sans jamais additionner deux jetons. L'onglet Exposure affiche
les nouveaux champs ; `share` est retiré à la prochaine version majeure de l'API.

**Conséquences.**
- Mémoire et performances : nulles.
- Sécurité : aucune.
- Exactitude : corrige une valeur sans unité.

## D7. Bloc et index de log dans les flux

**Problème.** `ValueFlow` n'a ni numéro de bloc ni index de log [F]. Ces données existent pourtant en amont
(`TokenTransfer`, `ChainEvent`, `address_history_events`) [F]. Sans elles, la preuve est incomplète et l'ordre des
transferts d'un même bloc reste indéterminé.

**Options.**

| Option | Mémoire [E] | Avantages | Inconvénients |
|---|---|---|---|
| a. Ajouter `blockNumber` (long) et `logIndex` (int) à `ValueFlow` | +16 o par flux, soit environ 6 Mo pour 400 000 flux | preuve complète partout, ordre intrabloc | touche la structure mesurée par la mesure de 12 h |
| b. Garder `ValueFlow` ; retrouver le bloc à la demande par le reçu de transaction, dans un budget | aucune | aucun risque mémoire | coût RPC, quotas, latence ; impossible hors ligne |
| c. Les stocker seulement dans le journal et les tables du traçage | aucune | preuve complète pour le traçage | flux en mémoire toujours sans bloc |

**[R] Option c d'abord** (aucun impact sur la mesure). Puis **option a après la validation de la mesure de 12 h**, avec un
histogramme dédié avant et après : le gain d'exactitude (ordre intrabloc, réorganisations) justifie environ 6 Mo, à
vérifier.

**Conséquences.**
- Mémoire : négligeable, mais à mesurer.
- Performances : nulles.
- Sécurité : aucune.
- Prérequis pour : corriger le double comptage TRC-20 (P0-B § 1.5), ordonner les flux d'un même bloc (P0-A R14, P0-C T5)
  et reconnaître un transfert réinclus (P0-D scénario 15).

---

## Dépendances

```
D1 isolation ──────────────► toute API de traçage (P1)
D3 (ADR-0023) ─────────────► D2 (ADR-0022) ─► API de traçage
D4 (ADR-0026) ─────────────► D5 (journal) ─► lecture des flux persistés par le moteur
P0-B identité des transferts ► P0-D (dédoublonnage) ─► P0-C (graphe orienté construit sur le moteur)
P0-A : indépendant ; son champ de couverture vient de P0-B
D7 option a ◄── fin et validation de la mesure de 12 h
```

## Risques

| Risque | Gravité | Origine | Traitement |
|---|---|---|---|
| Double comptage TRC-20 (direct + historique TronGrid) | élevée (montants faux) | `TronGridProvider.java:505-516` [F] | identité de transfert P0-D, avant tout montant tracé sur TRON |
| Trous d'indexation invisibles (reprise > 7 200 blocs) | élevée (analyse partielle présentée comme complète) | `Bootstrap.java:410-423` [F] | table des trous, couverture P0-B |
| Accès croisé entre clients | élevée dès qu'il y a plusieurs clients | aucun scope [F] | D1 |
| Sur-affirmation (inférence présentée comme fait) | élevée (juridique) | `EvidenceLevel` insuffisant [F] | D3 |
| Disque de la VM saturé par le journal | élevée | 33 Go libres [F] | D5 |
| Quotas RPC épuisés (TronGrid 429 observé) | moyenne | journaux du 2026-11-12 [F] | budgets par traçage et par scope |
| Exposition actuelle trompeuse (poussière, faux jetons, ordre) | moyenne | P0-A [F] | P0-A |
| Solde historique TRON indisponible | moyenne | limites déclarées [F] | intervalle d'attribution P0-D |
| Mémoire de l'ingestion perturbée par le traçage | moyenne | — | aucun flux de traçage dans la mémoire de travail |

## Ordre recommandé (après validation de la mesure de 12 h)

1. D1 minimal + D3 (ADR-0023 amendé).
2. P0-A (cas de régression d'abord).
3. P0-B : table des trous, identité des transferts, D7 option c.
4. P0-D avec ses 15 scénarios.
5. P0-C sur le moteur.
6. P1 : D2, puis D5 selon D4.

Aucune de ces étapes ne commence sans ta validation explicite.
