# Phase 1, étape 1 — redémarrage sans perte : instantané du jeu de travail borné

Statut : **conception validée le 2026-11-12, amendement de l'ADR-0024 (étape 3d) validé et reporté dans l'ADR.
Implémentation partielle le 2026-11-16 (branche `wip/adr-0024-step-3a`, non fusionnée, désactivée par défaut)** : voir
§ 10. L'implémentation devait commencer après la mesure mémoire de 12 h ; celle-ci reste sans résultat valide faute
d'hôte fiable, et le travail fait ne touche ni la production ni sa configuration (option désactivée par défaut). **[F]** fait vérifié (référence) ; **[I]** inférence
à mesurer ; **[P]** proposition.

## 1. Décisions prises (2026-11-12)

| Décision | Choix |
|---|---|
| Modèle de persistance | **instantané du jeu de travail déjà borné** par les plafonds de l'ADR-0024 étape 3b, et non journal de toute la chaîne |
| Périmètre de « sans perte » | flux, profils, signaux, scores, soldes mémorisés ; **non persistés et documentés** : fenêtres du moteur de règles, historique d'événements des simulations (dry-run) |

## 2. Point de départ [F]

- **Étape 1 de l'ADR-0024** (faite) : points de reprise des indexeurs dans `indexer_checkpoints` (V8), reprise au bloc
  suivant, testée. Au-delà de `max-resume-gap` (7 200 blocs), l'indexeur redémarre près de la tête.
- **Étape 2** (faite, désactivée par défaut) : journal en écriture différée de **tous** les flux, signaux et scores (V9),
  rechargé au démarrage dans la rétention (7 jours). Mesuré : 4,8 M flux par jour, **rechargement 316 s pour 50 000 flux**.
- **Étapes 3a/3b** (branche `wip`, verdict de la mesure de 12 h en attente) : purge par fenêtres de temps, puis plafonds
  stricts. 400 000 flux, 300 000 profils, 150 000 signaux, 64 signaux par wallet ; accumulateurs de profils bornés.
- **État en mémoire à persister** :
  - flux : `InMemoryFlowStore` ;
  - profils : `WalletProfileAccumulator`, qui contient les clés récentes (4 096), les comptages de hash de transaction,
    les totaux par jeton, l'activité (2 048 instants et repli), les contrats (1 024) et les contreparties
    (`Map<Address,long[]>`, coupée à 1 024) ;
  - signaux : `InMemorySignalRepository` ;
  - scores : `InMemoryRiskScoreRepository`, historique borné par adresse ;
  - soldes : `BalanceBook`, `LinkedHashMap` de `eventId` → solde avant envoi, bornée à `MAX_ENTRIES`, plus l'ensemble
    des réseaux « autoritaires ».
- **Arrêt** : `Bootstrap.destroy()` ne fait que `scheduler.shutdownNow()` (`Bootstrap.java:505`). Il n'existe aucun point
  d'arrêt ordonné qui fige l'état et le point de reprise ensemble.

## 3. Pourquoi le rechargement actuel est lent [F] puis [I]

- [F] `restoreChunk` est `synchronized` sur `ChainIngestionService`, **le même verrou** que le traitement en direct
  (`process`, `ChainIngestionService.java:295`, `:507`).
- [F] après chaque lot de 200 flux, le rechargement se met en pause pendant **deux fois** la durée du lot
  (`LockSupport.parkNanos(2 × durée)`) pour laisser la priorité au direct.
- [F] tous les flux sont d'abord accumulés dans une seule liste (`Bootstrap.java:396-399`), puis rechargés.
- [I] Le rechargement plafonne donc à un tiers du temps de traitement, et il est en concurrence avec l'ingestion en
  direct, qui démarre en même temps. Les 316 s mesurées incluent cette concurrence. **Hypothèse à mesurer** : rechargé
  avant le démarrage des indexeurs, sans pause, avec des insertions groupées, le coût par flux devrait baisser d'un ou
  deux ordres de grandeur.

## 4. Conception [P]

### 4.1 Principe

Au lieu de rejouer le trafic, on enregistre **l'état borné lui-même** et on le recharge **avant** le démarrage des
indexeurs. La taille du rechargement dépend des plafonds, pas du trafic.

### 4.2 Contenu d'un instantané

| Élément | Contenu | Taille [I] (à mesurer) |
|---|---|---|
| En-tête | id de génération, horodatage, version de l'application, **filigrane par réseau** (dernier bloc entièrement intégré à l'état), état terminé ou non | négligeable |
| Flux | ≤ 400 000 (plafond) + flux des adresses exemptées | ~300 o par ligne → ~120 à 200 Mo |
| Profils | ≤ 300 000 accumulateurs sérialisés en binaire compact (format versionné) | à mesurer (accumulateur en mémoire ~1 240 o de surcoût fixe, analyse de compaction) |
| Signaux | ≤ 150 000 (+ exemptés) | ~1 Ko → ~150 Mo |
| Scores | dernier score + historique borné par adresse | à mesurer |
| Soldes | entrées de `BalanceBook` (≤ `MAX_ENTRIES`) + réseaux autoritaires | faible |

### 4.3 Écriture

- **À l'arrêt ordonné** :
  1. arrêter les indexeurs (plus aucun événement) ;
  2. attendre la fin du traitement en cours ;
  3. écrire l'instantané ;
  4. enregistrer les points de reprise **dans la même transaction** que l'en-tête de l'instantané ;
  5. marquer l'instantané terminé.

  L'arrêt ordonné passe par un `SmartLifecycle` ou un `@PreDestroy` en remplacement du seul `shutdownNow()`. Le délai
  d'arrêt est à aligner sur `stop_grace_period` dans Compose (aujourd'hui par défaut).
- **Périodiquement** (par exemple toutes les 30 min, configurable, désactivable) : génération N+1 écrite en arrière-plan
  à partir d'une **copie cohérente** prise sous le verrou de traitement, puis bascule atomique de la génération courante ;
  la génération N-1 est supprimée après la bascule.
- Écriture par `COPY` (pilote PostgreSQL `CopyManager`) dans des tables dédiées, par génération, pour ne pas multiplier
  les `INSERT`.
- **Le journal de l'étape 2 n'est plus nécessaire** pour le redémarrage. Il reste désactivé ; sa suppression ou sa
  réutilisation est une décision séparée.

### 4.4 Rechargement

1. Au démarrage, **avant** l'enregistrement des indexeurs, charger la dernière génération terminée.
2. Recharger sans pause et sans verrou partagé (les indexeurs ne tournent pas encore) : flux et signaux par lots,
   profils désérialisés directement, sans réingestion des flux ; les instances partagées (option A) sont reconstruites
   par `canonical()`.
3. **Cohérence avec les points de reprise** :
   - arrêt ordonné : point de reprise = filigrane de l'instantané → **reprise au bloc suivant, sans ré-indexation** ;
   - arrêt brutal (crash, kill) : le point de reprise peut être plus récent que le dernier instantané périodique. On
     revient au filigrane de l'instantané et on **ré-indexe l'intervalle**, au plus une période d'instantané. C'est la
     seule manière de ne rien perdre ; c'est dit dans les journaux et dans la documentation. Les événements rejoués sont
     dédoublonnés par `eventId`.
   - si l'intervalle dépasse `max-resume-gap`, le comportement existant s'applique (saut journalisé) ; l'état reste
     cohérent avec le filigrane.
4. Version de format incompatible ou instantané corrompu : démarrage à vide, **jamais** un état partiel ; message
   explicite et métrique.

### 4.5 Ce qui n'est pas persisté (documenté)

Fenêtres du moteur de règles (une règle à fenêtre glissante repart à vide), historique d'événements des simulations,
`retractedIds` (ids retirés récemment), file de réévaluation après réorganisation. La fenêtre de réorganisation des
indexeurs reste celle de l'étape 1 (références de blocs seulement).

### 4.6 Métriques et journaux

`oci_snapshot_write_seconds`, `oci_snapshot_bytes`, `oci_snapshot_age_seconds`, `oci_snapshot_restore_seconds`,
`oci_snapshot_restored_items{kind}`, `oci_snapshot_failures_total{phase}` ; une ligne de journal par écriture et par
rechargement avec les compteurs.

## 5. Test de redémarrage en CI [P]

`SnapshotRestartTest` (`oci-app`, Testcontainers PostgreSQL, `@RequiresDocker`), qui doit tourner en CI avec
**« Skipped: 0 »** :

1. Démarrer l'application sur une chaîne simulée déterministe (graine fixe).
2. Injecter un flux scénarisé : transferts, natifs, doublons, réorganisation et réinclusion, signaux, scores, soldes.
3. Relever l'état complet : flux, profils, signaux, scores, soldes, points de reprise. La comparaison réutilise
   `CanonicalInstancesTest.run()`.
4. Arrêt ordonné, puis nouveau contexte sur la même base.
5. Vérifier :
   - état identique ;
   - point de reprise = dernier bloc + 1 ;
   - **aucun événement ré-indexé** (compteur `oci_events_indexed_total` à 0 avant les nouveaux blocs) ;
   - les nouveaux blocs donnent le même résultat qu'une exécution sans redémarrage.
6. Variante crash : arrêt brutal après un instantané périodique, puis rechargement et ré-indexation de l'intervalle ;
   état final identique à l'exécution sans crash.
7. Variante instantané corrompu ou de version inconnue : démarrage à vide, message, métrique.

Ajouter un contrôle CI qui échoue si un test est sauté (proposition déjà rédigée : issue « CI: fail when integration
tests are skipped »).

## 6. Mesure à volume réel [P]

Après l'implémentation, avec ton accord pour la production :
- durée d'écriture et de rechargement ;
- taille de l'instantané ;
- mémoire pendant l'écriture (la copie cohérente double temporairement certaines structures : **risque à mesurer**) ;
- impact sur le retard des indexeurs.

Rapport séparé, sur le modèle de la mesure de 12 h.

## 7. Amendement proposé de l'ADR-0024 (texte, non appliqué)

> **Step 3d (proposed, replaces step 2 for restarts):** persist a snapshot of the bounded working set (flows, wallet
> profiles, signals, scores, remembered balances) at orderly shutdown and periodically, tied to the indexer checkpoints in
> the same transaction; reload it before indexers start. Reload cost is bounded by the step 3b caps, not by traffic. After
> a crash, indexing rewinds to the snapshot watermark (at most one snapshot period re-indexed). Rule-engine windows and the
> dry-run event history are not persisted. The step 2 journal stays off by default.

## 8. Risques

| Risque | Traitement |
|---|---|
| Mémoire supplémentaire pendant la copie cohérente | copie incrémentale par type, mesurée ; ou écriture sous verrou à l'arrêt seulement, si la copie périodique coûte trop |
| Arrêt plus long (écriture finale) | délai d'arrêt configurable, aligné sur Compose ; mesuré |
| Format binaire des profils à maintenir dans le temps | version de format, test de compatibilité, démarrage à vide sur version inconnue |
| Incohérence instantané / point de reprise | même transaction, test de crash |
| Volume WAL de l'écriture périodique | `COPY` par génération + `DROP` de l'ancienne génération ; période configurable |
| Divergence avec l'ADR-0026 | compatible : l'ADR-0026 persiste en profondeur le périmètre client ; l'instantané couvre le jeu de travail de la chaîne |

## 9. Tâches et estimation

| # | Tâche | Estimation |
|---|---|---|
| 1 | Validation de ce document et de l'amendement de l'ADR-0024 | toi |
| 2 | Verdict de la mesure de 12 h (étapes 3a/3b) et fusion `wip` → `main` | dépend de la mesure |
| 3 | Sérialisation versionnée des accumulateurs, flux, signaux, scores, soldes + tests unitaires (aller-retour exact) | 3 à 4 jours |
| 4 | Tables (migration V11, additive), écriture `COPY` par génération, bascule atomique, en-tête et filigranes | 2 à 3 jours |
| 5 | Arrêt ordonné et rechargement avant les indexeurs ; cohérence des points de reprise ; cas du crash | 3 à 4 jours |
| 6 | `SnapshotRestartTest` (3 variantes) en CI, contrôle « 0 sauté » | 2 à 3 jours |
| 7 | Métriques, documentation (installation, limites du README), CHANGELOG | 1 jour |
| 8 | Mesure à volume réel (avec ton accord) | 1 jour + exécution |

Total : environ **2,5 à 3,5 semaines** après la validation.

## 10. État de l'implémentation (2026-11-16) [F]

Fait, testé :

| Élément | Où | Tests |
|---|---|---|
| Sérialisation exacte et versionnée : accumulateur de profil (clés récentes dans leur ordre, activité exacte et repliée, contreparties, contrats, marque « début inconnu »), flux, signaux, scores, soldes | `WalletProfileAccumulator.writeTo/readFrom`, `snapshot/RecordCodecs`, `snapshot/WorkingSetSnapshot` | `WalletProfileSnapshotTest` (4), `WorkingSetSnapshotTest` (4) : aller-retour exact et octets identiques, **même comportement après rechargement** pour les mêmes nouveaux événements, instances partagées, refus d'un format inconnu ou de données tronquées |
| Stockage PostgreSQL par génération, en morceaux de 1 Mo, visible seulement une fois terminé, avec copie des points de reprise | `V11__working_set_snapshot`, `JdbcWorkingSetSnapshotStore` | `WorkingSetSnapshotServiceTest` (5, PostgreSQL 17) |
| Arrêt ordonné : plus de nouvelle relève, relèves en cours attendues (`OCI_PERSISTENCE_SNAPSHOT_SHUTDOWN_TIMEOUT`), écriture sous le verrou d'ingestion ; rechargement avant les indexeurs | `Bootstrap.destroy/restoreWorkingSet`, `WorkingSetSnapshotService` | idem |
| Redémarrage de bout en bout : l'application réelle indexe les blocs 1 à 40 d'un nœud Ethereum simulé, s'arrête, une deuxième application sur la même base reprend au bloc 41 **sans relire un bloc déjà indexé** ; après le bloc 80, flux, profils, signaux et scores sont identiques à ceux d'une troisième application qui a tout indexé sans arrêt. Le trafic traverse le redémarrage (fonds reçus avant, transférés après) | — | `WorkingSetRestartTest` (PostgreSQL 17) ; vérifié qu'il échoue quand l'instantané est supprimé avant le redémarrage. Seules les valeurs d'horloge murale (instant de calcul des scores, âge affiché) sont exclues de la comparaison |
| Métriques (§ 4.6) : `oci_snapshot_restore_seconds`, `oci_snapshot_write_seconds`, `oci_snapshot_bytes`, `oci_snapshot_age_seconds`, `oci_snapshot_restore_outcome{outcome}`, `oci_snapshot_restored_items{kind}`, `oci_snapshot_failures_total{phase}` ; NaN tant qu'aucune mesure n'existe (les valeurs d'écriture ne sont visibles qu'avec des instantanés périodiques) | `MetricsConfiguration.workingSetSnapshotMetrics` | `WorkingSetRestartTest` |
| État d'observation (format 2) : filtre des adresses vues, début d'observation et premier bloc par réseau, rechargés ensemble ou pas du tout. Sans lui, chaque redémarrage relançait les 30 jours de mise en route de l'âge (âges retenus) ; le début d'observation sans le filtre ferait passer un vieux portefeuille dont le profil a été évincé pour neuf (âge 0 inventé). Taille du filtre changée depuis l'instantané : rien de l'observation n'est rechargé, l'âge reste retenu | `SeenAddresses.writeTo/readFrom`, `WorkingSetSnapshot.Observation`, `ChainIngestionService.observation/installObservation` | `WorkingSetSnapshotTest` (filtre identique bit à bit, bit corrompu refusé), `WorkingSetRestartTest` (même état d'observation que l'exécution sans arrêt ; échoue s'il n'est pas rechargé) |
| Contrôle CI « 0 test sauté » | `.github/workflows/ci.yml`, étape « No skipped test » | vérifié en local |

Écarts assumés par rapport au § 4 :

- **Rechargement seulement si les points de reprise n'ont pas bougé depuis l'instantané** (arrêt ordonné). Après un arrêt
  brutal, rien n'est rechargé et l'état se reconstruit comme avant, au lieu de revenir au filigrane et de réindexer : sans
  instantané périodique, le seul instantané disponible pourrait avoir des jours, et le retour en arrière réindexerait des
  milliers de blocs. Le retour au filigrane viendra avec les instantanés périodiques.
- **Morceaux binaires (`bytea`) plutôt que des tables par type remplies par `COPY`** : un seul format, versionné, déjà
  testé en aller-retour exact ; une génération se remplace en une transaction.
- **Désactivé par défaut** : l'écriture à l'arrêt demande un délai d'arrêt du conteneur supérieur aux 10 s par défaut de
  Docker, donc une modification de `docker-compose.yml` (configuration de production, à ton accord).

Reste à faire :

1. Instantanés périodiques avec copie cohérente et retour au filigrane après un crash (§ 4.3 et 4.4, variante crash du § 5).
2. Mesure à volume réel (§ 6), avec ton accord : durée d'écriture et de rechargement, taille, mémoire.
3. `stop_grace_period` dans `docker-compose.yml` et activation par défaut : décisions à prendre.
