# ChainSentinel : renseignement et investigation

Date : 2026-11-10. Complète `docs/CHAINSENTINEL_CORE_MISSION_AUDIT.md` (état détaillé des détecteurs, des données et
des règles, avec références) ; ce document l'examine sous l'angle d'un enquêteur et rend compte d'une investigation
simulée. Base : branche `wip/adr-0024-step-3a` (commit `299db4f`).

## 1. Vision

Passer de la transaction isolée à l'investigation : partir d'un signal, collecter, corréler, reconstituer une
chronologie, analyser les schémas, chercher les explications alternatives, évaluer la confiance et les limites,
documenter un dossier reproductible, puis surveiller. Chaque résultat distingue **fait** (lu sur la chaîne),
**indice**, **hypothèse**, **corrélation**, **attribution** (source fiable nommée) et **conclusion** (avec ses
limites). Un score élevé n'est pas une preuve ; une relation technique n'est pas une identité.

Correspondance avec le modèle existant : `EvidenceLevel` (ANOMALY, RISK_SIGNAL, CONFIRMED_BY_SOURCE) couvre en partie
indice et attribution ; l'ADR-0023 (proposée) ajoute OBSERVED / INFERRED / CONFIRMED ; corrélation, hypothèse et
conclusion n'ont aujourd'hui **aucune représentation** dans le code.

## 2. Les dix capacités : état réel

| Capacité | État | Ce qui existe (références) | Ce qui manque |
|---|---|---|---|
| A. Transaction intelligence | partielle | analyse de transaction : transferts, signaux, confirmations (`oci-app/.../analysis/InvestigationService.java:325`) | contexte avant/après la transaction, historique des parties au-delà de la mémoire, transactions internes |
| B. Wallet profiling | partielle | profil incrémental : volumes par jeton, contreparties, activité, plus long silence (`intelligence/profile/WalletProfileAccumulator.java`) ; borné (étape 3b) | horaires d'activité, changements de comportement, montants habituels ; **aucun détecteur ne lit le profil** (`DetectionContext.profile` inutilisé) ; profil évincé puis recréé « neuf » |
| C. Network investigation | faible | liste des 20 principales contreparties (`InvestigationService.java:217-243`), graphe ≤ 3 sauts (`:349-399`) | contreparties communes, flux convergents, regroupements, relations temporelles : **inexistants** |
| D. Fund flow tracing | partielle | graphe bidirectionnel ≤ 3 sauts / 500 nœuds ; exposition indirecte en **remontée** pondérée par les parts (`:266-311`) | traçage en **aval**, chemins orientés dans le temps côté serveur, consolidation, limites inter-chaînes ; résultats non sauvegardés |
| E. Suspicious pattern detection | partielle | structuring, rapid dispersal (fan-out), circular, fast forwarding, rapid drain, mixer exposure, dusting, unusual amount, dormancy, approvals (`intelligence/detection/detectors/`) | **peel chains, smurfing multi-wallets, layering, bridge hopping, wallet farming, bursts coordonnés, changement soudain de comportement** ; défauts listés dans l'audit § 3.2 |
| F. Entity & attribution | faible | modèle de label avec source, fiabilité, date, niveau de preuve (`intelligence/label/AddressLabel.java`) ; catégories EXCHANGE, BRIDGE, DEFI_PROTOCOL… | **aucune source d'attribution réelle** hors gels d'émetteur ; pas d'expiration, de révocation ni de persistance du catalogue |
| G. Threat intelligence | faible | gels Tether/Circle on-chain (`oci-blockchain/.../token/StablecoinFreezeSources.java`) ; import CSV/JSON au démarrage | listes de sanctions, IOC, incidents documentés, signalements validés (le circuit communautaire n'aboutit pas : `LabelReportStore.java:9-13`) |
| H. Case management | absente | alertes avec historique, commentaires, statut (V1) | dossier, objet, chronologie, hypothèses, conclusion, traçabilité de l'analyste ; ADR-0022 « proposée » |
| I. Predictive intelligence | absente | — | tout ; à n'envisager qu'avec un banc d'évaluation historique |
| J. Explainable intelligence | partielle et bonne | chaque signal porte ses preuves (transactions, chemins, labels) et un résumé lisible ; chaque point du score est expliqué (`intelligence/risk/RiskScoringEngine.java`) | hypothèses alternatives, éléments contradictoires, limites de couverture dans chaque résultat |

## 3. Investigation simulée

### 3.1 Scénario (données synthétiques)

Sonde : `oci-app/src/test/java/io/openchain/app/probe/InvestigationScenarioProbe.java` (non commitée ; exécution :
`mvn -pl oci-app test -Dtest=InvestigationScenarioProbe`). Adresses et labels fictifs, préfixe `0x5e`, source
`synthetic-scenario`. Configuration de détection et de scoring par défaut (celle de la production). 55 transferts USDT
en 66 minutes :

1. `MIX0` (étiqueté MIXER, fiabilité 0,9) envoie 120 000 à `SRC` ; `SRC` envoie 100 000 à `HUB`.
2. `HUB` disperse 5 000 vers 20 wallets `W1`…`W20` en 20 secondes.
3. `W1`…`W10` consolident vers `CON1` ; `CON1` envoie 20 000 à `EXCH` (exchange étiqueté) et 29 900 à `MIX1` (mixer).
4. `W11`…`W20` consolident vers `CON2` ; `CON2` renvoie 49 000 à `HUB` (circulation).
5. `HUB` fait trois sorties de 9 500, 9 600 et 9 700 (structuration sous 10 000).
6. Peel chain : `HUB` → `P1` 20 000 ; chaque `Pi` garde 2 000 pour `EXCH` et passe le reste à `Pi+1` jusqu'à `P4`.

### 3.2 Résultats bruts (sortie `PROBE|`)

- 66 signaux, aucune erreur de détecteur.
- `HUB` : INDIRECT_EXPOSURE (mixer, 2 sauts, 0,45), FAST_FORWARDING ×2, FLOW_CONCENTRATION ×2, UNUSUAL_AMOUNT,
  CIRCULAR_FLOW ×5, STRUCTURING. Score **59, MEDIUM**.
- `SRC` : MIXER_EXPOSURE HIGH (0,90), score 40. `CON1` : MIXER_EXPOSURE HIGH, FAST_FORWARDING, UNUSUAL_AMOUNT ×2,
  score 53. `CON2` : FAST_FORWARDING, UNUSUAL_AMOUNT, score 20.
- `W1`…`W20` : INDIRECT_EXPOSURE (3 sauts, 0,23) et FAST_FORWARDING chacun ; scores 16 à 20, LOW.
- `P1`…`P3` : FAST_FORWARDING ; `P4` : rien, score 0.
- Graphe depuis `HUB` (3 sauts) : 33 nœuds, 54 arêtes, toutes les adresses sauf `P4` (4ᵉ saut).
- Exposition API : `HUB` indirecte au mixer (2 sauts, part 0,671) ; `CON1` directe au mixer (part 0,300) et à
  l'exchange (0,200) ; `W1` indirecte au mixer (3 sauts) ; `P4` aucune.

### 3.3 Les sept questions

| Question | Résultat | Constat |
|---|---|---|
| 1. Détecter le comportement | **en partie** | dispersion, circulation, structuration, passage rapide et exposition au mixer amont reconnus. **Non reconnus** : la peel chain (aucun détecteur), la consolidation vers `CON1`/`CON2`, le « reçu puis vidé » de `HUB` (`RapidDrain` ne voit pas de solde avant la fenêtre). `UNUSUAL_AMOUNT` sur `CON1` est un **faux motif** : historique constant de 4 990 ⇒ z infini. |
| 2. Identifier les wallets impliqués | **en partie** | tous apparaissent dans le graphe de `HUB` (sauf `P4`), mais aucun objet ne les réunit comme un même ensemble suspect. |
| 3. Reconstituer les flux | **en partie** | graphe complet à 3 sauts ; **pas de chronologie** ; **pas de traçage en aval** : `HUB` n'est pas relié au mixer de destination `MIX1` ni à l'exchange de sortie. |
| 4. Identifier les relations | **non** | rien ne dit que `W1`…`W20` ont été financés par la même source dans la même minute puis consolidés vers deux collecteurs ; aucune hypothèse de contrôle commun, même présentée comme INFERRED. |
| 5. Déclencher les bons détecteurs | **oui pour les signaux, non pour les alertes** | avec les règles livrées, **aucune alerte** : `MIXER_EXPOSURE` n'est consommé par aucune règle, `INDIRECT_EXPOSURE` de `HUB` (0,45) est sous le seuil 0,5 de `sanctioned-exposure-signal`, `fund-splitting` exige plus de 30 destinataires en 3 minutes (20 ici). Bruit : 5 signaux de circulation pour un même schéma, 2 de concentration par découpage horaire. |
| 6. Expliquer les résultats | **oui au niveau du signal** | chaque signal cite ses transactions et son schéma ; le score détaille ses contributions. Pas d'explication d'ensemble, ni d'hypothèse alternative (par exemple une distribution de paie suivie d'une collecte). |
| 7. Produire un dossier | **non** | aucun dossier, aucune persistance : le résultat dépend de la mémoire du moment et disparaît au redémarrage (ADR-0022 non implémentée). |

Score de `HUB` à 59 (MEDIUM) malgré 12 signaux convergents : prudent, défendable, mais le score additionne des facteurs
**propres au wallet** ; il ne sait pas qu'il est le centre d'un schéma qui implique 30 adresses.

## 4. Limites d'architecture pour l'investigation

1. **Mémoire analytique** : environ 2 h de flux pour les wallets non surveillés (plafonds de l'étape 3b) ; une
   investigation sur un événement de la veille ne voit plus rien sans recharger l'historique, qui ne couvre ni l'ETH
   natif ni les transactions internes. La liste de surveillance (seule à garder tout l'historique) est vide.
2. **Rien n'est persisté pour l'investigation** : graphe et exposition recalculés à chaque appel sur un état qui
   change ; impossible de rejouer, de comparer ou d'auditer.
3. **Données manquantes** : transactions internes (sorties de mixers, contrats), TRC-10, solde historique TRON.
4. **Pas de notion d'ensemble** : tous les objets sont centrés sur une adresse (profil, score, signal) ; un schéma
   multi-wallets n'a pas de représentation.
5. **Une seule chaîne à la fois** : l'adresse est liée à un réseau ; aucun lien inter-chaînes.

Impact de la stratégie « périmètre client » (ADR-0026) : elle **rend possible** l'investigation approfondie
(historique persistant, fenêtres complètes, rejouabilité) pour les wallets surveillés et leurs contreparties ; elle
**ne la crée pas**. Hors périmètre, une investigation reste limitée à la mémoire bornée et à l'historique à la
demande ; elle doit le dire dans son résultat. L'expansion contrôlée (budgets par wallet surveillé) doit laisser une
marge pour l'expansion **à la demande** d'une investigation (rechargement d'historique des adresses du dossier), sans
quoi le périmètre client deviendrait un plafond d'investigation.

## 5. Scénarios d'investigation prioritaires

Choisis pour le positionnement (PSP, rails stablecoin) et pour ce qui est vérifiable avec les données disponibles :

| # | Scénario | Pourquoi | Capacités requises |
|---|---|---|---|
| S1 | Un paiement entrant chez un client provient-il, à ≤ 3 sauts, d'une adresse gelée, sanctionnée ou d'un mixer ? | décision d'acceptation d'un PSP | G (sources réelles), D (remontée), J, verdict avec couverture |
| S2 | Dispersion → consolidation (le scénario de la sonde) autour d'un wallet surveillé | schéma de blanchiment le plus courant sur USDT | E (consolidation, peel chain), C (ensemble de wallets), H |
| S3 | Après un gel Tether/Circle, quels wallets du client ont reçu des fonds de l'adresse gelée, et quand ? | renseignement fiable déjà disponible (gels on-chain) | D (aval et amont), historique persistant |
| S4 | Structuration sous les seuils sur les adresses de dépôt d'un client | obligation de vigilance | E (structuring corrigé, seuils par actif), B |
| S5 | Sortie de fonds d'un client vers un mixer ou un pont | exposition en aval | D (aval), transactions internes |

## 6. Évolutions nécessaires (ordre de la consigne, cohérentes avec l'audit de mission)

**P0**
1. Banc d'évaluation : transformer la sonde en bibliothèque de scénarios (S1 à S5, plus les comportements de la
   section 3 de la consigne) avec résultats attendus ; rejouer aussi des cas réels publics. Précision et rappel par
   détecteur.
2. Corriger les défauts mis en évidence par la sonde et l'audit : z infini d'`UnusualAmount`, ratio de
   `FastForwarding`, cas « reçu puis vidé » de `RapidDrain`, signaux redondants de `CircularFlow` et `FanOut`.
3. Faire aboutir les signaux en alertes : exposition directe MIXER/SCAM/FLAGGED ; seuils explicites.
4. Historique analytique : ADR-0026 ; retrait des flux de blocs orphelins.

**P1**
5. Traçage en aval et chemins orientés dans le temps, pondérés par montant.
6. Détecteurs d'ensemble : consolidation (fan-in), dispersion → consolidation, peel chain, financement commun
   simultané ; chaque relation produite comme **corrélation ou hypothèse** (INFERRED), avec ses alternatives.
7. Sources de renseignement réelles avec provenance, date, expiration ; nouvelle détection quand un label change.

**P2**
8. Dossier d'investigation (ADR-0022) : objet, adresses, transactions, chronologie, hypothèses, preuves, relations,
   confiance, conclusion, historique de l'analyste ; généré uniquement à partir d'éléments stockés.
9. Verdicts et `POST /screening` (ADR-0026 § 5).

**P3** : prédiction (seulement avec le banc historique), MCP, intégrations.

## 7. Feuille de route

| Étape | Livrable | Preuve attendue |
|---|---|---|
| 1 | Banc d'évaluation + 5 scénarios | rapport précision / rappel par détecteur, versionné |
| 2 | Corrections P0.2 et P0.3 | scénario S2 : alerte sur le mixer, plus de z infini, signaux non redondants |
| 3 | ADR-0026 validée et implémentée ; orphelins | redémarrage : historique des wallets surveillés intact ; test de réorganisation |
| 4 | Traçage en aval, détecteurs d'ensemble | S2 et S5 : `HUB` relié à `MIX1` et `EXCH`, ensemble `W1`…`W20` produit comme hypothèse |
| 5 | Sources réelles | S1 et S3 sur données réelles, date de mise à jour affichée |
| 6 | Dossiers et screening | dossier du scénario S2 rejoué à l'identique |

## 8. Critères mesurables

| Critère | Aujourd'hui (sonde et audit) | Cible |
|---|---|---|
| Schémas du scénario S2 reconnus | 4 sur 7 (dispersion, circulation, structuration, exposition amont) | 7 sur 7 |
| Alertes produites sur S2 avec les règles livrées | 0 | ≥ 1 alerte HIGH sur `HUB` et `CON1`, regroupées |
| Signaux redondants sur S2 | 5 circulations + 2 concentrations pour 2 schémas | 1 signal par schéma |
| Faux motifs sur S2 | 4 `UNUSUAL_AMOUNT` (historiques dominés par un même montant : MAD nul, z infini) | 0 |
| Wallets de S2 reliés en un ensemble | 0 | 1 ensemble (hypothèse INFERRED avec alternatives) |
| Traçage en aval depuis `HUB` | non | `MIX1` et `EXCH` atteints, avec parts |
| Dossier rejouable | non | rejeu identique (mêmes données, même version) |
| Détecteurs avec précision / rappel publiés | 0 | 10 |

## 9. Conclusion

ChainSentinel sait déjà **voir** beaucoup de choses : sur un scénario de blanchiment, il produit des signaux justes et
expliqués pour la plupart des schémas. Il ne sait pas encore **enquêter** : il ne relie pas les wallets entre eux, ne
suit pas les fonds vers l'aval, ne transforme pas ses signaux en alertes pour les schémas les plus graves, ne garde pas
la mémoire nécessaire hors périmètre client et ne produit pas de dossier. L'écart est concentré dans le passage du
signal individuel à l'ensemble corrélé et documenté ; c'est là que doit porter l'effort, après avoir rendu la détection
mesurable.

Rien n'a été développé dans le produit : seule la sonde (test non commité) a été écrite pour mesurer.
