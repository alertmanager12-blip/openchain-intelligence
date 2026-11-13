# Revue de l'ADR-0023 — niveaux de preuve et traçabilité

Revue du 2026-11-12 sur le code de `94ea42c`. L'ADR n'est **pas modifié** et garde le statut « proposed ». **[F]** fait
vérifié ; **[P]** proposition de la revue. La revue précédente (`docs/strategy/ADR_REVIEW_0021-0025.md`) recommandait
« accepter avec amendement : OBSERVED, INFERRED, CONFIRMED seulement, plus un état de couverture ».

## 1. Décision proposée par l'ADR

L'ADR introduit dans `oci-core` un type `Assertion` avec :
- un sujet, un prédicat, une valeur ;
- un niveau ∈ {OBSERVED, INFERRED, PREDICTED, CONFIRMED, SIMULATED} ;
- une confiance ;
- des références de preuve (hashes, blocs, étiquettes), des sources, la version du moteur ;
- des dates de création et d'expiration.

Les signaux, les scores, les relations et les résultats d'investigation sont exposés comme des assertions ou portent ce
niveau. L'`EvidenceLevel` existant s'y projette : ANOMALY et RISK_SIGNAL deviennent INFERRED, CONFIRMED_BY_SOURCE devient
CONFIRMED.

## 2. Problème résolu

- **Ce qui existe [F].** `EvidenceLevel` (ANOMALY, RISK_SIGNAL, CONFIRMED_BY_SOURCE) qualifie les signaux, les étiquettes
  et les règles. Il ne distingue pas un **fait observé** (ce flux a eu lieu) d'une **inférence** (ce chemin, cette
  attribution, ce motif). Il n'existe pas de vocabulaire commun à l'API, à la console, aux rapports et au futur MCP.
- **Ce que demande Fund Flow Trace.** Distinguer faits observés, signaux détectés, hypothèses d'investigation et
  conclusions étayées. C'est exactement ce que fait cet ADR.

## 3. Alternatives

| Option | Description | Verdict |
|---|---|---|
| A. ADR tel quel | 5 niveaux, type transverse | PREDICTED et SIMULATED n'ont **aucun producteur** aujourd'hui [F] : vocabulaire mort |
| B. Garder `EvidenceLevel` et ajouter OBSERVED | changement minimal | ne couvre pas les attributions ni les motifs du traçage ; mélange preuve et signal |
| C. ADR amendé [P] | OBSERVED, INFERRED, CONFIRMED maintenant ; PREDICTED et SIMULATED ajoutés le jour où un producteur existe ; plus un **état de couverture** distinct du niveau | **recommandé** |

## 4. Avantages et inconvénients (option C)

- **Avantages.**
  - Un seul vocabulaire.
  - Une association probable ne peut plus être affichée comme un fait.
  - Les conclusions « étayées » sont réservées à CONFIRMED (source nommée) ou à une validation humaine (ADR-0022).
  - L'option est additive : le JSON v1 n'est pas cassé.
- **Inconvénients.**
  - Des champs sont ajoutés sur plusieurs DTO.
  - Il faut éviter que deux notions coexistent durablement (`evidenceLevel` et `level`). La correspondance est fixée, et
    `evidenceLevel` est conservé en lecture.

## 5. Conséquences sur les modules existants

- **`oci-core`** : types `Assertion`, `EvidenceRef` (réseau, hash, bloc, index de log, `eventId`, provenance), `Level`.
- **`oci-intelligence`** :
  - les signaux portent déjà des preuves (`Signal.Evidence`) [F] ; il faut une correspondance vers `EvidenceRef` ;
  - le moteur de traçage (P0-D) produit des `Finding` typés.
- **`oci-app`** : DTO additifs (`level`, `evidence[]`) ; les textes générés citent le niveau.
- **Interface** : badge de niveau partout (graphe, preuves, exposition) ; le texte « Signals indicate risk indicators or
  anomalies, not wrongdoing » [F] est conservé.

## 6. Mémoire et performances

- **Risque principal : mémoire.** Si chaque signal en mémoire porte une `Assertion` de plus, le coût augmente. La mesure
  en cours chiffre déjà `Signal$Evidence` (1,34 M d'instances, 43 Mo, histogramme de référence).
- **Proposition.** Construire l'assertion **à la lecture** (projection des signaux existants), sans nouvel objet par
  signal stocké. Le traçage la stocke en base, pas dans la mémoire de travail.
- **Performances.** Négligeables : simple projection.

## 7. Risques de sécurité

- **Risque majeur : la sur-affirmation.** Un INFERRED affiché comme CONFIRMED peut accuser un tiers à tort (risque
  juridique et réputationnel). Il faut une règle testée : CONFIRMED uniquement si la source de l'étiquette est elle-même
  CONFIRMED_BY_SOURCE [F, `ExposureDetector`] ou après une validation humaine tracée.
- **Fausse preuve.** Une source d'étiquettes compromise rendrait CONFIRMED une fausse assertion. Il faut tracer la
  source, sa version et sa date dans l'assertion.
- **Données de chaîne non fiables.** Noms et symboles ne doivent jamais être interprétés (ADR-0025).

## 8. Compatibilité avec les objectifs

Elle est forte : c'est la colonne vertébrale de l'explicabilité. C'est aussi un prérequis de l'ADR-0022 (rapports issus
d'assertions) et de l'ADR-0026 (verdicts).

## 9. Modifications nécessaires avant acceptation [P]

1. Réduire à OBSERVED, INFERRED, CONFIRMED. Documenter PREDICTED et SIMULATED comme « réservés, non implémentés ».
2. Ajouter que **la couverture est un axe séparé du niveau** : une assertion OBSERVED sur des données partielles reste
   OBSERVED, mais la conclusion « aucun » exige une couverture COMPLETE (P0-B, ADR-0026 § 5).
3. Préciser la règle de passage à CONFIRMED : source nommée et fiable, ou validation humaine identifiée et datée.
4. Préciser le contenu d'`EvidenceRef` : bloc et index de log quand ils sont connus, provenance, et marque « non
   confirmé » (blocs pas encore finalisés).
5. Imposer la projection à la lecture : aucune nouvelle structure par signal en mémoire.

**Recommandation : accepter avec ces amendements. Cet ADR n'a pas de dépendance amont et peut être le premier mis en
œuvre, en P1, dès la fin de la mesure mémoire.**
