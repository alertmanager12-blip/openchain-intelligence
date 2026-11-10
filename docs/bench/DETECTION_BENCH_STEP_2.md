# Banc de détection — étape 2 (P0) : correction des défauts des détecteurs

Date : 2026-11-10. Branche `wip/adr-0024-step-3a` (base `299db4f`), modifications **non commitées**.
Référence avant correction : `docs/bench/DETECTION_BENCH_BASELINE.md`.
Régénérer le rapport : `mvn -pl oci-intelligence test -Dtest=DetectionBenchTest` (écrit
`oci-intelligence/target/detection-bench.md`).

## Règles suivies

- **Le banc est resté figé.** `BenchScenarios.java` (12 scénarios et leurs attentes) a été copié avant la première
  correction et comparé à la fin : aucune différence. Une seule modification d'attente a eu lieu, **avant** l'étape 2
  et avant l'enregistrement de la référence (une erreur de ma part : `RAPID_DRAIN` attendu sur un cas de transfert
  immédiat, remplacé par `FAST_FORWARDING`, et ajout du scénario S08 de vidage d'un solde ancien).
- Chaque correction : test de non-régression, tests du module, banc complet ; à la fin, `mvn verify` du projet.
- Pas de réorganisations, de plafond des signaux ni de politique de routage des alertes (étapes distinctes).

## Défauts corrigés

| # | Défaut | Correction | Fichiers et méthodes |
|---|---|---|---|
| 1 | `UnusualAmount` : z infini (historique constant) et z démesuré quand un même montant domine (MAD nulle, repli sur l'écart moyen : z = 17 pour 9 600 contre une médiane de 5 000) ; référence mélangeant entrées et sorties (un collecteur qui renvoie la somme de ses dépôts paraissait anormal) | référence calculée **dans le même sens** que la transaction examinée (envois ou réceptions), transferts vers soi exclus ; quand la MAD est nulle, pas de z : il faut un montant ≥ 3 × la médiane (`minRatioWithoutDispersion`) | `detectors/UnusualAmountDetector.detect`; `DetectionConfig.UnusualAmount` (nouveau paramètre, défaut 3,0) |
| 2 | `FastForwarding` : ratio sorties/entrées sans borne ni lien entre les deux (1 USDT reçu puis 1 000 payés = « 100 000 % » transférés) | les entrées doivent **expliquer** la sortie (reçu ≥ 50 % du montant envoyé, `minInflowShareOfOutflow`) ; la part transférée est plafonnée au montant reçu | `detectors/FastForwardingDetector.detect`; `DetectionConfig.FastForwarding` (nouveau paramètre, défaut 0,5) |
| 3 | `Structuring` : seuil de 10 000 appliqué par défaut à tous les actifs (ETH, TRX, jetons inconnus) | seuil réservé aux **stablecoins en dollars** (USDT, USDC, USDT0, USDC.e, DAI, BUSD, FDUSD, TUSD, PYUSD, USDD, USDP) ; aucune détection pour les autres actifs | `detectors/StructuringDetector.detect`; `DetectionConfig.Structuring` (`defaultThreshold` nul), `DetectionConfig.USD_STABLECOINS`, `usdStablecoinThresholds` |
| 4 | `CircularFlow` : un même retour de fonds qui ferme plusieurs chemins produisait jusqu'à 5 signaux | **un signal par flux de retour**, avec tous les chemins en preuves (5 listés + « N de plus »), le montant total envoyé et le nombre d'intermédiaires | `detectors/CircularFlowDetector.detect` (clé de déduplication `return|<événement de retour>`) |

Chemins : `oci-intelligence/src/main/java/io/openchain/intelligence/detection/…`. Différence : 5 fichiers de
production, 2 fichiers de tests (177 lignes ajoutées, 36 retirées).

## Métriques avant / après (12 scénarios synthétiques)

| | Avant | Après |
|---|---:|---:|
| Vrais positifs (détecteurs existants) | 14 | 14 |
| Faux positifs | 6 | **1** |
| Faux négatifs (détecteurs existants) | 0 | 0 |
| Comportements sans détecteur (consolidation, peel chain, exposition en aval, financement commun) | 8 | 8 |
| Précision | 0,70 | **0,93** |
| Rappel des détecteurs existants | 1,00 | 1,00 |
| Rappel global (y compris capacités manquantes) | 0,64 | 0,64 |
| Scénarios légitimes sans faux positif | 1 / 4 | **4 / 4** |
| Signaux produits sur S01 (blanchiment simulé) | 66 | 59 |

### Par détecteur

| Type de signal | Avant (VP / FP / FN) | Après (VP / FP / FN) | Changement |
|---|---|---|---|
| UNUSUAL_AMOUNT | 0 / 4 / 0 | 0 / 1 / 0 | 3 faux positifs supprimés (commerçant B01, collecteurs CON1 et CON2) ; reste HUB (voir « Régressions et points à arbitrer ») |
| FAST_FORWARDING | 3 / 1 / 0 | 3 / 0 / 0 | faux positif B02 supprimé, vrais positifs S01, S02, S05 conservés |
| STRUCTURING | 2 / 1 / 0 | 2 / 0 / 0 | faux positif B03 (jeton non stablecoin) supprimé ; S01 et S06 conservés |
| CIRCULAR_FLOW | 1 / 0 / 0 | 1 / 0 / 0 | même détection, 5 signaux → 1 sur HUB |
| FLOW_CONCENTRATION, INDIRECT_EXPOSURE, MIXER_EXPOSURE, SANCTIONED_EXPOSURE, RAPID_DRAIN | inchangés | inchangés | non modifiés |

## Régressions et points à arbitrer

- **Aucune détection existante perdue** : ni sur le banc (14 vrais positifs conservés), ni dans les tests unitaires
  (tous les tests de détection d'origine passent sans modification d'attente, sauf le texte de résumé
  d'`UnusualAmount` : « far above its usual amount » devient « far above what it usually receives » ; et
  `constantHistoryUsesFallbackScale`, renforcé : il attend toujours le signal et vérifie en plus la méthode indiquée
  dans la preuve).
- **Perte de rappel évitée en cours de route** : ma première version de la correction 3 ne gardait le seuil que pour
  USDT et USDC, ce qui faisait perdre la structuration en DAI (détectée avant). Corrigé en gardant tous les stablecoins
  en dollars ; test `otherUsdStablecoinsKeepTheThreshold` ajouté.
- **Compromis assumé de la correction 1** : la référence par sens demande 10 transferts **dans le même sens** au lieu
  de 10 au total. Un wallet avec 6 entrées et 6 sorties n'a plus de référence et ne peut plus produire d'
  `UNUSUAL_AMOUNT`. Aucun test ni scénario ne contenait ce cas (donc aucune perte mesurée), mais c'est une réduction
  de couverture possible. Le mélange des sens était statistiquement incohérent (audit § 3.2).
- **Faux positif restant, arbitré depuis (voir Décision R1)** : `UNUSUAL_AMOUNT` LOW sur HUB (S01), pour l'envoi de 20 000 alors que HUB
  envoie habituellement 5 000 (4 × la médiane). L'écart est réel ; l'attente « interdit » du banc a été posée en voyant
  les artefacts de z infini. Je ne l'ai pas modifiée. Deux options : garder l'attente (et rendre le détecteur plus
  strict, par exemple 5 × la médiane), ou décider que ce signal est légitime et changer l'attente. **Décision à
  prendre par toi.**

## Décision R1 : anomalie statistique légitime ou faux positif (2026-11-10, après revue)

Décision : le signal `UNUSUAL_AMOUNT` LOW sur HUB (envoi de 20 000 pour une médiane d'envois de 5 000) est
**conservé** ; le détecteur n'est pas durci.

Règle métier inscrite dans le banc (`BenchScenarios.RULE_ANOMALY`) : *`UNUSUAL_AMOUNT` est une anomalie statistique
(niveau de preuve ANOMALY), pas une qualification d'activité suspecte. Elle est légitime quand le montant est au moins
3 fois la médiane des transferts du même sens, et doit rester LOW au plus. Elle reste un faux positif sous ce ratio
(B01) ou quand la référence mélange les sens (CON1, CON2).*

Mise en œuvre, sans masquer de résultat :

- nouveau type d'attente `tolerate` (`BenchScenario.Mode.TOLERATE`) avec une sévérité maximale et la règle citée ;
  une anomalie tolérée n'est comptée ni comme vrai ni comme faux positif **si** elle reste ANOMALY et dans la sévérité
  plafonnée ; sinon elle redevient un faux positif ;
- S01/HUB passe de `forbid` à `tolerate` (seule attente modifiée, avec cette règle) ; B01, CON1 et CON2 restent
  `forbid` ;
- le rapport du banc liste les anomalies tolérées à part, avec leur poids dans le score.

Vérifications : sévérité **LOW**, niveau **ANOMALY**, conforme à la règle ; contribution au score de HUB **1,5 point**
sur 51 (MEDIUM). Pour mémoire, le score de HUB était de 59 avant l'étape 2 : la baisse vient des signaux circulaires
redondants supprimés et du z infini corrigé.

| Lecture | Faux positifs | Précision |
|---|---:|---:|
| Détection d'activité suspecte (après R1) | 0 | 1,00 |
| Toute alerte statistique comprise (sans R1) | 1 | 0,93 |

La précision de 1,00 résulte du **reclassement** du signal de HUB par la règle R1, pas d'un changement de détection.

## Tests exécutés

| Commande | Résultat |
|---|---|
| `mvn -pl oci-intelligence test` (après chaque correction) | 104, puis 106, puis 107 tests, 0 échec |
| `mvn -pl oci-intelligence test -Dtest=DetectionBenchTest` (après chaque correction) | rapport ci-dessus, aucune erreur de détecteur |
| `mvn -B verify` (projet complet, Docker actif) | **BUILD SUCCESS, 529 tests, 0 échec, 0 ignoré** |

Tests de non-régression ajoutés (`DetectorsTest`) : `constantHistoryNeedsARealRelativeDeviation`,
`dominantAmountDoesNotInflateTheScore`, `baselineIsPerDirection`, `smallReceiptBeforeALargerPaymentIsNotForwarding`,
`forwardedShareIsCappedAtTheReceivedAmount`, `noThresholdForAssetsWithoutOne`, `otherUsdStablecoinsKeepTheThreshold`,
`oneSignalPerReturnListingEveryPath`.

## Limites restantes

- **Banc réduit et synthétique** : 12 scénarios, un seul cas par comportement ; aucune donnée réelle. Une précision
  de 0,93 sur ce banc ne prédit pas la précision en production. Le rappel d'`UNUSUAL_AMOUNT` n'est pas mesuré
  (aucun scénario suspect ne l'attend).
- **Non traité dans cette étape** (défauts connus de l'audit) : `FanOut` sans filtre de jeton ni de montant et doublé
  par le découpage horaire (2 signaux sur HUB) ; ensemble « visité » global d'`ExposureDetector` ; troncature
  silencieuse des budgets de `CircularFlow` et `Exposure` ; `UnusualApproval` sur les routeurs DeFi.
- **Bruit structurel** : sur S01, 27 `FAST_FORWARDING` et 24 `INDIRECT_EXPOSURE` LOW, justes un par un, mais qui
  décrivent un seul schéma ; le regroupement relève des capacités P1 (ensembles de wallets) et de la politique
  d'alerte.
- Capacités absentes inchangées : consolidation, peel chain, exposition en aval, financement commun.
- Ces corrections ne sont **pas déployées** : la production tourne toujours avec l'image de la mesure 3b.

## Verdict

Les quatre défauts mathématiques et logiques identifiés sont corrigés sans perte de détection mesurée : la précision
passe de 0,70 à 0,93 et les quatre scénarios légitimes ne produisent plus de faux positif, avec un rappel inchangé
(1,00) sur les comportements que les détecteurs savent reconnaître. Les détecteurs corrigés sont **plus fiables sur
ce qu'ils mesurent**, mais le banc est encore trop petit pour parler de qualité de détection en conditions réelles,
et le rappel global (0,64) reste limité par l'absence des détecteurs d'ensemble (P1). Prochaine priorité utile pour
la fiabilité : enrichir le banc (cas réels publics, plusieurs variantes par comportement) avant de s'appuyer sur ses
chiffres pour des décisions de production.
