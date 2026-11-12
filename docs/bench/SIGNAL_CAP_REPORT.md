# Étape 1 (suite de la mesure 3b) : plafond des signaux — rapport

Date : 2026-11-11. Branche `wip/adr-0024-step-3a` après `53566bd`, modifications **non commitées**, **non déployées**.
Origine : `docs/ADR-0024-STEP-3B-VALIDATION.md` § 5.1 (269 676 signaux pour un plafond de 150 000, en hausse de
≈ 56 000/h : les signaux des wallets ayant un profil n'étaient jamais retirés).

## Changements

| Fichier | Changement |
|---|---|
| `oci-intelligence/.../detection/InMemorySignalRepository.java` | **plafond par wallet** (64 par défaut) appliqué à l'enregistrement ; **plafond global exact** `evictToCap` ; compteurs de retraits par motif, sévérité et type ; `findAll` |
| `oci-intelligence/.../detection/SignalRepository.java` | `EvictionReason`, `evictToCap`, `evictionCounts`, `findAll` |
| `oci-app/.../chain/ChainIngestionService.java` | la purge applique le plafond global par paliers ; `TrimResult.signalsRemovedBySeverity` |
| `oci-app/.../config/Bootstrap.java` | la ligne de journal de chaque purge détaille les signaux retirés par sévérité |
| `oci-app/.../config/MetricsConfiguration.java` | `oci_signals_evicted_total{reason,severity}`, `oci_signals_evicted_by_type_total{reason,type}` |
| `oci-app/.../config/OciProperties.java`, `ModulesConfiguration.java`, `docker-compose.yml` | `OCI_INGESTION_WORKING_SET_MAX_SIGNALS_PER_WALLET` (64) |
| `oci-app/.../journal/JournalingStores.java` | délégation des nouvelles méthodes |
| `docs/guides/installation.md` | politique et métriques documentées |

## Politique de retrait

**Par wallet** (à l'enregistrement d'un signal, au-delà de 64) : le **type de signal le plus représenté** cède d'abord
(sa sévérité la plus faible, puis son plus ancien) ; les HIGH et CRITICAL ne partent que s'il ne reste rien de plus
faible. Un signal faible qui arrive dans un wallet déjà plein de signaux graves est lui-même retiré : il est compté et
n'est pas annoncé comme nouveau.

**Global** (à chaque purge, exactement au plafond, adresses exemptées non comptées) : (0) INFO à MEDIUM des wallets
sans profil, (1) INFO à MEDIUM des wallets avec profil, (2) HIGH et CRITICAL des wallets sans profil, (3) HIGH et
CRITICAL des wallets avec profil ; dans un palier, sévérité la plus faible puis plus ancien.

### Deux écarts avec le plan, trouvés par les tests

1. **Par wallet, retrait par sévérité seule (première version) : rejeté.** La mesure d'impact a montré qu'il faisait
   disparaître des **catégories entières** de preuves : sur un wallet de 203 signaux, plus aucun signal de fan-out ni
   de montant inhabituel, score 83 → 77. Remplacé par le retrait dans le type le plus représenté (voir les chiffres).
2. **Global, « wallets sans profil d'abord, toutes sévérités » (ordre du plan) : rejeté.** Le test d'intégration a
   montré qu'il retirait des HIGH et CRITICAL avant des LOW de wallets actifs, contraire à la priorité de protection
   des signaux graves. Remplacé par les quatre paliers ci-dessus.

## Impact mesuré sur les scores et la détection

Wallet très actif synthétique (`SignalCapScoreImpactTest`) : 200 signaux sur 30 jours (transfert immédiat, fan-out,
montant inhabituel, structuration) et 3 expositions HIGH à un mixer.

| | Sans plafond | Plafond 64, par sévérité seule (rejeté) | Plafond 64, type le plus représenté (retenu) |
|---|---:|---:|---:|
| Signaux conservés | 203 | 64 | 64 |
| Score | **83 HIGH** | 77 HIGH | **83 HIGH** |
| Facteur exposition mixer | 49,45 | 49,45 | 49,45 |
| Facteur structuration | 17,67 | 17,67 | 17,67 |
| Facteur transfert immédiat | 9,85 | 9,85 | 9,85 |
| Facteur fan-out | 4,60 | **0,00** | 4,57 |
| Facteur montant inhabituel | 1,83 | **0,00** | 1,84 |

Effets sur la détection :

- **Détecteurs** : aucun effet direct ; ils lisent les flux, pas les signaux. Banc complet inchangé (16 vrais positifs,
  0 faux positif, 8 capacités absentes ; 12 scénarios historiques et R01 identiques).
- **Scores** : chaque facteur est plafonné par son poids ; garder les signaux récents de chaque type conserve l'essentiel
  (écart ≤ 0,03 point par facteur sur le cas mesuré).
- **Signal retiré puis redétecté** : même identifiant, réenregistré ; l'alerte existante n'est pas dupliquée (garde de
  rejeu des alertes).
- **Console** : la fiche d'un wallet montre au plus 64 signaux.

## Tests exécutés

| Tests | Résultat |
|---|---|
| `SignalCapTest` (6) : plafond par wallet (sévérité puis ancienneté), signal faible non annoncé dans un wallet saturé, **chaque type garde ses derniers signaux**, wallet très actif sans monopolisation, plafond global exact par paliers avec exemptions, HIGH retiré seulement en dernier | 6 / 6 |
| `SignalCapScoreImpactTest` : HIGH conservés, aucun facteur disparu, écart de score ≤ 1 | réussi (83 → 83) |
| `WorkingSetTrimTest.globalSignalCapIsExactAndKeepsGraveSignalsOfActiveWallets` (application complète) : plafond exact, palier 0 parti d'abord, aucun HIGH ni CRITICAL retiré tant qu'il reste plus faible, exemptés intacts, détail par sévérité égal au nombre de retraits | réussi |
| `mvn -B verify` | **BUILD SUCCESS, 552 tests, 0 échec, 0 ignoré** |

## Limites

- Les adresses **exemptées** (liste de surveillance, historiques chargés) ne sont ni retirées ni comptées : c'est
  l'étape 2.
- Le plafond global trie tout le stock à chaque purge (150 000 signaux au plus) ; à surveiller dans la durée de purge.
- Pas encore observé en production : la nouvelle mesure de 12 heures (étape 4) le vérifiera.
- L'ancienne méthode `evictOldest` reste disponible (tests de l'étape 3b) mais n'est plus utilisée par la purge.
