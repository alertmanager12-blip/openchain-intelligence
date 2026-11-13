# Revue de l'ADR-0026 — périmètre client et isolation

Revue du 2026-11-12 sur le code de `94ea42c`. L'ADR n'est **pas modifié** et reste « proposed (principe approuvé le
2026-11-10, conception à valider) ». **[F]** fait vérifié ; **[P]** proposition de la revue.

## 1. Décision proposée par l'ADR

1. **Trois profondeurs d'analyse :**
   - W (liste de surveillance) : persistée, avec tous les détecteurs et leurs fenêtres complètes ;
   - C (contreparties directes de W) : profil compact et vérifications directes ;
   - G (reste de la chaîne) : mémoire de travail bornée et filtrage léger, jamais présenté comme complet.

   Pas d'expansion automatique au-delà d'un saut ; l'exposition indirecte n'est calculée qu'à la demande, avec des
   budgets.
2. **Bornes :**
   - 10 000 contreparties C par adresse W ;
   - 500 000 C par client ;
   - 50 000 adresses W par client ;
   - à la demande : 3 sauts et 500 nœuds au plus.
3. **Isolation :**
   - une instance par client par défaut ;
   - `scope_id` sur tous les enregistrements du périmètre ;
   - filtrage dans les repositories ;
   - tests d'autorisation ;
   - données publiques partagées, données dérivées jamais partagées.
4. **Persistance :**
   - flux de W partitionnés par mois, 13 mois ;
   - instantanés de profils ;
   - verdicts immuables 5 ans ;
   - mémoire de chaîne non persistée.
5. **Verdicts à deux axes :**
   - couverture : DEEP, COUNTERPARTY, SCREENED, NOT_COVERED ;
   - résultat : RISK_FOUND, NO_RISK_FOUND (seulement avec DEEP ou COUNTERPARTY et toutes les vérifications faites),
     NO_LISTED_EXPOSURE, INCONCLUSIVE.

L'ADR remplacerait l'étape 2 de l'ADR-0024 (journal de la chaîne entière) et l'étape 3c.

## 2. Problème résolu

Les faits sont rappelés dans l'ADR et confirmés depuis [F] :
- analyser toute la chaîne en profondeur coûte trop cher (4,8 M de flux par jour, rechargement du journal à 316 s pour
  50 000 flux, mémoire plafonnée) ;
- l'analyse de la chaîne entière ne regarde qu'environ 2 h en arrière en régime établi ;
- la liste de surveillance de production est vide, donc **aucune adresse n'a d'analyse profonde** ;
- aucune isolation entre clients n'existe (`V1:5`, voir `FUND_FLOW_TRACE_TENANT_ISOLATION.md`).

## 3. Alternatives

| Option | Description | Verdict |
|---|---|---|
| A. ADR tel quel | profondeur par périmètre, isolation par `scope_id`, verdicts à deux axes | cible juste |
| B. Journal de la chaîne entière (ADR-0024 étape 2) | tout persister 7 jours | 25 à 28 Go et un rechargement inutilisable ; pas plus profond que 7 jours ; voir `FLOW_JOURNAL_CAPACITY_ANALYSIS.md` |
| C. Instance par client sans `scope_id` | isolation physique seulement | simple aujourd'hui, mais le passage au multi-client devient une réécriture |
| D. ADR amendé [P] | A, avec les ajouts du § 9 | **recommandé** |

## 4. Avantages et inconvénients

- **Avantages.**
  - Le coût suit la taille du périmètre du client, pas l'activité de la chaîne.
  - L'analyse est profonde là où elle compte (13 mois sur W).
  - Aucune réponse « sûre » ne masque une analyse partielle.
  - Il fournit la base de `POST /screening`, des investigations (ADR-0022) et du MCP (ADR-0025).
- **Inconvénients.**
  - Une adresse hors périmètre reçoit une réponse superficielle, voulue et dite.
  - L'intégration d'un client exige qu'il déclare ses adresses.
  - Le `scope_id` doit être propagé dans tous les repositories.
  - Des partitions sont à exploiter.
  - Les alertes comportementales hors W deviennent informatives.

## 5. Conséquences sur les modules existants

- **`oci-app`** : migration `scope` et `scope_id` (clés, utilisateurs, liste de surveillance, alertes, règles, webhooks,
  verdicts, traçages) ; principal portant le scope ; repositories filtrés ; contrainte
  `watchlist unique(network, address)` → `unique(scope_id, network, address)` [F, V7].
- **`AddressHistoryService`** : les demandes deviennent liées au scope ; les événements publics peuvent rester partagés.
- **Purge (ADR-0024)** : les adresses exemptées deviennent les W de tous les scopes, plus les historiques demandés.
  Aujourd'hui, toute adresse dont l'historique a été consulté est exemptée [F] ; la mesure des exemptions (étape 2)
  quantifie ce coût.
- **Règles et détecteurs** : les fenêtres complètes ne s'appliquent qu'à W ; la chaîne entière reste dans la fenêtre de
  la mémoire de travail.
- **Fund Flow Trace** : le traçage lit d'abord les flux persistés du périmètre, puis l'historique à la demande, puis la
  mémoire ; ses résultats sont isolés par scope.

## 6. Mémoire et performances

- C : estimation de l'ADR ≤ 300 o par entrée, soit ≤ 150 Mo par client. **À confirmer** par l'histogramme de la mesure de
  12 h en cours : le profil complet coûte environ 1 240 o de surcoût fixe (option G de l'analyse de compaction des clés),
  donc un profil compact devra être réellement compact.
- Persistance de W : 1,3 Go (PSP) à 12 Go (exchange) sur 13 mois selon les scénarios de l'ADR. Taille de ligne mesurée :
  474 o avec 2 index, environ 650 o avec les index d'adresse (estimation, voir l'étude du journal).
- Filtrage d'une adresse inconnue en moins de 300 ms (p95) : réaliste depuis les étiquettes et l'exposition directe en
  mémoire ; **à mesurer**.

## 7. Risques de sécurité

- **Accès croisé** : le risque principal. Il faut un filtrage obligatoire dans les repositories, la RLS en défense en
  profondeur et des tests négatifs (12 cas proposés dans la stratégie d'isolation).
- **Divulgation par effet de bord** : un historique déjà chargé par A ne doit pas rendre la réponse de B plus profonde
  ni révéler l'intérêt de A. L'ADR le dit pour la profondeur ; il faut aussi le dire pour la couverture affichée.
- **Scope pris dans la requête** : interdit ; il vient seulement du principal authentifié.
- **Rétention de 5 ans des verdicts** : données sensibles. Il faut chiffrer ou contrôler l'accès aux sauvegardes, et
  journaliser les accès.

## 8. Compatibilité avec les objectifs

Elle est forte. C'est la seule voie mesurée qui donne de la profondeur (13 mois) sans exploser la mémoire et le disque.
Elle impose l'honnêteté sur la couverture, ce qui est aussi la règle de Fund Flow Trace.

## 9. Modifications nécessaires avant acceptation [P]

1. Ajouter les **traçages** (ADR-0022) et leurs exports à la liste des données isolées par `scope_id`.
2. Ajouter la **non-divulgation par la couverture** : une demande d'historique est liée au scope qui l'a faite.
3. Remplacer les estimations « assumed » par les mesures disponibles : taille de ligne mesurée (474 o) et histogramme de
   la mesure de 12 h, dès qu'elle est validée.
4. Préciser l'alignement du vocabulaire de couverture avec P0-B : COMPLETE, PARTIAL, TRUNCATED, UNAVAILABLE,
   NOT_YET_INDEXED par plage, et DEEP, COUNTERPARTY, SCREENED, NOT_COVERED par adresse.
5. Préciser la stratégie RLS : tables du traçage dès leur création ; autres tables avant toute offre multi-client
   hébergée.
6. Trancher la question ouverte « instance multi-scope nécessaire pour les premiers pilotes ? ». Proposition : non, une
   instance par client, mais `scope_id` présent dans le code dès le traçage.
7. Inscrire le **trou de reprise des indexeurs** (blocs sautés, aujourd'hui seulement journalisés) parmi les limites à
   déclarer dans les verdicts.

**Recommandation : accepter avec ces amendements, après les validations prévues par l'ADR lui-même :**
- histogramme de la mesure en cours ;
- distribution réelle des contreparties ;
- relecture du vocabulaire des verdicts.

**L'isolation (§ 3 de l'ADR) est une exigence P0 et précède toute API de traçage.**
