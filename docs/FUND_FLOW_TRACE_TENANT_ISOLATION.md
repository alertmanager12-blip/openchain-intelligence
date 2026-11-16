# Fund Flow Trace — isolation multi-client (exigence P0)

Statut : **socle implémenté le 2026-11-16** (décision D1, option b) : table `client_scopes` (V12), `client_scope_id` sur
`api_keys` et `users`, périmètre porté par la clé ou par la revendication OIDC (`oci.security.multi-client`, désactivé par
défaut), administration des clés limitée au périmètre de l'appelant, tests N8 et N9. Restent avec l'API de traçage : les
tables du traçage avec `client_scope_id` et RLS, les tests N1 à N7 et N10 à N12, le journal `audit_log`. Le nom retenu est
`client_scope` (et non `scope`), pour ne pas le confondre avec les permissions des clés (`api_keys.scopes`).

Texte d'origine (2026-11-12, code de `94ea42c`), stratégie : **[F]** fait vérifié (référence) ;
**[P]** proposition. Exigence : aucune exposition du moteur de traçage (P0-D) par l'API avant que cette isolation soit en
place et prouvée par des tests négatifs.

## 1. État actuel [F]

- **Aucune notion de client.** `V1__operator_state.sql:5` : « Single tenant for now: no tenant_id / row-level security
  yet ». TODO identiques dans `ApiKeyService.java:37` et `RuleRegistry.java:41`. Aucune colonne, entité, DTO ni principal
  ne porte d'identifiant de client.
- **Authentification** :
  - clés API `oci_<env>_<préfixe 8>_<secret>` (`ApiKeyService.java:44`) ; seul `HMAC-SHA256(pepper, secret)` est stocké,
    avec une comparaison en temps constant ;
  - table `api_keys(id, name, env, prefix, secret_hash, scopes, created_by, created_at, expires_at, last_used_at,
    revoked_at)`, sans rôle ni client ;
  - OIDC optionnel, qui lit des permissions et des rôles mais **aucune revendication de client**
    (`SecurityConfiguration.java:143-155`) ;
  - en mode démo, une clé `*` et une clé d'interface.
- **Autorisation** : uniquement par motifs d'URL (`SecurityConfiguration.java:71-110`), sans `@PreAuthorize` ni sécurité
  de méthode.
  - 13 permissions (`Scopes.java:15-29`) ;
  - `/wallets/*/exposure` et `/graph/**` exigent `investigation:read` ;
  - `/admin/**` exige `keys:admin`.
- **Journal d'audit** : texte du journal `oci.audit` (clés, rôles, règles, webhooks, backfill), sans table.
  - Ne sont pas audités : les lectures, les consultations d'adresses, la liste de surveillance et les signalements
    d'étiquettes.
- **Tests** : `SecurityApiTest` couvre 401, 403, 400 (clé en query), la révocation et CORS. Il n'existe **aucun test
  d'accès croisé**, et pour cause : aucun client n'existe.
- **États partagés en mémoire**, globaux pour tous les appelants :
  - flux, signaux, scores et profils ;
  - étiquettes ;
  - cache des soldes (LRU 10 000) ;
  - historique d'événements ;
  - `AddressHistoryService.queued/lastViewed` ;
  - liste de surveillance, règles, webhooks ;
  - cache des types de contrat ;
  - cache de déduplication du moteur de règles.
- **Fuite d'information déjà possible, même en mono-client** : `address_history_progress` liste toutes les adresses
  consultées, et la mémoire garde leurs flux (exemptés de la purge). Toute clé `wallet:read` voit donc, via la page
  wallet, si une adresse a déjà été « regardée » par quelqu'un, et en bénéficie (historique déjà chargé). Avec plusieurs
  clients, cela révèle l'activité d'investigation d'un client à un autre.

## 2. Modèle proposé [P]

### 2.1 Identité et propagation

- **Modèle de déploiement par défaut : une instance par client** (ADR-0026 § 3), isolation physique. Le code doit
  néanmoins porter un `scope_id` dès la première migration du traçage, pour que le passage au multi-client ne soit pas
  une réécriture.
- **Entité `scope`** (id, nom, statut). Le scope `default` est créé par la migration.
- **Principal** : `ScopedPrincipal(subject, scopeId, permissions)`.
  - Clé API : nouvelle colonne `api_keys.scope_id`, non nulle, `default` par défaut.
  - OIDC : revendication configurable (`oci.security.oidc.scope-claim`) ; un jeton sans scope valide est refusé (401),
    jamais rattaché par défaut quand le mode multi-client est actif.
- **Propagation** : le scope n'est **jamais lu d'un paramètre de requête ni d'un en-tête libre**. Il vient du principal
  authentifié, passe par un `ScopeContext` explicite (paramètre des services, pas un ThreadLocal implicite), et les
  travaux asynchrones (traçages) le stockent dans leur ligne.

### 2.2 Ce qui est isolé, ce qui est partagé

| Donnée | Isolée par scope | Raison |
|---|---|---|
| Traçages, nœuds, preuves gelées, attributions, constats, exports | **oui** | résultat d'une investigation |
| Liste de surveillance, verdicts, alertes, règles, webhooks, utilisateurs, clés | **oui** | configuration et décisions du client |
| Demandes d'historique d'adresse (qui a demandé quoi, quand) | **oui** | révèle l'activité d'investigation |
| Événements d'historique d'une adresse (données publiques de chaîne) | partagés, **en lecture seule par le moteur** | coût RPC ; mais le fait qu'ils existent ne doit pas être visible d'un autre scope (voir 2.3) |
| Flux et blocs indexés en direct, étiquettes publiques (sanctions, gels, communauté) | partagés | données publiques |
| Étiquettes privées ou signalements d'un client | **oui** | jugement du client |
| Caches (soldes, types de contrat) | partagés, clés sans scope, contenu public uniquement | aucun résultat dérivé d'un scope n'y entre |

### 2.3 Non-divulgation par effet de bord

- Une réponse ne doit jamais être plus profonde ou plus rapide pour le scope B parce que le scope A a déjà traité la même
  adresse (ADR-0026 § 3). Pour le traçage, le moteur peut **réutiliser** un historique déjà rapatrié (donnée publique),
  mais la couverture affichée à B est celle que **B** a demandée et obtenue, et la page wallet de B ne montre pas un
  historique demandé par A comme « chargé ».
- Pas de messages d'erreur distincts selon l'existence d'une ressource d'un autre scope : 404 identique, qu'elle
  n'existe pas ou qu'elle appartienne à un autre scope.
- Identifiants de traçage non devinables (UUID aléatoires), mais la sécurité ne repose **pas** dessus.

### 2.4 Application du contrôle

- **Dans les repositories** : toute requête sur une table isolée prend `scopeId` en paramètre obligatoire (signature de
  méthode) ; aucune méthode « find by id » sans scope.
- **En défense en profondeur dans PostgreSQL** : Row-Level Security sur les tables du traçage
  (`USING scope_id = current_setting('oci.scope')::text`), positionné par transaction. Optionnel en mono-instance,
  obligatoire avant une offre multi-client hébergée.
- **Permissions** : `investigation:read` existe [F] ; ajouter `investigation:write` (lancer, annuler) et
  `investigation:export`. Un export est une lecture auditée.

### 2.5 Journalisation des accès

Table `audit_log` (append-only) : instant, scope, sujet, action (`trace.create`, `trace.read`, `trace.export`,
`trace.cancel`, `history.request`, `exposure.read`, refus 403/404 cross-scope), ressource, résultat, adresse IP.
Rétention alignée sur ADR-0026 (verdicts 5 ans ; accès : à décider). Le journal `oci.audit` existant continue.

## 3. Tests négatifs obligatoires [P]

Deux scopes A et B, chacun avec une clé `investigation:*`, plus une clé admin de A.

| # | Action | Attendu |
|---|---|---|
| N1 | B lit le traçage de A (`GET /traces/{idA}`) | 404, journalisé comme refus |
| N2 | B lit graphe, chemins, preuves, attribution, export du traçage de A | 404 pour chacun |
| N3 | B annule le traçage de A | 404, traçage de A inchangé |
| N4 | B liste les traçages | ne contient aucun traçage de A |
| N5 | admin de A lit un traçage de B | 404 (admin limité à son scope) |
| N6 | B consulte une adresse que A a déjà tracée | couverture et réponse identiques à celles d'une adresse jamais consultée par B |
| N7 | B devine des identifiants (séquentiels, UUID d'A connus) | 404 |
| N8 | jeton OIDC sans revendication de scope (multi-client actif) | 401 |
| N9 | requête avec en-tête ou paramètre `scope=A` envoyée par B | ignoré ; réponse de B |
| N10 | requête SQL directe sans `oci.scope` positionné (RLS active) | aucune ligne |
| N11 | export de A, puis tentative de B avec l'URL de l'export | 404 |
| N12 | traçage asynchrone de A en cours ; B interroge le statut | 404 |

Ces tests font partie de la définition de « terminé » de toute API de traçage (P1), et chaque nouvel endpoint isolé
ajoute sa ligne.

## 4. Migration et compatibilité

- Migration additive : table `scope`, `scope_id` non nul avec défaut `default` sur `api_keys`, `users` et les tables du
  traçage. Les autres tables (alertes, règles, liste de surveillance…) suivent dans une migration séparée : elles ne
  bloquent pas le traçage si une seule instance sert un seul client, mais **bloquent** toute offre multi-client.
- Contrainte `watchlist unique(network, address)` à transformer en `unique(scope_id, network, address)` le jour du
  multi-client.

## 5. Risques et décisions

| Sujet | Décision demandée |
|---|---|
| Mono-instance par client d'abord, `scope_id` dans le code dès le traçage | à valider |
| RLS PostgreSQL : maintenant (tables du traçage) ou avant le multi-client hébergé | proposition : tables du traçage dès leur création |
| Partage des événements d'historique rapatriés entre scopes (coût RPC) contre isolation stricte (re-rapatrier par scope) | proposition : partage des données publiques, isolation des demandes et de la couverture affichée |
| Rétention du journal d'accès | à décider (obligations des clients) |
