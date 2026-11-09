# Modèle économique

> Hypothèses à valider par des entretiens clients. **Aucun prix n'est fixé ici** : seuls les repères publics des
> concurrents (consultés le 2026-11-09) sont cités.

## 1. Repères de marché (publics)

| Offre | Prix public |
|---|---|
| Arkham | offre gratuite ; Enterprise **1 500 $/mois** |
| Breadcrumbs | paliers publics ; TRON + score de risque au plan **499 $/mois** ; données on-premise **380 000 $/an + 30 000 $** |
| TRM Beacon Network | niveau de base gratuit (VASP vérifiés) |
| Elliptic | liste de blocage et MCP publics gratuits (périmètre limité) ; reste sur devis |
| Chainalysis, TRM, Elliptic, Blockaid | sur devis (« contact sales ») |
| GraphSense | gratuit (MIT) ; Iknaio sur devis |

## 2. Offres proposées

| Offre | Pour qui | Contenu | Ce qui la justifie |
|---|---|---|---|
| **Community Edition** (gratuite, Apache-2.0) | développeurs, PSP techniques, chercheurs | tout le moteur (voir [OPEN_SOURCE_STRATEGY.md](OPEN_SOURCE_STRATEGY.md) § 3) | adoption, confiance, contributions |
| **Cloud / Pro** | PSP et fintechs sans équipe d'exploitation | instance managée, indexation historique (nœuds d'archive), sauvegardes, mises à jour, flux d'intelligence maintenus | coût réel d'infrastructure (archive, stockage) et d'exploitation |
| **Enterprise** | exchanges, banques, institutions | déploiement privé, HA multi-instance, SSO/SCIM, RBAC avancé, audit et rétention, intégrations, support et SLA | exigences de conformité et de disponibilité |
| **Intelligence API** | intégrateurs | screening, transaction, exposition, investigation, threat-intel par API hébergée | usage à l'appel, sans hébergement |

## 3. Ce qui ne sera jamais payant

Explicabilité des scores, niveaux de preuve, provenance, vérification des signatures, correctifs de sécurité,
export de ses propres données, règles et rapports. Les rendre payants détruirait la proposition « vérifiable ».

## 4. Unités de valeur possibles (à tester)

- par réseau surveillé et volume d'adresses suivies (Cloud) ;
- par appel de screening (Intelligence API) ;
- forfait annuel par instance + support (Enterprise) ;
- abonnement aux flux d'intelligence curés (indépendant de l'hébergement).

## 5. Risques du modèle

| Risque | Mitigation |
|---|---|
| Attribution plus faible que les leaders → difficile de vendre un remplacement | vendre d'abord un **complément** (moteur indépendant, règles en code, rails stablecoin) |
| Revente du moteur par un tiers (Apache-2.0) | marque déposée, exécution, flux d'intelligence et support comme valeur |
| Coût des nœuds d'archive et du stockage | refacturé dans le Cloud ; Community reste sur nœud non-archive avec limites affichées |
| Exigence réglementaire d'un « fournisseur reconnu » | partenariats, audits de sécurité publiés, documentation de conformité |

## 6. Validation avant tout investissement commercial

1. 10 à 15 entretiens (PSP, exchanges régionaux, émetteurs de cartes crypto) : volume de flux, outil actuel, budget,
   exigence d'auto-hébergement, ce qui manque.
2. 2 à 3 pilotes sur leurs flux réels, avec mesure du bruit et des alertes utiles.
3. Décision Cloud vs Enterprise en premier, selon les résultats.
