# Critères de validation

## 1. Définition de « terminé » (toute livraison)

1. `mvn -B clean verify` vert, **0 test sauté** (Docker démarré, Testcontainers exécutés).
2. Frontend : `tsc` strict app et specs, `npm test`, `npm run build` sans mock ni script en ligne.
3. Test de fumée Docker isolé vert ; aucune écriture dans une base de production.
4. Aucune fonctionnalité visible qui renvoie une erreur ou une donnée codée en dur.
5. Aucun résultat sans provenance (source, version du moteur, niveau de preuve).
6. Aucune prédiction présentée comme un fait ; aucune association présentée comme une identité.
7. Documentation, CHANGELOG et ADR à jour ; écarts doc/code nuls sur le périmètre touché.
8. Aucun secret dans le code ou les logs ; dépendances nouvelles justifiées et de licence autorisée.
9. Pas de commit, push ni changement de licence sans accord explicite.

## 2. Critères par phase

### Phase 0
- 0 dead-letter « unknown connector » sur 48 h d'exploitation réelle.
- Alertes ouvertes par jour divisées par au moins 10 sur l'instance réelle, **sans** désactiver silencieusement de règle ;
  rapport avant/après publié.
- Les 16 défauts D1-D16 de [PRODUCT_GAP_ANALYSIS.md](PRODUCT_GAP_ANALYSIS.md) fermés ou documentés comme limites.
- `/actuator/prometheus` exposé et scruté ; tableau de bord indexation/alertes/livraisons.

### Phase 1
- Redémarrage de l'application : 0 événement perdu, 0 bloc ré-indexé au-delà de la fenêtre de réorg (test automatisé).
- Liste OFAC chargée, datée, rafraîchie ; chaque label affiche source, fiabilité, date, preuve.
- `POST /screening` : p95 < 300 ms sur 10 000 requêtes locales ; chaque verdict liste ses raisons et ses limites.
- Précision mesurée par règle sur le jeu de vérité terrain, publiée.

### Phase 2
- Traçage > 3 sauts borné en nœuds et en temps, `truncated` explicite.
- 100 % des relations entre portefeuilles marquées `INFERRED` avec confiance et preuves.

### Phase 3
- Rejouer une investigation sur les mêmes données donne un résultat identique (test d'égalité).
- Une investigation interrompue reprend après redémarrage.
- Chaque phrase du rapport renvoie à au moins une assertion identifiée.

### Phase 4
- Chaque prédiction a une confiance, des signaux, des limites et une expiration ; taux de réalisation calculé.
- Les résultats simulés ne sont jamais stockés ni affichés comme des événements observés (test).

### Phase 5
- Chaque outil MCP refuse l'appel sans le scope de l'endpoint équivalent (test par outil).
- SDK générés compilés et testés contre l'API en CI.

### Phase 6
- Aucun label communautaire publié sans décision de modération journalisée.
- Révocation d'un label : retiré des scores en moins d'un cycle de recalcul.

## 3. Indicateurs produit à suivre

| Indicateur | Pourquoi |
|---|---|
| Alertes ouvertes/jour et % classées faux positif, par règle | crédibilité |
| Délai bloc → alerte (p50/p95) | promesse « temps réel » |
| Retard d'indexation par chaîne | fiabilité |
| Taux de dead-letters | aucune perte silencieuse |
| Taux de réalisation des prédictions | honnêteté du prédictif |
| Investigations rejouées identiques (%) | reproductibilité |
