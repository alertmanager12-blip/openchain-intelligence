# P0, item 2 : réorganisations blockchain — scénario R01 et conception

Date : 2026-11-10. Statut : **validée et implémentée (non commitée)** ; résultats et écarts : `docs/P0_REORG_REPORT.md`.

## 1. Le défaut

Quand un bloc quitte la chaîne canonique, l'indexeur émet ses événements avec `removed = true`
(`oci-blockchain/.../indexer/BlockIndexer.java:139-170`). Pour un transfert, l'intelligence l'ignore :
`IntelligencePipeline.onEvent` renvoie `DUPLICATE` (`IntelligencePipeline.java:122`) et `ChainIngestionService`
n'annule que les gels (`ChainIngestionService.java:308-313`). Le flux, ses effets sur les profils, les signaux qui en
dérivent, les scores et les alertes restent. Seul le moteur de règles retire la contribution de l'événement à ses
fenêtres (`RuleEngine.java:160-183`) ; une alerte déjà créée reçoit seulement un commentaire (`AlertService.java:137-147`).

Fréquence : `oci_reorg_total` = 0 pendant la mesure du 2026-11-10 (12 à 19 confirmations avant indexation). Rare, mais
un signal fondé sur des fonds qui n'existent pas est une **preuve fausse**.

## 2. Scénario R01 (écrit avant la conception, attentes figées)

Banc étendu : un scénario peut désormais contenir une chronologie (`BenchScenario.retract`, `reinclude`) jouée dans
l'ordre déclaré ; un retrait est l'événement exact qu'émet l'indexeur (même identifiant, `removed = true`). Ces
scénarios sont évalués sur l'**état final** du stock de signaux. Les 12 scénarios d'origine sont évalués comme avant.

| Scénario | Situation | Attente | Résultat aujourd'hui |
|---|---|---|---|
| R01a | la mule perd son flux entrant | plus de `FAST_FORWARDING` | **échec** : le signal reste |
| R01b | structuration sur 4 transferts, 1 retiré, 3 restent | `STRUCTURING` toujours présent (recalculé) | passe (rien n'est retiré) ; protège contre une suppression aveugle |
| R01c | structuration sur 3 transferts, 1 retiré, 2 restent | plus de `STRUCTURING` | **échec** |
| R01d | le transfert venant du mixer est retiré | plus de `MIXER_EXPOSURE` sur W ni d'`INDIRECT_EXPOSURE` sur Z (chemin dérivé) | **échec** (2 signaux) |
| R01e | retrait reçu deux fois, puis réinclusion dans un autre bloc | `FAST_FORWARDING` présent, une fois | passe (rien n'est retiré) ; protège l'idempotence et la réinclusion |

Référence : 4 signaux fondés sur des fonds retirés persistent. Objectif : 5 scénarios R01 conformes, banc d'origine
inchangé.

## 3. Conception

### 3.1 Principe

Un retrait est traité comme l'**inverse exact** de l'application du flux, puis les conclusions qui en dépendaient sont
**réévaluées à partir des preuves restantes** (jamais supprimées sans recalcul). Le traitement est idempotent : retirer
un flux inconnu ou déjà retiré n'a aucun effet.

### 3.2 Étapes de `IntelligencePipeline.retract(ChainEvent)` (nouvelle méthode, sous le même verrou que l'ingestion)

1. **Flux** : `FlowStore.remove(eventId)` (nouvelle méthode) ; si le flux n'est pas connu, fin (idempotence).
2. **Identifiant** : retirer l'identifiant des ensembles de déduplication (`seenEvents` du pipeline, index du stock de
   flux) pour qu'une réinclusion soit appliquée comme un événement nouveau. Les gels le font déjà (`:174`).
3. **Profils** : `WalletProfileAccumulator.revert(flow)` pour l'émetteur et le destinataire : totaux et compteurs par
   jeton (exacts), compteur de la contrepartie, instant d'activité ; le compteur de transactions n'est décrémenté que
   lorsque le dernier flux connu de la transaction est retiré (les hachages récents gardent un compte de flux).
4. **Soldes** : retirer l'entrée de `BalanceBook` attachée à cet événement.
5. **Signaux dépendants** : `SignalRepository.findByEvidenceReference(txHash)` (nouvel index hachage → signaux) trouve
   tous les signaux dont une preuve de type flux cite la transaction, **quel que soit le wallet** (cela couvre
   l'exposition indirecte en aval, R01d).
6. **Réévaluation** (exigence « recalculer, pas supprimer aveuglément ») : pour chaque wallet concerné, les signaux
   dépendants sont retirés puis la détection est rejouée sur les preuves restantes :
   - détecteurs à déclencheur (structuration, transfert immédiat, fan-out, montant inhabituel, vidage) : rejoués avec
     comme déclencheurs les flux restants du wallet dans la fenêtre du signal retiré (au plus 24 h avant et après le
     flux retiré, borné par le budget de l'item 5) ;
   - détecteurs sans déclencheur (exposition, circulation) : une exécution sans déclencheur pour le wallet.
   Les identifiants de signaux sont déterministes : un signal encore justifié revient avec le **même identifiant** et
   des preuves mises à jour (R01b) ; un signal qui n'est plus justifié ne revient pas (R01a, R01c, R01d).
7. **Scores** : recalcul pour tous les wallets touchés (deux parties du flux et wallets des signaux dépendants) ; une
   entrée d'historique n'est ajoutée que si le score change (comportement existant).
8. **Événements de signal** : pour chaque signal retiré et non recréé, émettre un événement `DETECTION_SIGNAL` retiré,
   pour que le moteur de règles retire sa contribution (mécanisme existant).
9. **Résultat** : `RetractResult` (flux retiré, wallets touchés, signaux retirés, recréés, scores changés), journalisé
   et compté (`oci_reorg_reverted_flows_total`, `oci_reorg_signals_total{outcome=removed|recomputed}`).

`ChainIngestionService.processLocked` appelle `retract` pour tout événement retiré de type transfert (natif ou jeton),
en plus du traitement actuel (historique, règles).

### 3.3 Alertes

Une alerte n'est **jamais supprimée** (piste d'audit). Pour une alerte dont un événement déclencheur ou le signal
d'origine (`subject_event_id = signal:<id>`) est retiré :

- entrée d'historique « preuve retirée par réorganisation au bloc N », avec la liste des preuves qui restent ;
- si un signal d'origine est recréé (même identifiant), l'alerte reste valide et l'historique le dit ;
- si **aucune** preuve ne reste : indicateur `evidenceWithdrawn = true`, statut inchangé, affiché « preuve retirée, à
  revoir » dans la console ; notification `alert.retracted` aux connecteurs qui y sont abonnés (aujourd'hui acceptée à
  l'abonnement mais jamais envoyée, `WebhookConnector.java:47`).

Migration : une colonne `evidence_withdrawn` sur `alerts` (V10). Le traitement complet du cycle de vie des alertes
reste du ressort de la politique de routage (item 6) ; ici, seulement la cohérence avec la preuve.

### 3.4 Chemins dérivés

Le graphe et l'exposition de l'API sont recalculés à chaque appel à partir du stock de flux : une fois le flux retiré,
ils sont cohérents sans autre changement. Les signaux d'exposition indirecte, eux, sont des conclusions stockées : ils
sont couverts par l'étape 6.

### 3.5 Limites qui resteront

- **Activité repliée** : un flux plus ancien que la partie exacte de l'accumulateur (4 096 derniers événements, 2 048
  instants) ne peut pas être retiré exactement de l'activité repliée (premier instant, plus long silence) ; les totaux
  restent exacts. Documenté, compté.
- **Après un redémarrage** : une réorganisation de blocs indexés avant le redémarrage est détectée, mais leurs
  événements ne sont plus connus (le point de reprise ne garde que les références de blocs) ; sans journal, l'état en
  mémoire correspondant n'existe déjà plus. Journalisé comme tel.
- **Coût** : la réévaluation est bornée par les budgets de l'item 5 ; si un budget est atteint, le résultat le dit.

## 4. Stratégie de test

| Niveau | Tests |
|---|---|
| Unitaires | `FlowStore.remove` (idempotent) ; `WalletProfileAccumulator.revert` (totaux, compteurs, transaction à plusieurs flux) ; index par référence de preuve ; `retract` : signal retiré, signal recalculé avec preuves mises à jour (R01b), score recalculé, réinclusion acceptée, double retrait sans effet |
| Banc | R01a à R01e conformes ; 12 scénarios d'origine inchangés |
| Intégration | `ChainIngestionService` avec le `BlockIndexer` (réorganisations de profondeur 1 et 3 existantes dans `BlockIndexerTest`) ; alerte créée sur un flux retiré : historique et indicateur ; alerte sur un signal recréé : reste valide ; webhook `alert.retracted` envoyé |
| Projet | `mvn verify` complet |

## 5. Impact sur la détection et critères de réussite

| Critère | Aujourd'hui | Cible |
|---|---|---|
| Scénarios R01 conformes | 2 / 5 (dont 2 par hasard) | 5 / 5 |
| Signaux fondés sur des flux retirés après réorganisation | 4 (banc) | 0 |
| Signaux encore justifiés conservés (R01b) | oui, par absence de traitement | oui, recalculés |
| Banc d'origine (12 scénarios) | précision 1,00 (0,93 sans R1), rappel 1,00 des détecteurs existants | identique |
| Alertes dont toute la preuve a disparu signalées | non | oui (indicateur + historique + webhook) |

Effet attendu : aucune détection perdue sur des flux valides ; suppression des conclusions fondées sur des fonds
inexistants. Point de décision pour toi : la migration V10 et l'envoi de `alert.retracted`, qui touchent le cycle de
vie des alertes.
