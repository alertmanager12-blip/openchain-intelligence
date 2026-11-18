# Précision réelle des règles : grille d'étiquetage

Le banc existant (`docs/bench/DETECTION_BENCH_STEP_2.md`) est **synthétique** (12 scénarios). Ici, on mesure la précision
sur des **alertes de production**, jugées par une personne.

## Contenu

- `grid-2026-11-18.csv` : 63 alertes tirées au hasard dans la base de production le 2026-11-18, réparties par règle
  (wallet-draining 25, large-usdt-transfer 20, fund-splitting 12, issuer-freeze-counterparty 3,
  sanctioned-exposure-signal 3). Tirage reproductible (`setseed`).
- `sample_alerts.py` : refait le tirage (`python3 sample_alerts.py --sql` donne la requête).
- `precision.py` : calcule la précision par règle et son intervalle à 95 % (Wilson).

## Comment juger une alerte

Remplir la colonne `verdict (TP/FP/?)`, et `note` en quelques mots (la note sert à régler la règle ensuite).

- **TP (vrai positif)** : un analyste conformité aurait **voulu voir** cette alerte, au regard de ce que la règle
  affirme. Ce n'est pas une preuve de délit.
- **FP (faux positif)** : activité de routine que la règle n'aurait pas dû signaler.
- **? (indécidable)** : impossible de trancher en environ 3 minutes avec l'explorateur public. Jamais compté comme TP.

Repères par règle :

| Règle | TP typique | FP typique |
|---|---|---|
| wallet-draining | vidage vers une adresse neuve puis dispersion ; pattern de compromission | balayage vers un dépôt ou un portefeuille chaud d'exchange ; vers une autre adresse du même propriétaire ; swap DEX, pont |
| large-usdt-transfer | gros montant avec contrepartie neuve ou inhabituelle pour ce portefeuille | trésorerie d'exchange, teneur de marché, mouvements internes connus |
| fund-splitting | éclatement vers de nombreuses adresses neuves (empilement) | paiements de masse, retraits d'exchange, paie |
| issuer-freeze-counterparty | gel de l'émetteur vérifiable sur l'explorateur, transfert réel avec l'adresse gelée | gel non retrouvé, ou relation sans transfert de valeur |
| sanctioned-exposure-signal | adresse effectivement sur la liste citée, transfert réel | adresse retirée de la liste, ou correspondance erronée |

## Calcul

```
python3 precision.py grid-2026-11-18.csv
```

Limites : 3 alertes pour les deux règles de listes, c'est trop peu pour une précision ; la précision globale n'est pas
pondérée par le volume de chaque règle ; un échantillon jugé par une seule personne.
