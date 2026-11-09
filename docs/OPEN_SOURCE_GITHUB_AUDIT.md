# Audit open source et GitHub de ChainSentinel

*Date : 10 novembre 2026. Dépôt : https://github.com/alertmanager12-blip/openchain-intelligence. État initial : branche
`main` au commit `5abe1f2`, 31 commits. Les décisions du mainteneur sont reportées en fin de document ; les réglages
GitHub appliqués après validation sont listés au § 12.*

Légende : **observé** (vérifié sur le dépôt ou par l'API GitHub), **recommandé** (proposition à valider),
**décision** (à prendre par le mainteneur).

## 1. État initial du dépôt

- **Observé :** dépôt public, licence Apache-2.0 (`LICENSE` et `NOTICE` présents), Issues et Projects activés,
  Discussions et Wiki respectivement désactivées et activé, aucune protection de branche, aucun tag ni release, aucune
  description ni topic, indice « community profile » GitHub de 85 %.
- **Observé :** l'intégration continue fonctionne réellement. Le run du commit `2906a26` a réussi sur ses 6 jobs :
  backend Maven JDK 21 avec les tests Testcontainers, frontend, politique de licences, images Docker et test de fumée de
  la stack, CodeQL Java et JavaScript.
- **Observé :** Dependabot a ouvert 12 pull requests dans l'heure qui a suivi la publication (actions, images Docker,
  npm dont TypeScript 7 et Angular, Maven).
- **Observé :** secret scanning et push protection activés ; alertes Dependabot et mises à jour de sécurité Dependabot
  désactivées ; signalement privé de vulnérabilités activé.

## 2. Fichiers communautaires existants

| Fichier | Constat | Action |
|---|---|---|
| `README.md` / `README.fr.md` | Très complets sur l'installation, mais titrés « OpenChain Intelligence », sans vision, chaînes, limites ni feuille de route ; affirmaient l'absence de métriques (elles existent) et présentaient BSC/Polygon sans réserve | Améliorés |
| `CONTRIBUTING.md` | Bonnes règles de fond ; pas de convention de branches détaillée, de parcours nouveau contributeur, de processus de revue ni de politique IA ; exigeait 2 approbations alors qu'il n'y a qu'un mainteneur ; DCO obligatoire | Réécrit, DCO rendu facultatif (décision du mainteneur) |
| `CODE_OF_CONDUCT.md` | Contributor Covenant 2.1 officiel, mais adresse de signalement `conduct@openchain-intelligence.org` inexistante | Signalement au mainteneur via son profil GitHub |
| `SECURITY.md` | Délais et périmètre présents ; manquaient confidentialité, traitement des failles critiques, publication des correctifs ; mentionnait l'isolation multi-tenant, qui n'existe pas | Complété |
| `.github/ISSUE_TEMPLATE/*` | Bug, feature, contribution de règle ; labels `triage` et `rule` inexistants sur GitHub (donc ignorés) ; schéma de règle mal nommé (`oci.rules/v1`) | Améliorés, modèle documentation ajouté |
| `.github/PULL_REQUEST_TEMPLATE.md` | Checklist utile mais sans résumé structuré, compatibilité, sécurité, migration | Réécrit |
| `.github/workflows/ci.yml` | Complet, permissions minimales (`contents: read`), aucun secret utilisé | Actions tierces épinglées par SHA |
| `.github/dependabot.yml` | 4 écosystèmes, peu de regroupement, limites de 10 PR | Regroupé et limité |
| `SUPPORT.md`, `GOVERNANCE.md`, `CONTRIBUTORS.md`, index de la doc, procédure de release | Absents | Créés |

## 3. Fichiers créés

- `SUPPORT.md` : où demander de l'aide, ce qui ne doit pas être une issue.
- `GOVERNANCE.md` : rôles, mainteneur principal, décisions, fusion des PR, releases, incidents, devenir mainteneur.
- `CONTRIBUTORS.md` : reconnaissance des contributeurs (seul le mainteneur est listé ; personne n'est ajouté sans
  consentement).
- `docs/README.md` : index de toute la documentation existante, sans déplacer aucun fichier.
- `docs/guides/release-process.md` : SemVer, changelog, tags, releases GitHub, releases de sécurité.
- `.github/ISSUE_TEMPLATE/documentation_issue.yml`.
- `.github/workflows/dependency-review.yml`.
- `.github/labels.yml` et `.github/scripts/sync-labels.sh` : nomenclature de labels et script d'application (non
  exécuté).
- `docs/OPEN_SOURCE_GITHUB_AUDIT.md` : ce rapport.

## 4. Fichiers modifiés

`README.md`, `README.fr.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`,
`.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/{bug_report,feature_request,rule_contribution,config}.yml`,
`.github/dependabot.yml`, `.github/workflows/ci.yml`.

Aucun fichier de code applicatif n'a été modifié par cette mission. Les modifications locales de l'étape 3a
(ADR-0024) sont présentes dans la copie de travail mais **hors du périmètre** : elles ne doivent pas être incluses dans
le commit de cette mission (voir § 13).

## 5. Workflows GitHub ajoutés ou modifiés

- **`dependency-review.yml` (nouveau)** : sur chaque pull request vers `main`, bloque l'ajout d'une dépendance ayant
  une vulnérabilité connue de sévérité haute ou critique. Permissions : `contents: read`. Action épinglée par SHA
  (v4.9.0).
- **`ci.yml`** : `docker/setup-buildx-action` et `docker/build-push-action` (actions tierces) épinglées par SHA de
  commit (v3.12.0 et v6.19.2). Les actions officielles `actions/*` et `github/codeql-action` restent sur des tags
  majeurs. Le reste est inchangé : la CI couvre déjà `mvn verify` avec Testcontainers, le frontend, les licences, les
  images Docker, le test de fumée et CodeQL.
- **Non ajouté, volontairement :** aucun workflow de publication (release, images). La procédure de release reste
  manuelle et validée par le mainteneur.

Contrôle de sécurité des workflows (**observé**) : permissions minimales partout (`contents: read`, plus
`security-events: write` pour CodeQL uniquement), aucun déclencheur `pull_request_target`, aucun secret, aucune
expression `${{ github.event.* }}` interpolée dans un script shell.

## 6. Tests CI réalisés

- **Observé :** `actionlint` 1.7.7 sur `ci.yml` et `dependency-review.yml` : aucune erreur.
- **Observé :** les fichiers YAML (workflows, Dependabot, modèles d'issues, labels) se chargent sans erreur ; le script
  `sync-labels.sh` passe `bash -n`.
- **Observé :** liens relatifs des documents modifiés vérifiés ; tous pointent vers des fichiers existants.
- **Non vérifié :** l'exécution réelle de `dependency-review.yml` et des actions épinglées sur GitHub. Elle n'aura
  lieu qu'après la publication ; le premier run doit être contrôlé.

## 7. Configuration Dependabot

- Maven et npm : hebdomadaire, mises à jour mineures et correctives **regroupées** en une PR par écosystème ; majeures
  une par une pour évaluation ; Angular regroupé (majeures comprises, les paquets doivent évoluer ensemble) ; 5 PR
  ouvertes au maximum.
- Images Docker et GitHub Actions : mensuel, 3 PR au maximum ; les actions sont regroupées.
- Préfixes de commit conventionnels : `build(deps)` et `ci(deps)`.
- **Recommandé :** activer les **alertes Dependabot** et les **mises à jour de sécurité Dependabot** (désactivées).
- **Recommandé :** trier les 12 PR déjà ouvertes, notamment : `maven:3.9-eclipse-temurin-26` (Java 26, non LTS, alors
  que le projet compile en 21 et tourne en 25), `node:26-alpine` et TypeScript 7 (majeures à évaluer).

## 8. Politique de sécurité

- Canal unique : signalement privé de vulnérabilités GitHub (**observé : activé**).
- `SECURITY.md` précise : informations à fournir, délais indicatifs (accusé de réception 3 jours ouvrés, évaluation 10
  jours ouvrés, correctif High/Critical 30 jours si possible, divulgation coordonnée ≤ 90 jours ; délais repris de la
  version existante, **à confirmer par le mainteneur**), confidentialité, failles critiques, publication des correctifs
  (advisory GitHub, CVE si applicable, changelog).
- **Pas de modèle d'issue « security report »** : un formulaire d'issue crée forcément une issue publique. Le lien
  « Security vulnerability (private) » du sélecteur d'issues renvoie vers le signalement privé, ce qui répond à
  l'objectif sans risque de divulgation.

## 9. Organisation des Issues et Pull Requests

- Modèles : bug (description, reproduction, attendu/obtenu, version, mode de déploiement, Java, environnement,
  blockchain, logs expurgés), fonctionnalité (problème, proposition, valeur, cas d'usage, alternatives, impact
  architectural), documentation, contribution de règle. Issues vides désactivées ; liens vers sécurité, aide et docs.
- Modèle de PR : résumé, problème, approche, modules, tests avec résultats réels, compatibilité, sécurité,
  documentation et migration, checklist demandée.
- Labels (**à appliquer après validation**, `bash .github/scripts/sync-labels.sh`) : 26 labels en 5 familles (type,
  priorité, statut, contribution, module). Le script crée ou met à jour, ne supprime jamais.
- Milestones (**recommandé**, sans date) alignées sur la feuille de route réelle : *Core Stability* (phase 0-1,
  dont ADR-0024), *Graph Intelligence* (phase 2), *Autonomous Investigation* (phase 3), *Predictive Signals*
  (phase 4), *Developer Ecosystem* (phase 5, MCP et SDK), *Collaborative Intelligence* (phase 6).
- GitHub Project (**recommandé**, léger) : un tableau unique *Backlog, Ready, In Progress, In Review, Done*, alimenté
  par les issues `status:accepted`. Inutile tant qu'il y a peu de contributeurs ; à créer dès les premières
  contributions externes.
- Discussions (**décision**) : utiles pour les questions d'usage, avec les catégories Announcements, General, Ideas,
  Q&A, Show and Tell, Development. `SUPPORT.md` indique aujourd'hui qu'elles ne sont pas activées.

## 10. Gouvernance

`GOVERNANCE.md` décrit une gouvernance à mainteneur unique, explicitement évolutive : le mainteneur principal
(@alertmanager12-blip) valide tout ce qui est fusionné, décide en dernier ressort et publie les releases ; décisions
dans les PR, ADR pour les décisions d'architecture, consensus paresseux ; critères pour devenir mainteneur. Aucun
comité ni aucune personne n'a été inventé. La politique de contributions assistées par IA est dans
`CONTRIBUTING.md` (responsabilité du contributeur, tests réels, licences, aucun co-auteur inventé, aucune déclaration
obligatoire).

## 11. Licence et conformité

- **Observé :** Apache-2.0 cohérente entre `LICENSE`, les README, `CONTRIBUTING.md`, la landing page et l'ADR-0012.
  Aucun changement proposé.
- **Observé :** la CI vérifie les licences des dépendances Maven et npm d'exécution (liste blanche de l'ADR-0012,
  OFL-1.1 seulement pour les polices), et `NOTICE` liste les composants tiers.
- **Décidé :** le DCO, auparavant déclaré obligatoire sans être vérifié, est désormais **recommandé, pas exigé**
  (`CONTRIBUTING.md`, case facultative du modèle de PR). Aucun CLA.

## 12. Paramètres GitHub restant à configurer (checklist)

À faire dans l'interface GitHub, **après validation** :

- [ ] *About* : description (« Open source, self-hosted blockchain intelligence: explainable risk signals, fund flows
      and alerts. »), topics (`blockchain`, `aml`, `tron`, `ethereum`, `risk-scoring`, `threat-detection`,
      `self-hosted`, `spring-boot`, `angular`), site web si une adresse publique existe.
- [ ] *Settings → Code security* : activer **Dependabot alerts** et **Dependabot security updates**.
- [ ] *Settings → Code security* : vérifier que secret scanning et push protection restent actifs (**observé** : oui).
- [ ] *Security → Secret scanning* : clore les 2 alertes ouvertes comme **faux positifs / utilisées dans des tests**
      (voir § 13).
- [ ] *Security → Code scanning* : trier les 74 alertes CodeQL ouvertes (voir § 13).
- [ ] *Settings → General* : désactiver le Wiki (la documentation vit dans `docs/`), activer « Automatically delete
      head branches », garder le squash merge par défaut.
- [ ] *Issues → Labels* : lancer `bash .github/scripts/sync-labels.sh`.
- [ ] *Issues → Milestones* : créer les milestones du § 9, sans date.
- [ ] *Settings → General → Features* : activer les Discussions si validé, avec les catégories du § 9.
- [ ] *Settings → Rules* : créer le ruleset de `main` du § 14 (étape 1).

## 13. Risques détectés

1. **Alertes de secret (observé, faux positifs)** : les 2 alertes ouvertes portent sur des valeurs factices dans des
   tests (`ConnectorsTest.java` ligne 127 : jeton Telegram de test ; `JdbcRepositoriesPersistenceTest.java` ligne 191 :
   secret de webhook de test). **Observé :** aucune valeur réelle du `.env` (clé admin, jeton Telegram, clé TronGrid,
   identifiant de chat, etc.) n'apparaît dans l'historique ; aucune clé privée, aucun jeton AWS/GitHub/Slack/Stripe réel,
   aucun fichier `.env`, dump ou log versionné ; aucune adresse e-mail personnelle ni chemin local. **Aucune rotation
   nécessaire.**
2. **74 alertes CodeQL ouvertes (observé, non triées)** : 7 hautes (désactivation CSRF dans `SecurityConfiguration`,
   requêtes non protégées par CSRF dans `InvestigationController`, XSS dans `RuleController`, arithmétique sur données
   non fiables dans les simulateurs de démo), 9 moyennes (injection dans les logs), 58 de qualité. Elles ne sont **pas
   confirmées** : la désactivation CSRF est probablement voulue pour une API à clés API sans cookie de session, mais
   chaque alerte doit être examinée et fermée avec une justification, ou corrigée.
3. **Contact du code de conduite** : l'adresse inexistante est remplacée par le profil GitHub du mainteneur. GitHub n'a
   pas de messagerie privée : il faut qu'un moyen de contact figure sur ce profil, et une adresse dédiée reste
   préférable dès qu'elle existe.
4. **Documents stratégiques publics** : `docs/strategy/` (dont `MONETIZATION_STRATEGY.md`) et l'audit PDF restent
   publics, par décision du mainteneur.
5. **Mélange avec l'étape 3a** : la copie de travail contient aussi des modifications non validées de l'étape 3a
   (`oci-*`, `docker-compose.yml`, ADR-0024, guide d'installation, `CHANGELOG.md`). Le commit de cette mission devra
   les exclure fichier par fichier.
6. **Nom du projet** : le README présente désormais ChainSentinel, mais le dépôt, les modules et l'image Docker gardent
   le préfixe `openchain-intelligence` / `oci`. C'est expliqué dans le README ; un renommage complet est une décision
   séparée.
7. **Mainteneur unique** : bus factor de 1. La gouvernance prévoit l'ajout de mainteneurs.

## 14. Recommandations prioritaires

1. **Ajouter un moyen de contact sur le profil GitHub du mainteneur** (cible du code de conduite).
2. **Trier les alertes CodeQL hautes** (§ 13.2) et clore les 2 alertes de secret comme faux positifs.
3. **Activer les alertes et mises à jour de sécurité Dependabot**, puis trier les 12 PR ouvertes.
4. **Protection de `main`, en deux temps** (ruleset GitHub, gratuit pour un dépôt public) :
   - *Étape 1, tout de suite* : interdire les force pushes et la suppression de `main`, exiger que les checks CI
     passent pour les pull requests ; le mainteneur garde le droit de pousser directement (bypass) pendant la
     stabilisation.
   - *Étape 2, dès les premières contributions externes ou un second mainteneur* : pull request obligatoire pour
     tous, 1 approbation, checks obligatoires (backend, frontend, licences, Docker, CodeQL, dependency review),
     conversations résolues avant fusion.
6. **Appliquer les labels**, créer les milestones, puis ouvrir quelques issues `good first issue` réelles (par exemple
   les alertes CodeQL de qualité, ou des corrections de documentation) pour accueillir les premiers contributeurs.
7. **Plus tard :** publier une première release quand le mainteneur la juge prête (numéro à décider), activer les
   Discussions et un GitHub Project quand la communauté le justifie, envisager l'épinglage par SHA de toutes les
   actions.

## Décisions du mainteneur (10 novembre 2026)

| Sujet | Décision |
|---|---|
| Contact du code de conduite | Profil GitHub du mainteneur |
| DCO | Recommandé, pas exigé |
| Documents stratégiques | Restent publics |
| Publication | Fichiers de cette mission commités et poussés (sans l'étape 3a) ; labels, alertes et mises à jour de sécurité Dependabot, ruleset de `main` (étape 1), Wiki désactivé, description et topics appliqués |
| Délais de `SECURITY.md`, milestones, Discussions, Project | Non tranchés ; restent des recommandations |
