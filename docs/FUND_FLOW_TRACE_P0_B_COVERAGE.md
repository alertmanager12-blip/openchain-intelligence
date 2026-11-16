# Fund Flow Trace P0-B — couverture et preuves

Statut : **en cours (2026-11-16)**. Fait : trous de reprise et début de couverture continue enregistrés (V13,
`IndexingCoverage`, cas C3 et C8) ; identité des transferts TRC-20 vus en direct et par l'historique (`TransferIdentity`,
cas C7, métrique `oci_history_duplicates_total`). Reste : calcul de la couverture par adresse en segments et son usage
dans `/exposure` (C1, C2, C4 à C6, C9) ; D7 option c.

Préparation (2026-11-12, code de `94ea42c`) : **[F]** fait vérifié (référence) ;
**[P]** proposition.

## 1. Ce qui existe

### 1.1 Sources de flux d'une adresse [F]

| Source | Contenu | Durée de vie | Couverture connue ? |
|---|---|---|---|
| Indexation en direct | tous les transferts de jetons et natifs des blocs indexés | mémoire de travail : 24 h au plus, plafonnée à 400 000 flux (ADR-0024 3b) ; perdue au redémarrage (journal désactivé) | **non** : aucun champ « depuis quand » |
| Journal `value_flows` (V9) | flux écrits en différé, restaurés au démarrage | 7 jours ; **désactivé par défaut** (`OciProperties.java:398`), dernière ligne en base : 2026-11-09 | non |
| Historique d'adresse (ADR-0020) | transferts ERC-20 (eth_getLogs) ou TRC-20 + natifs (TronGrid) d'**une** adresse, dans `[from, to]` avec `to = premier bloc indexé en direct − 1` (`AddressHistoryService.java:250-258`) | persisté (V4) ; réappliqué au démarrage ; flux de ces adresses jamais purgés de la mémoire | **oui** : `HistoryCoverageDto` (`Dto.java:70-73`) |

### 1.2 `HistoryCoverageDto` [F]

Champs : `status` (PENDING, RUNNING, COMPLETE, TRUNCATED, FAILED, plus NOT_CONFIGURED et « PENDING » quand aucune ligne
n'existe), `sources`, `coveredFromBlock`, `coveredToBlock`, `coveredFromTime`, `tokenTransfersCovered`,
`approvalsCovered`, `nativeCovered`, `internalCovered`, `truncated`, `eventsFound`, `message`, `updatedAt`.
Limites : 500 000 blocs en arrière, 200 pages, 5 000 événements ; au-delà → TRUNCATED, événements les plus récents
gardés (`AddressHistoryService.java:193-216`). Indisponibilité du fournisseur → TRUNCATED avec « provider history
limit » ; autre erreur → FAILED (reprise au prochain `ensure()`).

### 1.3 Trous non déclarés [F]

| Trou | Où il est connu | Exposé ? |
|---|---|---|
| Blocs sautés à la reprise (`max-resume-gap` 7 200) | une ligne WARN (`Bootstrap.java:410-423`) ; le point de reprise est écrasé | **non** : ni table, ni métrique, ni API. Exemple réel : TRON 87 899 299..87 943 187, Ethereum 26 386 430..26 400 192 (2026-11-12) |
| Flux purgés de la mémoire de travail | compteurs de purge | non, par adresse |
| Période entre fin d'historique et début de l'indexation après un redémarrage | `firstIndexedBlock` en mémoire, non persisté (`ChainIngestionService.java:130, 307`) | non |
| Transactions internes EVM, TRC-10 | `InvestigationService.limitations()` (page wallet) | oui sur la page wallet, **pas** sur le graphe ni l'exposition |
| Solde historique TRON | idem | idem |

### 1.4 Preuves disponibles par flux [F]

| Champ | `ValueFlow` (mémoire) | `value_flows` (journal) | `address_history_events` | Source amont |
|---|---|---|---|---|
| Réseau, hash, horodatage, de, vers, jeton, montant brut | oui | oui | oui (`fields` JSON) | — |
| Numéro de bloc | **non** | non | oui ; **−1 pour TRC-20 TronGrid** | `ChainEvent`, `TokenTransfer`, `ChainTransaction` |
| Hash de bloc | non | non | oui (null TronGrid) | idem |
| Index de log | non | non | non | `TokenTransfer.logIndex` (l'`eventId` live vaut `net:tx:logIndex`) |
| Provenance (direct, journal, historique) | non | non | implicite (table) | — |

### 1.5 Défaut d'identité : double comptage TRC-20 [F]

`TronGridProvider.java:505-516` le documente : TronGrid ne donne ni bloc ni index de log, donc l'identifiant historique
d'un transfert TRC-20 est `tron:<txid>:trc20:<hash(from|to|contrat|valeur)>:<n>`, différent de l'identifiant live
`tron:<txid>:<logIndex>`. **Un même transfert vu par les deux sources compte deux fois.** Par conception les plages ne se
recouvrent pas (`to = premier bloc live − 1`), mais :
- TronGrid filtre par **horodatage** converti depuis les blocs : les bords peuvent se recouvrir ;
- après un redémarrage, le « premier bloc live » repart de zéro (non persisté) : avec le journal activé, des flux live
  restaurés et un historique relancé peuvent couvrir les mêmes blocs ;
- deux transferts identiques d'une même transaction répartis sur deux pages partagent `n = 0` (sous-comptage).

Ce défaut touche les montants, les attributions et les expositions : il doit être corrigé (P0-D, § dédoublonnage) avant
de présenter des montants tracés sur TRON comme exacts.

## 2. Modèle de couverture proposé [P]

### 2.1 États par adresse, par réseau et par plage

| État | Définition stricte (doit être démontrée, jamais supposée) |
|---|---|
| `COMPLETE` | toute la plage demandée est couverte par des sources dont la fin est confirmée : historique COMPLETE jusqu'à `to`, puis indexation continue sans trou jusqu'à la fin de la plage |
| `PARTIAL` | une partie de la plage est couverte ; les sous-plages manquantes sont listées |
| `TRUNCATED` | une source a atteint une limite (pages, événements, profondeur du fournisseur) ; la plage réellement couverte est donnée |
| `UNAVAILABLE` | aucune source ne peut fournir la plage (réseau non configuré, fournisseur sans historique, échec) |
| `NOT_YET_INDEXED` | la plage dépasse le dernier bloc indexé, ou tombe dans un trou de reprise connu |

Et pour chaque type de données : `tokenTransfers`, `native`, `internal` (aujourd'hui toujours absent), `approvals`.

### 2.2 Structure

```
Coverage {
  address, network, requested: {fromBlock|fromTime, toBlock|toTime},
  state,                                   // le pire des segments
  segments: [ { fromBlock, toBlock, fromTime, toTime, source: LIVE|JOURNAL|HISTORY|NONE,
                state, reason } ],         // ordonnés, sans chevauchement
  kinds: { tokenTransfers, native, internal, approvals },
  knownGaps: [ { fromBlock, toBlock, cause: RESUME_GAP|EVICTED|PROVIDER_LIMIT|NOT_INDEXED } ],
  limitations: [ texte ],
  computedAt, sourcesVersion
}
```

Règle : `state = COMPLETE` uniquement si la concaténation des segments couvre la plage demandée **sans trou** et que
chaque segment est confirmé. Tout autre cas est `PARTIAL` au mieux. Le texte « aucun résultat » n'est permis qu'avec
`COMPLETE` ; sinon « aucun résultat dans les données couvertes du … au … ».

### 2.3 Données nécessaires (et ce qui manque aujourd'hui)

1. **Trous de reprise persistés** : table `indexer_gaps (network, from_block, to_block, detected_at, cause)` écrite par
   `guardResumeGap` au lieu du seul log. Migration additive, sans effet sur l'indexation.
2. **Début de couverture continue live** persisté par réseau (le premier bloc indexé depuis le dernier trou), pour
   relier historique et direct.
3. **Plage purgée** : pour une adresse non exemptée, la mémoire ne garde que les flux postérieurs au dernier seuil de
   purge du réseau ; ce seuil est connu à chaque purge et peut être exposé (`oci_working_set` le calcule déjà).
4. Tout cela reste du **métadonnée de couverture** : aucune donnée de flux supplémentaire en mémoire.

## 3. Preuves : modèle cible [P]

Un flux de preuve porte : `network`, `txHash`, `blockNumber` (nullable), `blockHash` (nullable), `logIndex` (nullable),
`timestamp`, `from`, `to`, `tokenKey` + symbole + décimales, `rawAmount`, `eventId`, `provenance` (LIVE, JOURNAL,
HISTORY:<source>), `confirmed` (bloc ≤ tête − confirmations), `sourceIds` (identifiants vus pour ce même transfert, voir
dédoublonnage).

- **Arêtes du graphe : tous les hashes**. Aujourd'hui `GraphEdgeDto` garde le dernier hash et un compteur
  (`InvestigationService.java:353-377`). Cible : l'arête garde la liste des `eventId` (en mémoire, le graphe est déjà borné
  à 500 nœuds) et l'API pagine les preuves d'une arête (`/graph/.../edges/{id}/evidence`), pour ne pas gonfler la
  réponse : la réponse du graphe porte le nombre de preuves et les N premières.
- **Bloc et index de log dans `ValueFlow`** : environ +16 o par flux (≈ 6 Mo pour 400 000 flux) ; **après** la mesure
  de 12 h et avec une mesure d'histogramme dédiée. En attendant, la preuve d'un flux live peut retrouver le bloc par le
  reçu de transaction (`getTransactionReceipt`, déjà utilisé par l'analyse de transaction), à la demande et dans un
  budget.

## 4. Cas de test [P]

| # | Cas | Attendu |
|---|---|---|
| C1 | historique COMPLETE + indexation continue | `COMPLETE` |
| C2 | historique TRUNCATED à 5 000 événements | `TRUNCATED`, plage réelle, raison |
| C3 | trou de reprise dans la plage | `PARTIAL`, `knownGaps` = le trou exact |
| C4 | adresse jamais consultée, flux en mémoire depuis 3 h | `PARTIAL`, segment LIVE de 3 h, reste `NONE` |
| C5 | plage au-delà du dernier bloc indexé | `NOT_YET_INDEXED` pour la sous-plage |
| C6 | réseau sans source d'historique | `UNAVAILABLE` hors segment live |
| C7 | même transfert TRC-20 vu live et historique | compté une fois (dépend de P0-D) |
| C8 | redémarrage : début de couverture live persisté | segments continus et corrects |
| C9 | texte du vide | interdit de dire « aucun » sans `COMPLETE` |

## 5. Risques

- Faux sentiment de complétude si un segment est mal raccordé : la règle `COMPLETE` est volontairement stricte et testée.
- Les trous de reprise antérieurs à la migration ne sont connus que par les journaux : ils seront marqués « inconnu
  avant <date de migration> ».
- Coût : métadonnées seulement ; calcul de la couverture d'une adresse en O(segments).
