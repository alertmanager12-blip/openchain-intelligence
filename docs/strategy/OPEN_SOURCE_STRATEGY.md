# Stratégie Open Source

> **Aucun changement de licence n'est proposé sans validation.** Le dépôt est aujourd'hui sous **Apache-2.0**
> (`LICENSE`, politique des dépendances : ADR-0012).

## 1. Pourquoi l'open source est au cœur de la proposition

Ce n'est pas un canal marketing : c'est la réponse directe au besoin du segment visé.

- **Souveraineté des données** : un PSP surveille ses flux sans les envoyer à un tiers.
- **Auditabilité** : un régulateur ou un auditeur peut lire la règle et le code qui ont produit une alerte.
- **Indépendance fournisseur** : l'incident Chainalysis de novembre 2026 montre le risque d'un point unique de
  dépendance pour une fonction de conformité.

## 2. Comparaison des licences

| Critère | Apache-2.0 (actuelle) | MIT | AGPLv3 |
|---|---|---|---|
| Adoption par les entreprises (PSP, banques) | très bonne ; brevets couverts explicitement | très bonne ; pas de clause brevet | **freinée** : beaucoup de politiques internes l'interdisent |
| Protection contre un cloud qui revend le produit sans contribuer | faible | faible | forte (obligation de publier les modifications servies en réseau) |
| Contributions externes | bonne | bonne | moindre |
| Compatibilité avec un modèle open core | bonne | bonne | bonne mais complexe (double licence nécessaire, donc CLA) |
| Cohérence avec l'existant | aucune migration | migration simple, perte de la clause brevet | migration lourde, accord des contributeurs requis |

**Recommandation : conserver Apache-2.0.** Le segment visé (fintechs, PSP) est sensible aux politiques de licence ;
l'AGPL bloquerait l'adoption là où nous voulons gagner. Le risque « un cloud revend le moteur » est réel mais
secondaire au stade actuel ; il se traite par la marque, la vitesse d'exécution et les services, pas par la licence.
*Décision à valider ; aucune modification ne sera faite sans accord.*

## 3. Frontière open core

**Toujours ouvert (Community Edition)** — sans exception pour la sécurité et la vérification :

- moteur d'ingestion et adaptateurs TRON/EVM ;
- détecteurs, scoring explicable, niveaux épistémiques ;
- moteur de règles, pack de règles communautaire, dry-run ;
- graphe d'investigation, investigations reproductibles, rapports ;
- API, webhooks signés, MCP, SDK ;
- import des sources ouvertes (OFAC, gels d'émetteur, TagPacks) ;
- auto-hébergement complet, mono-instance.

**Candidats à une offre commerciale** (valeur d'exploitation, pas de verrouillage de sécurité) :

- hébergement managé et indexation historique complète (nœuds d'archive : coût réel d'infrastructure) ;
- haute disponibilité multi-instance, SSO entreprise (SAML/SCIM), multi-tenant, audit avancé, rétention longue ;
- flux d'intelligence curés et maintenus (labels vérifiés, mises à jour continues) ;
- support, SLA, intégrations spécifiques.

## 4. Gouvernance et contribution

| Sujet | Proposition |
|---|---|
| Contributions | **DCO** (`Signed-off-by`) plutôt que CLA tant qu'aucune double licence n'est envisagée |
| Règles communautaires | dépôt `rules/` avec tests embarqués obligatoires (déjà appliqué par `RulesRepositoryTest`) |
| Labels communautaires | format TagPack, provenance obligatoire, revue et révocation (phase 6) |
| Marque | déposer « ChainSentinel » avant toute offre commerciale ; politique d'usage de la marque |
| Sécurité | `SECURITY.md` avec divulgation responsable et délai de correction |
| Versions | SemVer ; API v1 et schéma de règles `openchain.io/rule/v1` stables à partir de 1.0.0 (déjà annoncé dans le CHANGELOG) |
| Feuille de route publique | issues GitHub étiquetées par phase |

## 5. Écosystème

- **Interopérer plutôt que concurrencer GraphSense** : import/export TagPacks ; possibilité future d'utiliser
  GraphSense comme source d'historique/attribution.
- **Publier les détecteurs avec leurs mesures** (taux de faux positifs sur données réelles) : c'est ce qui peut
  différencier des détecteurs propriétaires.
- **Conditions d'usage des sources** : vérifier la licence de chaque liste (OFAC : domaine public américain ; listes de
  fournisseurs : conditions propres) avant de la redistribuer dans le dépôt.
