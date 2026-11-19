# Sortir la production du portable — plan de migration

- Date : 2026-11-19
- Statut : **proposé** (à valider par le mainteneur ; aucune ressource louée, aucun secret déplacé)
- Liens : `docs/guides/installation.md`, `docs/guides/security-hardening.md`, `deploy/backup/README.md`

## Pourquoi maintenant

Mesuré sur la production du portable entre le 18 et le 19 novembre :

| Fait | Effet sur l'analyse |
|---|---|
| Docker arrêté 3 fois en 14 h (redémarrage du Mac, deux arrêts après une veille) | production arrêtée, instantané d'arrêt non écrit, reprise sur un instantané ancien (Ethereum : ~900 blocs de progression perdus) |
| Veilles répétées (4 entre 14 h et 17 h le 19/11) ; l'horloge de la VM Docker s'arrête pendant la veille | instantanés périodiques retardés, retard d'indexation qui remonte |
| Trous enregistrés dans `indexer_gaps` : 17 340 blocs TRON (18/11), 12 300 blocs TRON (19/11) | ~15 h de transferts TRON jamais analysés |
| Serveur à 3,6 Gio sur 4 Gio, jusqu'à 3 cœurs | aucune marge pour le rattrapage ni pour la croissance |
| Retard Ethereum de plusieurs heures après chaque arrêt | alertes en retard d'autant |

Tant que la production tourne sur un portable, aucune surveillance n'est continue et aucune mesure n'est comparable.

## Dimensionnement

| Ressource | Aujourd'hui | Cible |
|---|---|---|
| Mémoire | conteneur limité à 4 Gio, plein | **16 Gio** (conteneur 8 Gio : `OCI_APP_MEMORY_LIMIT=8g`, PostgreSQL et système le reste) |
| Processeur | jusqu'à 3 cœurs ; traitement sous un verrou unique occupé à 90 % | **4 vCPU dédiés** (le verrou limite l'usage à ~1 cœur pour le traitement ; le reste pour indexation, PostgreSQL, instantanés) |
| Disque | base 926 Mo, sauvegardes ~300 Mo chacune | **80 à 160 Go SSD** (journal des adresses surveillées, instantanés, 14 sauvegardes locales) |
| Réseau | sortant vers TronGrid, le fournisseur Ethereum, l'OFAC | IP fixe ; entrant uniquement 443 (console) et 22 (SSH par clé) |

Fournisseurs possibles (offres et prix **à vérifier** au moment du choix) : Hetzner Cloud (CCX23 ou équivalent), OVHcloud,
Scaleway. Une région européenne convient ; les fournisseurs de chaîne sont des API publiques.

## Étapes

### 0. Décisions du mainteneur (avant tout)

1. Fournisseur et offre ; qui paie.
2. Nom de domaine pour la console (ex. `console.<domaine>`) et certificat (Let's Encrypt via le proxy).
3. Où vivent les secrets (`OCI_CRYPTO_MASTER_KEY`, `OCI_SECURITY_API_KEY_PEPPER`, mot de passe PostgreSQL, clés TronGrid et
   Ethereum, jeton Telegram) : gestionnaire de mots de passe ou de secrets. **Ils ne passent jamais par Claude.**
4. Où vont les sauvegardes hors serveur (stockage objet chiffré, autre fournisseur).

### 1. Préparer le serveur (≈ 1 h)

- Ubuntu LTS à jour, mises à jour de sécurité automatiques, utilisateur non root, SSH par clé uniquement.
- Pare-feu : entrant 22 et 443 seulement ; 8080 et 5432 jamais exposés (le compose les lie déjà à `127.0.0.1`).
- Docker Engine et le plugin compose ; horloge synchronisée (NTP).

### 2. Installer l'application (≈ 30 min)

- Code : `git clone` puis checkout du commit en production (ou d'un tag de release : le workflow `release.yml` publie les
  images sur GHCR, ce qui évite de construire sur le serveur).
- `.env` : copié par le mainteneur depuis le portable (`scp`) ; ajouter `OCI_APP_MEMORY_LIMIT=8g`.
- Proxy TLS devant la console (Caddy ou nginx) : HTTPS, HSTS ; `/actuator` réservé au réseau interne.
- **Ne pas démarrer** l'application avant la restauration (sinon Flyway migre une base vide).

### 3. Bascule des données (fenêtre d'arrêt ≈ 20 à 40 min)

1. Portable : arrêt **propre** de l'application (`docker compose stop app`) pour écrire l'instantané ; vérifier dans les
   journaux « working-set snapshot written ».
2. Portable : `deploy/backup/pg-backup.sh` ; noter la taille et le résultat de `pg_restore -l`.
3. Copie de la sauvegarde vers le serveur (`scp`), vérification de l'empreinte SHA-256 des deux côtés.
4. Serveur : démarrer PostgreSQL seul, `pg_restore` dans la base vide (procédure de `deploy/backup/README.md`).
5. Serveur : démarrer l'application. Attendus dans les journaux : schéma V21, « snapshot restored … indexing resumes at the
   next block », liste OFAC réappliquée (478 adresses), indexation qui reprend au point de reprise (pas de saut : l'arrêt
   dure bien moins que `max-resume-gap`).
6. Portable : **ne pas redémarrer** l'application (deux instances notifieraient deux fois et écriraient des états divergents).
   Garder le volume intact une semaine comme retour arrière.

### 4. Vérifications après bascule

- Console accessible en HTTPS ; connexion par clé API ou OIDC.
- `GET /api/v1/networks` : les deux réseaux sains ; retard qui diminue.
- `GET /api/v1/labels/sanctions-lists` : `CURRENT`.
- Une heure de mesure de débit (`target/measure/sample.sh`), comparée à celle du portable (Ethereum 1,11 s/bloc,
  TRON 0,95 s/bloc, 19/11).
- Une restauration d'essai de la première sauvegarde du serveur sur une base jetable.

### 5. Exploitation

- Sauvegarde quotidienne (cron) + copie hors serveur ; rétention 14 jours ; une restauration d'essai par mois.
- Supervision externe : disponibilité HTTPS de la console ; alerte si `oci_indexer_lag_blocks` dépasse un seuil
  (ex. 200 blocs TRON, 50 blocs Ethereum) plus de 15 min, si l'espace disque passe sous 20 %, si
  `oci_sanctions_unconfirmed_hours` dépasse 72.
- Profil `observability` (Prometheus, Grafana) déjà présent dans `deploy/` : à activer sur le serveur.

## Retour arrière

Si le serveur échoue pendant la première semaine : arrêter l'application sur le serveur, redémarrer celle du portable
(son volume est intact ; l'indexation reprendra à son point de reprise, avec un trou si l'écart dépasse `max-resume-gap`),
puis analyser.

## Hors de ce plan

- Haute disponibilité (deux instances) : impossible en l'état (un seul indexeur par chaîne, instantané local).
- Traitement des chaînes en parallèle (verrou unique) : décision d'architecture séparée.
- Récupération des 29 640 blocs TRON déjà sautés : rattrapage ponctuel séparé.
