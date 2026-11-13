# Journal des flux — étude de dimensionnement

Statut : **étude, journal non activé** (2026-11-12, code de `94ea42c`). **[F]** fait vérifié (code, base, mesure) ;
**[E]** estimation à confirmer ; **[P]** proposition.

Précision : ChainSentinel n'utilise **pas MongoDB**. Le journal est une table PostgreSQL 17.11 (`value_flows`, migration
V9). La question « coût des index MongoDB » se transpose ici en coût des index B-tree PostgreSQL.

## 1. Le journal actuel [F]

| Élément | Valeur | Référence |
|---|---|---|
| Activation | `oci.persistence.intelligence-journal`, **false** par défaut ; bean créé seulement si true et en mode JDBC | `OciProperties.java:398`, `PersistenceConfiguration.java:121-124` |
| Chemin d'écriture | l'ingestion **met en file** seulement ; un thread virtuel écrit par lots de 1 000 | `JdbcIntelligenceJournal.java:37, 57, 81-101` |
| File | `ArrayBlockingQueue` de 200 000 ; pleine → enregistrement **perdu et compté** (`oci_journal_dropped_total`, WARN au plus une fois par minute) | `:70-79`, `OciProperties.java:403` |
| Erreur d'écriture d'un lot | lot entier perdu, compté, **sans nouvel essai** | `:94-96` |
| Idempotence | `insert … on conflict (event_id) do nothing` | `:132-141` |
| Réorganisation | suppression par `event_id`, dans la même file que les écritures (jamais avant l'insertion qu'elle annule) | `:108-115` |
| Purges de la mémoire de travail | **non** répercutées (normal : le journal garde ce que la mémoire oublie) | `JournalingStores` |
| Rétention | 7 jours ; `delete … where ts < :before` **unique, non découpé**, 1 h après le démarrage puis toutes les 24 h | `JdbcIntelligenceJournal.java:177-182`, `Bootstrap.java:400-403` |
| Restauration | `where ts >= :since order by ts, event_id` par blocs de 10 000, **tous accumulés dans une seule liste** avant application | `:146-164`, `Bootstrap.java:388-404` |
| Index | clé primaire `event_id`, index `ts` ; **aucun index par adresse** | `V9:4, :15` |
| Signaux, scores | même journal (`signals`, `risk_scores` ; les scores sont insérés à chaque recalcul, sans dédoublonnage) | `:132-141` |

Mesures existantes :
- 4,8 millions de flux par jour, Ethereum et TRON indexés à la tête (2026-11-09, `docs/guides/installation.md`) ;
- restauration de 50 000 flux en **316 s** (ADR-0026, mesure 2026-11-09, étape 2 de l'ADR-0024) : environ 6 ms par flux.

Cette restauration est la raison de la désactivation. À ce rythme, 400 000 flux prendraient environ 42 minutes, et
33 millions sont hors de portée.

## 2. Taille réelle d'une ligne [F]

Mesuré sur la table existante (188 774 lignes, du 2026-10-21 au 2026-11-09), le 2026-11-12 en lecture seule :

| Mesure | Valeur |
|---|---|
| Table (heap) | 60,6 Mo, soit **321 o par ligne** |
| Clé primaire `event_id` (texte de 75 à 80 caractères) | 26,7 Mo, soit **141 o par ligne** |
| Index `ts` | 2,1 Mo, soit **11 o par ligne** (B-tree dédoublonné : beaucoup de flux partagent l'horodatage de leur bloc) |
| **Total** | 89,5 Mo, soit **474 o par ligne** |
| Taille moyenne du tuple | 334 o (Ethereum), 287 o (TRON) |
| Lignes sans contrat (natifs) | 80 796 sur 188 774 (43 %) |

## 3. Ligne et index cibles [E]

Pour le traçage (P0-B, P0-D) :
- colonnes `block_number bigint`, `log_index int`, `provenance smallint` : environ +14 o ;
- index `(network, from_address, ts)` et `(network, to_address, ts)` : clé d'environ 50 à 60 o, plus le pointeur et le
  remplissage, soit **environ 70 à 90 o chacun** (pas de dédoublonnage utile, les adresses varient) ;
- clé primaire : en table partitionnée par date, elle doit contenir la clé de partition, donc `(event_id, ts)`
  (environ +8 o).

**Ligne cible ≈ 650 o** avec 4 index (fourchette 600 à 700 o), **à mesurer** sur un échantillon avant toute décision.

## 4. Scénarios de dimensionnement

Hypothèse de volume : 4,8 M flux/jour mesurés [F]. Le débit d'événements de la mesure en cours (environ 6 400 par
minute pendant la montée en charge, approbations et natifs compris) est cohérent ; il sera confirmé par la fenêtre de
12 h.

| Scénario | Périmètre | Rétention | Lignes | Disque (table + index) [E] | Usage permis |
|---|---|---|---|---|---|
| **Minimal** | toute la chaîne (Ethereum + TRON) | 48 h | 9,6 M | **6 à 7 Go** | traçage sur 2 jours ; restauration interdite au-delà de la fenêtre de travail |
| **Recommandé** | toute la chaîne | 7 jours, partitions journalières | 33,6 M | **20 à 24 Go**, plus environ 20 % de marge (WAL, maintenance) : **25 à 28 Go** | traçage sur 7 jours, base de P0-D |
| **Forte charge** | 2 × le volume (pic de marché, ou BSC/Polygon ajoutés) | 14 jours | 134 M | **85 à 95 Go** | instance PostgreSQL dédiée, disque dédié |
| **Alternative ADR-0026** | flux impliquant la liste de surveillance seulement | 13 mois | scénario PSP : 5 000/jour, soit 2 M ; scénario exchange : 50 000/jour, soit 18 M | **1,3 Go** / **12 Go** | profondeur sur le périmètre client ; le reste de la chaîne n'est pas persisté |

Capacité disponible aujourd'hui [F] :
- **disque de la VM Docker : 126 Go, dont 87 Go utilisés, 33 Go libres**, partagés par toutes les images, volumes et caches de
  construction (dont 20 Go de cache récupérable) ;
- disque de l'hôte : 402 Go libres.

Le scénario recommandé consommerait **les trois quarts de l'espace libre de la VM**. Il faut donc agrandir le disque de la
VM (réglage Docker Desktop, action de l'utilisateur) ou monter un volume dédié **avant** toute activation.

## 5. Coûts

### 5.1 Écriture [E]

- Débit moyen : 4,8 M/jour ≈ 56 lignes/s ; les pics de blocs TRON ou Ethereum peuvent atteindre plusieurs centaines par
  seconde pendant quelques secondes. La file de 200 000 absorbe environ 1 h à la moyenne.
- Amplification : 4 index au lieu de 2, soit environ 2 fois plus d'écritures d'index ; reste faible pour PostgreSQL à ce
  débit.
- Configuration actuelle : `shared_buffers = 128 MB`, `max_wal_size = 1 GB`, `work_mem = 4 MB` [F]. Ce sont les
  valeurs par défaut, sous-dimensionnées pour une table de 30 M lignes. À régler (par exemple 1 Go de `shared_buffers`)
  **en tenant compte de la VM de 7,7 Go déjà partagée** : décision d'exploitation.

### 5.2 Impact sur les indexeurs [F] / [E]

Le chemin chaud ne fait qu'une insertion dans une file (pas d'I/O) [F]. Les risques sont indirects [E] :
- contention d'I/O et de CPU dans la même VM ;
- mémoire : chaque enregistrement en file garde une référence au flux (déjà en mémoire), donc coût marginal faible ; mais
  une file pleine de 200 000 enregistrements retient des flux que la purge aurait libérés.

**À mesurer, pas à supposer** : une mesure dédiée journal activé, après la mesure de 12 h.

### 5.3 Recherches multi-sauts [E]

- Un saut = pour chaque adresse de la frontière, un parcours d'index `(network, from_address, ts)` sur la fenêtre :
  O(log n + k) par adresse.
- Éviter le N+1 : une requête par saut pour toute la frontière (`… where network = $1 and from_address = any($2) and ts
  between …`), avec une limite par adresse via `LATERAL … LIMIT k` pour les adresses de service.
- Ordre de grandeur : quelques millisecondes par adresse peu active ; une adresse d'exchange (des dizaines de milliers de
  flux par jour) doit être bornée par la fenêtre et la limite, et signalée (`SERVICE_LIKE`, P0-D).

### 5.4 Rétention et purge [P]

- Partitionnement par plage journalière sur `ts` (`value_flows_YYYYMMDD`).
- Purge = `DROP` (ou `DETACH` puis `DROP`) de la partition la plus ancienne : instantané, sans gonflement ni long
  `VACUUM`. Le `DELETE` unique actuel sur 4,8 M lignes par jour génère du WAL et du gonflement : à remplacer **avant**
  toute activation à l'échelle de la chaîne.
- Partitions créées d'avance (J+1, J+2) par une tâche planifiée ; une insertion hors partition échoue bruyamment et est
  comptée.

### 5.5 Doublons, idempotence, réorganisations [F] / [P]

- Idempotence par `event_id` [F]. Avec le partitionnement, l'unicité devient `(event_id, ts)`. Un même transfert a le même
  `ts`, donc l'unicité est conservée.
- **Défaut connu** [F] : les TRC-20 issus de l'historique TronGrid ont un autre `event_id` que le direct (voir P0-B
  § 1.5). Le journal ne le corrige pas ; il faut une identité de transfert (P0-D § 3) ou une colonne de rapprochement.
- Réorganisations : suppression par `event_id` [F]. En table partitionnée, ajouter `ts` à la requête pour l'élagage des
  partitions. Le retrait est déjà ordonné après l'insertion. **Proposition** : ne pas supprimer, mais marquer
  (`retracted_at`) pendant la rétention, pour que les traçages gelés (P1) puissent expliquer une preuve disparue.

### 5.6 Reprise après incident [F] / [P]

- Aujourd'hui [F] :
  - les lots en échec et la file pleine sont perdus (comptés) ;
  - un arrêt brutal perd la file (jusqu'à 200 000 enregistrements) ;
  - aucune marque de couverture n'existe : on ne sait pas quelle plage de blocs est complète dans le journal.
- Proposé [P] :
  1. une table `journal_watermark(network, last_complete_block, updated_at)`, avancée seulement quand tous les flux
     jusqu'à ce bloc sont écrits ; c'est le segment JOURNAL du modèle de couverture (P0-B) ;
  2. un nouvel essai borné des lots en échec avant de compter une perte ;
  3. au redémarrage, plus de rechargement complet : la mémoire de travail reste vide (ADR-0024) et le traçage **lit** le
     journal à la demande ;
  4. au-delà du filigrane, combler depuis l'historique d'adresse à la demande, ou marquer `NOT_YET_INDEXED`.

## 6. Recommandation [P]

1. **Ne pas activer** le journal tel qu'il est : le rechargement complet est inutilisable, la purge par `DELETE` est
   coûteuse, il n'y a pas d'index par adresse, et la VM manque de disque.
2. Ordre proposé :
   1. décision ADR-0026 (journal de la chaîne entière ou journal limité au périmètre) ;
   2. mesure de 12 h terminée ;
   3. migration du schéma (partitions, index d'adresse, bloc et index de log, filigrane) ;
   4. **mesure dédiée** sur 48 h (scénario minimal) : débit, file, pertes, disque, impact sur les indexeurs et le GC ;
   5. passage éventuel au scénario recommandé, après agrandissement du disque.
3. Si l'ADR-0026 est accepté tel que proposé, le journal de la chaîne entière n'est plus la cible. Le traçage lit alors
   dans l'ordre : flux persistés du périmètre, puis historique d'adresse à la demande (ADR-0020), puis mémoire de travail.
   Le scénario « alternative ADR-0026 » suffit : il est beaucoup moins coûteux et plus profond (13 mois) sur ce qui
   compte pour le client.

## 7. Décisions à prendre

| Décision | Options |
|---|---|
| Journal de la chaîne entière ou limité au périmètre (ADR-0026) | recommandé : limité au périmètre, avec la chaîne entière en mémoire de travail seulement |
| Rétention de la chaîne entière si elle est retenue | 48 h (minimal) ou 7 jours (recommandé) |
| Disque | agrandir le disque de la VM Docker ou utiliser un volume dédié ; seuil d'alerte d'occupation |
| Réglages PostgreSQL | `shared_buffers`, `max_wal_size`, autovacuum, dans la limite de la VM partagée |
| Retraits | supprimer, ou marquer `retracted_at` |
