# Mesure mémoire de 12 heures — jeu de travail (étapes 1 à 3)

Statut : **seuils fixés avant lancement (2026-11-12, 19 h 10 WAT) ; mesure non commencée.** Les sections 4 à 15
seront remplies après la fenêtre. Faits mesurés et estimations sont distingués explicitement.

## 1. Version mesurée

| Élément | Valeur |
|---|---|
| Commits | `e2ba383` (plafond des signaux), `f3ce55d` (métriques des exemptions), `94ea42c` (option A, diagnostic) sur `53566bd` |
| Jar | construit depuis `94ea42c` (worktree propre), sha256 `d1b5927c186b837e1aef211a1a1e379e64a7eb16a4e96b066cb185acf3455263` |
| Image | `openchain-intelligence/app:measure-94ea42c` = image de référence 3b (`a8c1571`, conservée sous `app:ref-299db4f`) + nouveau jar |
| JVM | identique à la référence : JDK 25.0.5, `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+PrintClassHistogram`, conteneur 4 Go (heap max 3 Go) |
| Plafonds | inchangés : 400 000 flux, 300 000 profils, 150 000 signaux, 64 signaux par wallet ; rétentions 24 h / 7 j / 48 h ; purge toutes les 10 min |
| Tests | `mvn verify` sur `94ea42c` : 563 tests, 0 échec, 0 ignoré |

## 2. Conditions de mesure (prérequis)

Avant T0 : Mac sur secteur, veille désactivée (`pmset disablesleep 1`), capot ouvert ; application redémarrée (JVM
propre) ; retard Ethereum ≤ 50 blocs et TRON ≤ 200 blocs (environ 10 min) ; environ 1 h de montée en charge jusqu'aux
plafonds avant T0. Diagnostic des clés orphelines **non exécuté**. Aucune modification de code, de plafonds, de purge,
d'exemptions ou de paramètres JVM pendant la fenêtre.

## 3. Seuils fixés avant lancement

Référence : run 3b (image `299db4f`, 2026-11-10 20 h 22 → 2026-11-11), histogramme du 11 novembre 11 h 40 : heap vivant
2,02 Go, `Token` 549 800, `Address` 1 504 674, `String` 9,35 M (224 Mo) + `byte[]` 776 Mo, `ValueFlow` 549 789, `Signal`
344 886 ; échantillons : Old Gen après GC 1,8 à 2,0 Go au palier, environ 460 000 flux, 300 000 profils, 250 000 à 277 000
signaux ; purge 1,0 à 3,9 s.

**Validité de la fenêtre** (une seule condition manquée rend la mesure « non valide comme mesure de 12 h ») :
- V1 aucune veille du Mac (journal `pmset`), aucun redémarrage de l'application, de Docker ni de la VM ;
- V2 au moins 90 % des échantillons de 5 min collectés ;
- V3 retard Ethereum ≤ 300 blocs et TRON ≤ 1 200 blocs (environ 1 h) sur au moins 95 % des échantillons ; tout épisode
  au-delà est documenté ;
- V4 jeu de travail comparable : flux en mémoire au moins 80 % de la référence (≥ 368 000) en fin de fenêtre.

**Option A (instances partagées), sur l'histogramme de fin :**
- A1 `Token` ≤ 10 000 instances (référence 549 800) ;
- A2 `Address` par `ValueFlow` ≤ 2,0 (référence 2,74) ;
- A3 les instances `Token` et `Address` évitées sont chiffrées en octets à partir de l'histogramme (pas d'extrapolation
  présentée comme mesure).

**Heap vivant (histogramme de fin, après GC complet), à jeu de travail comparable :**
- amélioration confirmée : ≤ 1,92 Go (référence − 100 Mo), avec attribution par classe distinguant le plafond des signaux
  (`Signal`, `Signal$Evidence`, `RiskScore$Contribution`) de l'option A (`Token`, `Address`, chaînes) ;
- stabilité (sans gain démontré) : entre 1,92 et 2,12 Go ;
- dégradation : > 2,12 Go, ou OOM, ou redémarrage.

**Stabilité mémoire pendant la fenêtre** (l'absence de Full GC ne suffit pas) :
- S1 pente de l'Old Gen après GC (`jvm_gc_live_data_size_bytes`) sur les 8 dernières heures ≤ 25 Mo/h ;
- S2 maximum de l'Old Gen après GC ≤ 2,6 Go (87 % du heap de 3 Go) ;
- S3 aucun Full GC hors ceux provoqués par les deux histogrammes (comptés à part) ; aucun OOM.

**GC :** pause maximale ≤ 500 ms ; temps de pause cumulé ≤ 2 % du temps écoulé.

**Coût opérationnel :**
- purge : médiane ≤ 4 s, maximum ≤ 10 s (référence 1,0 à 3,9 s) ;
- mesure des exemptions (durée journalisée à chaque purge) : ≤ 500 ms par purge ;
- débit : événements indexés par heure ; retard par réseau (V3).

**Limites connues de la méthode :** le temps CPU du tri du store n'est pas instrumenté séparément (pas de code ajouté) ; il
est borné par la durée totale de la purge et le temps CPU du processus. Les deux histogrammes provoquent chacun un GC
complet. La référence cohabitait avec gate-trader-pro (environ 1,4 Go) ; cette mesure cohabite avec la pile Dify,
mysql-local et rabbitmq (environ 2,2 Go) dans la même VM de 7,7 Go.

**Verdict :** amélioration confirmée / stabilité mémoire / dégradation / non concluant (si V1 à V4 non remplies, ou si
le jeu de travail n'est pas comparable).

## 4 à 15

À remplir après la fenêtre.

## Incidents avant la mesure (2026-11-12)

- Application précédente (image `299db4f`) arrêtée proprement le 2026-11-11 à 15 h 52 UTC (SIGTERM, pas d'OOM) ; origine
  non établie. Docker Desktop était arrêté le 2026-11-12 à 18 h 02.
- Au redémarrage, la règle existante d'écart maximal (7 200 blocs) a sauté les blocs manquants : TRON 87 899 299 à
  87 943 187 et Ethereum 26 386 430 à 26 400 192 **non indexés** (trou de couverture des enquêtes, pas de rattrapage).
- 18 h 15 → 18 h 51 WAT : Mac en veille (capot fermé, sur batterie) ; la VM Docker n'a tourné qu'environ 7 min sur
  42. Ces 36 min ne font pas partie de la mesure ; l'application sera redémarrée avant T0.
- 19 h 43 WAT : conditions réunies (secteur ; veille système `sleep 0` réglée par l'utilisateur, au lieu de
  `disablesleep 1` prévu : l'orchestrateur accepte l'un ou l'autre). Risque résiduel : fermer le capot met le Mac en
  veille malgré `sleep 0` ; toute veille est relevée dans le journal `pmset` et invalide la fenêtre (V1). Application
  redémarrée à 18 h 43 UTC (image `measure-94ea42c`) ; montée en charge d'une heure, puis T0 dès que le retard est dans
  les seuils.
- **T0 réel : 2026-11-12 20 h 43 min 39 s WAT (19 h 43 min 39 s UTC)**, distinct du redémarrage de l'application
  (19 h 43 min 09 s WAT) ; retard à T0 : Ethereum 12 blocs, TRON 20 blocs. Histogramme de départ pris à 20 h 43 min 43 s
  (provoque un GC complet, compté à part). Fin prévue : T0 + 12 h ≈ 2026-11-13 08 h 44 WAT.

## Incident : fenêtre interrompue à T0 + 8 min 32 s — mesure de 12 h NON VALIDE

- **Fait** : à **20 h 52 min 11 s WAT**, l'application Docker Desktop (interface) s'est déconnectée du backend, puis
  Docker s'est arrêté proprement (journal `com.docker.backend.log` : « shutting down electron processes », arrêt des
  services ; console de la VM : `POST /shutdown … shutdown complete` à 20 h 52 min 18 s). Le conteneur de l'application,
  PostgreSQL et tous les autres conteneurs se sont arrêtés avec la VM.
- **Ce qui est exclu** : veille du Mac (aucune entrée `Sleep` dans `pmset` ; seul l'écran s'est éteint à 20 h 51 min 19 s) ;
  passage sur batterie (secteur constant) ; OOM dans la VM (aucune trace dans la console de la VM) ; action de Claude (aucune
  commande Docker lancée entre T0 et l'arrêt, hormis les lectures de l'échantillonneur).
- **Cause non établie** : fermeture de Docker Desktop par l'utilisateur, plantage de l'interface ou arrêt par le système ;
  les journaux macOS accessibles ne le disent pas.
- **Données** : 2 échantillons valides dans la fenêtre (20 h 43 min 45 s et 20 h 48 min 46 s), puis des échantillons vides.
  L'histogramme de départ est valide et conservé ; il n'y a pas d'histogramme de fin.
- **Conséquence** : critère V1 non rempli. **Aucun résultat de 12 h n'est présenté.** Orchestrateur arrêté à 21 h 20 WAT
  pour qu'il n'échantillonne pas une application relancée comme si c'était la même fenêtre. Données de cette tentative
  archivées dans le scratchpad (`m12h-attempt2-invalid`).
- **Seul résultat exploitable** (instantané, pas une mesure de stabilité) : l'histogramme de départ montre l'option A
  active en production : `Token` 1 977 instances pour 454 407 flux (référence : 549 800 pour 549 789 flux) et `Address`
  0,76 par flux (référence : 2,74). Le heap vivant à T0 (1,17 Go) **n'est pas comparable** à la référence (2,02 Go) :
  jeu de travail différent (64 846 signaux contre 344 886) et ancienneté différente (1 h contre environ 15 h).

## Troisième tentative (relance autorisée par l'utilisateur le 2026-11-12 à 21 h 21 WAT)

- Docker Desktop redémarré à 21 h 21 ; application recréée à **21 h 21 min 55 s WAT** (20 h 21 min 55 s UTC), même image
  `measure-94ea42c`, mêmes options JVM, mêmes seuils (section 3) ; secteur, `sleep 0`.
- Montée en charge d'une heure, puis T0 lorsque le retard est dans les seuils ; T0 attendu vers 22 h 22 WAT, fin vers
  10 h 22 WAT le 2026-11-13.
- Surveillance ajoutée : arrêt de Docker Desktop, redémarrage du conteneur, veille, batterie (vérifiés chaque minute).
- **21 h 23 min 44 s : passage sur batterie pendant la montée en charge** (veille système revenue à `1`). Aucune fenêtre
  n'avait commencé. Orchestrateur arrêté à 21 h 24 et relancé (quatrième tentative) : il attend de nouveau secteur +
  veille désactivée, puis recrée l'application (JVM propre) et refait une heure de montée en charge avant T0.

## Quatrième tentative abandonnée avant T0 et défaut de la métrique de retard

- 21 h 35 et 21 h 40 WAT : délai d'attente puis **HTTP 429** de TronGrid. Le disjoncteur s'ouvre et l'indexation TRON est
  en pause. Un rapatriement d'historique d'adresse TRON tournait au même moment, sur le même quota. Le retard TRON monte
  à environ 940 blocs et ne se résorbe pas (935 à 946 entre 21 h 55 et 22 h 00).
- **Défaut constaté [F]** : quand TronGrid échoue, `TronGridProvider.java:663` renvoie une hauteur de chaîne égale à 0,
  et la jauge `oci_indexer_lag_blocks` calcule `max(0, 0 − dernier bloc)` = **0** (`MetricsConfiguration.java:84`).
  Les retards à 0 relevés à 21 h 36-21 h 46 et à 21 h 58-22 h 00 sont donc **faux** : l'indexation était en panne. Le
  contrôle de T0 des tentatives précédentes (retard ≤ 200 seulement) aurait pu être trompé. La correction du produit
  (NaN au lieu de 0) attend une autorisation.
- 22 h 06 : quatrième tentative arrêtée avant T0 et remplacée par la **cinquième**, avec un contrôle robuste autorisé
  par l'utilisateur :
  - T0 exige un retard sous les seuils **et** un dernier bloc indexé qui avance en 60 s sur chaque réseau **et** aucune
    erreur fournisseur (disjoncteur, 429, délai d'attente) dans les 5 dernières minutes ; chaque contrôle est journalisé ;
  - chaque échantillon enregistre les derniers blocs et les erreurs fournisseur ;
  - l'analyse compte un retard de 0 sans avancée du bloc comme **inconnu**, jamais comme valide (critère V3).
- Application recréée à **22 h 06 min 50 s WAT** ; T0 au plus tôt vers 23 h 07, au plus tard vers 1 h 07 (sinon
  abandon : « robust T0 gate not met »).
- **T0 réel (cinquième tentative) : 2026-11-12 23 h 08 min 20 s WAT (22 h 08 min 20 s UTC)**, distinct du redémarrage
  (22 h 06 min 50 s WAT). Contrôle au premier essai :
  - retard : Ethereum 12 blocs, TRON 19 blocs ;
  - blocs indexés en 60 s : Ethereum 26 401 735 → 26 401 741, TRON 87 949 108 → 87 949 129 ;
  - aucune erreur fournisseur dans les 5 minutes précédentes.

  **Cinquième tentative NON VALIDE (constat du 2026-11-13 à 9 h 49, corrigé à 10 h 40) :**
  - **le conteneur s'est arrêté le 2026-11-12 à 23 h 22 min 32 s WAT (T0 + 14 min)**, arrêt propre (code 143, pas
    d'OOM). L'interface de Docker Desktop s'est fermée à 23 h 22 min 28 s (`before-quit`, `app.exit(104)`, journal
    `electron-2026-11-12.log`) et a demandé l'arrêt du moteur (`com.docker.backend.log` : « electron requested backend
    shutdown »). Aucune action dans l'interface ne précède, comme pour l'arrêt de 20 h 52 (deuxième tentative) ;
  - **cause probable (inférence)** : les deux fois, Docker Desktop avait été lancé par Claude (`open -a Docker`) depuis
    sa session, et les deux fermetures coïncident avec la fin d'une session Claude Code. Remède : lancer Docker Desktop
    soi-même (Dock ou Spotlight), jamais depuis la session ;
  - ensuite, sans effet sur la mesure déjà arrêtée : passage sur batterie à 8 h 10, veille par fermeture du capot à
    8 h 40, **redémarrage du Mac vers 9 h 17** ;
  - critère V1 non rempli ;
  - **données perdues** : le redémarrage a vidé `/private/tmp`, donc l'orchestrateur, les échantillons de 5 min,
    l'histogramme de départ en fichier et le script d'analyse. Les chiffres de l'histogramme de départ ci-dessous ont
    été relevés pendant la session et restent exacts. Les journaux du conteneur (lignes de purge toutes les 10 min,
    histogramme de départ) devraient subsister dans Docker, **non vérifié** (Docker arrêté au constat) ;
  - **aucun résultat de 12 h n'est présenté.**

  Histogramme de départ pris juste après (GC complet compté à part) : heap vivant 1 196,6 Mo, `ValueFlow` 471 024,
  `Token` 1 745, `Address` 506 626 (1,08 par flux), `WalletProfileAccumulator` 306 568, `Signal` 71 211. Fin prévue :
  T0 + 12 h ≈ 2026-11-13 11 h 08 WAT.
