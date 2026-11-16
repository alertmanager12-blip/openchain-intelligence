# Versionnement, tags et releases GitHub

Audit et préparation du 2026-11-12. **Aucun tag créé, rien poussé, aucune release publiée.** Ces opérations attendent la
validation explicite du mainteneur. **[F]** fait vérifié (commande et résultat) ; **[R]** recommandation.

Contrainte du moment : une mesure mémoire de 12 h tourne sur cette machine. Aucun build ni test Maven n'a donc été lancé
pour cet audit, et la qualité du commit candidat s'appuie sur la CI GitHub (§ 4).

## 1. État actuel du versionnement [F]

| Élément | État |
|---|---|
| Dépôt | `alertmanager12-blip/openchain-intelligence`, public, licence Apache-2.0, branche par défaut `main` |
| Tags locaux | **aucun** (`git tag -l` vide) |
| Tags distants | **aucun** (`git ls-remote --tags origin` vide) |
| Releases GitHub | **aucune** (`gh release list` vide) |
| `main` | `5f581fe` « feat(frontend): refonte UI/UX de la console … (#14) », 39 commits, identique à `origin/main` |
| Branche courante | `wip/adr-0024-step-3a` à `94ea42c` ; `origin/wip/adr-0024-step-3a` à `299db4f` |
| Commits non publiés | 5 sur `wip` : `1115044`, `53566bd`, `e2ba383`, `f3ce55d`, `94ea42c` |
| Commits sur `wip` absents de `main` | 7, dont `7b667ca` et `299db4f` déjà poussés sur la branche `wip` distante, sans être fusionnés |
| Modifications locales non commitées | `docs/adr/README.md` (ligne ADR-0026) et 18 fichiers non suivis : documents d'analyse et de conception, ADR-0026 proposé, banc exploratoire `oci-app/src/test/.../probe/` |
| Version Maven | `0.1.0-SNAPSHOT` (parent et 5 modules) |
| Version frontend | `0.0.0` (`frontend/package.json`) |
| Images Compose | `openchain-intelligence/app:0.1.0-SNAPSHOT`, `…/frontend:0.1.0-SNAPSHOT` (`docker-compose.yml:37, 109`) |
| `CHANGELOG.md` | présent sur `main` (Keep a Changelog 1.1.0, SemVer 2.0.0) ; tout est sous `[Unreleased]`, aucune version publiée |
| Engagement de stabilité | `CHANGELOG.md` : API REST v1 et schéma de règles `openchain.io/rule/v1` stables à partir de 1.0.0 ; en 0.x, une version mineure peut casser la compatibilité (toujours listé) |
| Branches Dependabot | 13 branches distantes ; aucune PR ouverte listée par `gh pr list` |
| Signature des tags | aucune clé configurée (`user.signingkey`, `gpg.format`, `tag.gpgSign` vides) |

## 2. Commit candidat [R]

**`main` à `5f581fe`, et pas la branche `wip`.**

| Critère | `main` (`5f581fe`) | `wip` (`94ea42c`) |
|---|---|---|
| Publié sur GitHub | oui | non (5 commits locaux) |
| CI GitHub | **verte sur les 6 tâches** (§ 4) | aucune exécution |
| Validation fonctionnelle | fusionné par PR (#13, #14) | travail mémoire non validé : mesure de 12 h en cours, troisième relance |
| Contenu expérimental | non | oui : plafonds et purge du jeu de travail (ADR-0024 3a/3b) dont le verdict mémoire n'est pas rendu |

Le travail de `wip` (corrections de détecteurs, réorganisations, plafond des signaux, instances partagées) fera l'objet
d'une version ultérieure (`v0.2.0`), après validation de la mesure, fusion dans `main` par PR et CI verte.

## 3. Version recommandée : `v0.1.0` [R]

- C'est la **première version publiée** : aucun tag n'existe, `pom.xml` annonce déjà `0.1.0-SNAPSHOT` et le CHANGELOG
  prévoit des versions 0.x.
- **Pas `v1.0.0`.** Les critères de stabilité ne sont pas remplis :
  - API v1 explicitement non stable avant 1.0.0 ;
  - ADR 0021 à 0026 encore « proposed » ;
  - isolation multi-client absente ;
  - mémoire de la chaîne entière en cours de validation.
- **Pas `v0.0.x`.** Le projet offre déjà des fonctionnalités utilisables, avec une CI complète.

### Problème à résoudre avant le tag : la version des artefacts

À `5f581fe`, les artefacts portent `0.1.0-SNAPSHOT` (Maven, images Compose) et `0.0.0` (frontend), et le CHANGELOG n'a
pas de section `[0.1.0]`. Un tag `v0.1.0` sur ce commit livrerait des artefacts étiquetés SNAPSHOT : la version n'est
alors pas reproductible.

| Option | Description | Avantages | Inconvénients |
|---|---|---|---|
| **A. Commit de préparation de release** [R] | branche `release/v0.1.0` depuis `main` : versions `0.1.0` (6 `pom.xml`, `package.json`, images Compose), `CHANGELOG.md` `[Unreleased]` → `[0.1.0] - AAAA-MM-JJ` avec liens de comparaison ; PR, CI verte, fusion ; tag sur le commit fusionné ; puis passage de `main` à `0.2.0-SNAPSHOT` | artefacts, tag et CHANGELOG cohérents ; pratique standard ; aucun historique réécrit | une PR et un commit de plus |
| B. Tag direct sur `5f581fe` | `git tag -a v0.1.0 5f581fe` | immédiat | artefacts « SNAPSHOT » sous une version publiée ; CHANGELOG sans section 0.1.0 |

## 4. Vérifications effectuées [F]

| Vérification | Résultat |
|---|---|
| CI GitHub sur `5f581fe` (2026-11-10 13 h 33 UTC) | **succès** : Backend (Maven, JDK 21), Frontend (Angular), Docker images, licences des dépendances (bloquant), CodeQL java-kotlin, CodeQL javascript-typescript |
| CI précédentes sur `main` | `bf16539` en échec (couleurs des labels), corrigé par `35fccaf` (succès) ; deux exécutions annulées par des exécutions plus récentes |
| Build et tests locaux | **non relancés** (mesure mémoire en cours). Dernière exécution locale : `mvn verify` sur `94ea42c` (branche `wip`, pas le candidat), 563 tests, 0 échec, le 2026-11-12 |
| Fichiers sensibles suivis sur `main` | aucun `.env`, `.pem`, `.key`, `.p12` ; `deploy/.env.example` ne contient que des valeurs vides ou `change…` ; `.env` est dans `.gitignore` |
| Motifs de secrets sur `main` (AWS, GitHub, Slack, OpenAI, clé privée, clé `oci_live_`, jeton Telegram) | une seule correspondance : `ConnectorsTest.java:126`, jeton Telegram **de test** utilisé avec un faux client HTTP (`FakeHttp`) ; aucun appel réel |
| Clé de chiffrement par défaut dans `docker-compose.yml` | clé **publique, réservée au développement**, documentée comme telle et refusée par l'application hors mode démo |
| Alertes CodeQL, Dependabot et secret scanning | **non lisibles** avec le jeton `gh` actuel (403 et 404 : il manque les droits sur les alertes) ; à vérifier dans l'onglet *Security* avant publication |
| Cohérence des modules | 5 modules au même `${project.version}` ; ADR : 14 « accepted » dans l'index de `main` |

## 5. Notes de version proposées (anglais, langue du dépôt)

```markdown
# ChainSentinel v0.1.0 — first public preview

First tagged release of ChainSentinel (OpenChain Intelligence), an open-source blockchain intelligence engine:
detect, understand, correlate, investigate and explain suspicious on-chain activity. **Preview quality (0.x):** the
REST API v1 and the rule schema may still change in minor versions; every breaking change is listed in CHANGELOG.md.

## Highlights
- Multi-chain ingestion (Ethereum and EVM networks via JSON-RPC, TRON via TronGrid) with reorg-aware indexing,
  RPC failover and checkpointed restart.
- Detection engine (fan-out, fast forwarding, structuring, circular flow, rapid drain, dormant reactivation,
  direct and indirect exposure to labelled addresses, unusual approvals) with plain-language explanations.
- Explainable risk scoring; declarative rule DSL (`openchain.io/rule/v1`) with a rule pack, noise report and
  reachability checks.
- Alerting with outbox delivery to webhooks, Slack, Discord and Telegram; signed webhooks.
- Investigation console (Angular): wallet analysis, risk, exposure, transactions, fund flow graph, watchlist,
  rule builder; dark theme by default; public landing page.
- On-demand address history (ADR-0020), watchlist, optional flow/signal journal (off by default).
- Security: API keys (HMAC-hashed), optional OIDC, scoped permissions, secrets encrypted at rest.
- Observability: Prometheus metrics, OpenTelemetry.

## Known limitations
- In-memory working set: chain-wide analysis looks back a few hours at mainnet volume; deep history only for
  addresses whose history was loaded.
- No internal (trace) transactions, no TRC-10; no historical balances on TRON.
- Single tenant: no isolation between clients within one instance.
- `/wallets/{a}/exposure` uses a simplified method (see docs; to be aligned with the exposure detector).
- Not production-hardened for multi-client hosting; see SECURITY.md.

## Upgrade
First release: no upgrade path needed. Database schema is created by Flyway migrations V1–V9 at start-up.

## Verification
CI on the tagged commit: backend (Maven, JDK 21), frontend (Angular), Docker images, licence policy, CodeQL (Java,
TypeScript): all green.
```

La liste des migrations (V1 à V9, vérifiée sur `main`) et le contenu exact seront relus sur le commit de release (option A) avant publication.

## 6. Commandes (à exécuter seulement après validation)

### Option A (recommandée)

```bash
# 1. préparation (sur une branche, jamais sur main directement)
git switch -c release/v0.1.0 origin/main
mvn -q versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false     # parent + 5 modules
#    frontend/package.json "version": "0.1.0" ; docker-compose.yml images :0.1.0
#    CHANGELOG.md : "## [Unreleased]" (vide) + "## [0.1.0] - AAAA-MM-JJ" + liens de comparaison
mvn -B verify                       # après la fin de la mesure mémoire
git commit -am "chore(release): v0.1.0"
git push -u origin release/v0.1.0   # autorisation requise
gh pr create --base main --title "chore(release): v0.1.0" --body-file <notes>   # autorisation requise
# 2. après CI verte et fusion
git switch main && git pull --ff-only
git tag -a v0.1.0 -m "ChainSentinel v0.1.0" <commit fusionné>
git show v0.1.0 --stat | head        # vérifier la cible
git push origin v0.1.0              # autorisation requise
gh release create v0.1.0 --title "ChainSentinel v0.1.0" --notes-file <notes> --prerelease --verify-tag   # autorisation requise
# 3. ouvrir le cycle suivant
#    main : versions 0.2.0-SNAPSHOT (PR)
```

### Option B

```bash
git tag -a v0.1.0 -m "ChainSentinel v0.1.0" 5f581fe
git push origin v0.1.0              # autorisation requise
gh release create v0.1.0 --notes-file <notes> --prerelease --verify-tag   # autorisation requise
```

`--prerelease` est recommandé tant que la version est en 0.x.

## 7. Règles de versionnement proposées pour la suite [R]

- Tags annotés `vMAJOR.MINOR.PATCH`, uniquement sur `main`, sur un commit à CI verte. Jamais déplacés ni supprimés ; une
  erreur se corrige par une nouvelle version de correctif.
- Un tag correspond à une seule release ; la release GitHub reprend la section du CHANGELOG.
- En 0.x : MINOR pour une fonctionnalité ou un changement incompatible (listé), PATCH pour une correction.
- Correctifs de sécurité : version PATCH, section `### Security` du CHANGELOG, avis de sécurité GitHub si nécessaire.
- Signature : recommandée (SSH ou GPG, `git tag -s`) dès qu'une clé est configurée ; aucune ne l'est aujourd'hui.
- Prochaine version prévue : `v0.2.0` pour le contenu de `wip` (détection, réorganisations, plafonds, instances partagées),
  après validation de la mesure mémoire et fusion par PR.

## 8. Décision du mainteneur (2026-11-12 21 h 50) et état de la préparation

**Retenu : option A, `v0.1.0`, publiée comme pre-release GitHub.** La procédure du dépôt
(`docs/guides/release-process.md`), lue après l'audit, s'applique :
- branche `release/0.1.0` (sans « v ») ;
- message du tag « ChainSentinel 0.1.0 » ;
- vérifications : `mvn -B clean verify`, tests du frontend et `bash deploy/smoke-test.sh` ;
- tag sur le commit de fusion.

Préparé localement (worktree `scratchpad/release-v0.1.0`, branche `release/0.1.0` depuis `origin/main` = `5f581fe`),
**non commité, non poussé** :

- `0.1.0` dans les 6 `pom.xml`, `frontend/package.json`, `frontend/package-lock.json` et les deux images de
  `docker-compose.yml`. Modifié à la main : Maven n'a pas été lancé, mesure mémoire en cours ; le résultat équivaut à
  `versions:set`.
- `CHANGELOG.md` : section `## [0.1.0] - RELEASE_DATE` créée sous un `[Unreleased]` vide, avec les liens de comparaison.
  La date sera fixée au moment du commit.
- `docs/guides/release-process.md` : n'indique plus « aucune release publiée ».
- Notes de version : `scratchpad/release-notes-v0.1.0.md` (§ 5).

Reste à faire, dans l'ordre :

1. après la mesure mémoire : `mvn -B clean verify`, tests du frontend, `deploy/smoke-test.sh` ;
2. commit `chore(release): 0.1.0` (sans co-auteur) ;
3. **avec autorisation** : pousser la branche et ouvrir la PR ;
4. après CI verte, **avec autorisation** : fusion ;
5. **avec autorisation** : tag `v0.1.0` sur le commit de fusion, poussé ;
6. **avec autorisation** : `gh release create v0.1.0 --prerelease --verify-tag` ;
7. PR d'ouverture du cycle suivant (`0.2.0-SNAPSHOT`).

## 9. Décisions attendues (version initiale de l'audit)

1. Commit candidat : `main` (`5f581fe`) plutôt que `wip`.
2. Version : `v0.1.0`.
3. Option A (commit de préparation par PR) ou option B (tag direct).
4. Release marquée « pre-release ».
5. Autorisations distinctes pour : pousser la branche de release, ouvrir la PR, pousser le tag, publier la release.
6. Signature des tags : configurer une clé maintenant ou plus tard.
