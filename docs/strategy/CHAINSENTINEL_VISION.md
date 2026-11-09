# ChainSentinel — Vision et positionnement

> Statut : **proposition à valider** (2026-11-09). Fondé sur l'audit du code à `44c0990`, l'exploitation réelle de
> l'instance de production (Ethereum mainnet + TRON) et l'étude de marché sourcée
> ([market-research-sources.md](market-research-sources.md)).

## 1. Positionnement

**ChainSentinel — The Open Intelligence Engine for Blockchain.**
*Understand. Predict. Investigate. Act.*

Une plateforme Open Source (Apache-2.0), **auto-hébergeable**, qui surveille en temps réel les flux de stablecoins
(USDT/USDC sur TRON et EVM), explique chaque risque qu'elle signale, conserve la preuve de chaque conclusion et se
pilote entièrement par API, par règles déclaratives et par agents.

### Ce que nous ne sommes pas

- Pas un clone de Chainalysis, TRM ou Elliptic : leur actif principal est une base d'attribution propriétaire
  (« 1B+ labeled », « 2.2B+ attributed », « hundreds of clustering heuristics ») que nous n'avons pas et ne pouvons
  pas reconstituer à court terme.
- Pas un outil de forensique académique : GraphSense (MIT, auto-hébergeable, TRON + USDT, MCP intégré) occupe déjà ce
  terrain ; il ne fait en revanche ni surveillance temps réel, ni alerting, ni score de risque (docs publiques).

## 2. L'avantage concurrentiel démontrable : une intersection, pas une fonctionnalité

L'étude de marché est sans complaisance : **aucun élément pris isolément n'est unique**.

| Élément | Déjà couvert par |
|---|---|
| Score explicable | Elliptic (facteurs journalisés), Blockaid (montants/catégories/%), Chainalysis (exposition directe/indirecte) |
| Serveur MCP / agents | TRM (juil. 2026), GraphSense (juin), Arkham (oct.), Elliptic (public), Breadcrumbs |
| Open source + auto-hébergé | GraphSense (MIT) |
| Intelligence communautaire | TRM Beacon, Arkham Intel Exchange, Breadcrumbs Network, TagPacks GraphSense |
| Couverture TRON | les sept acteurs |

Ce qui n'existe chez **aucun** acteur étudié, c'est la combinaison :

1. **Open source et auto-hébergé** — aucune des cinq offres SaaS (Chainalysis, TRM, Elliptic, Arkham, Blockaid)
   ne documente d'auto-hébergement ; la seule offre on-premise commerciale trouvée (Breadcrumbs) coûte 380 000 $/an.
2. **Surveillance temps réel et alerting** — absents de l'offre open source existante (GraphSense).
3. **Spécialisation « rails stablecoin »** — TRON-first + EVM, avec les événements d'émetteur (gels USDT/USDC) comme
   signaux de premier rang ; aucun acteur ne se positionne ainsi dans les sources étudiées.
4. **Règles ouvertes, versionnées, testables en CI** — les moteurs concurrents se configurent dans des interfaces
   fermées.
5. **Investigations reproductibles et prouvées** — chaque conclusion rattachée à ses données, ses paramètres et la
   version du moteur ; chaque relation probabiliste présentée comme telle.

Argument d'actualité, vérifié à la source : Chainalysis a déclaré le 2026-10-30 que « names, usernames, password
hashes and live tokens for all Chainalysis users were exposed », puis a coupé KYT (interface et API) du 2026-11-04 au
2026-11-07. Pour un PSP, dépendre d'un seul SaaS pour la surveillance de conformité est un risque opérationnel
documenté ; un moteur auto-hébergé est une alternative ou un **filet de secours indépendant**. Nous ne devons pas
exploiter cet incident de façon dénigrante : c'est un argument d'architecture, pas de marketing agressif.

## 3. Premier marché visé (hypothèse à valider)

**Fintechs, PSP, exchanges de taille moyenne et entreprises qui manipulent des stablecoins** et veulent surveiller
leurs flux entrants/sortants sur leur propre infrastructure, intégrer la décision à leurs systèmes (API, webhooks,
règles en code) et garder la maîtrise de leurs données.

Pourquoi ce segment :

- TRON/USDT et EVM concentrent les flux de paiement en stablecoins et, selon Elliptic, la majorité des adresses de
  blanchiment du piratage Binance du 2026-10-29 (« Most are Tron and EVM (0x) addresses »).
- Ces acteurs n'ont pas toujours le budget d'un contrat Chainalysis/TRM (« contact sales ») ; Breadcrumbs facture
  TRON + score de risque 499 $/mois, Arkham Enterprise 1 500 $/mois.
- Le code existant est déjà orienté ainsi : indexation réelle TRON + Ethereum, USDT/USDC, gels d'émetteur,
  approbations, règles YAML, connecteurs webhook/Slack/Discord/Telegram.

Ce que ce segment exige et que nous n'avons pas encore (voir [PRODUCT_GAP_ANALYSIS.md](PRODUCT_GAP_ANALYSIS.md)) :
persistance de l'état indexé, faible taux de faux positifs mesuré, listes de sanctions réelles, API de décision
(screening pré-transaction), haute disponibilité minimale.

## 4. Principes non négociables

1. **Observé ≠ inféré ≠ prédit ≠ confirmé.** Toute assertion porte son niveau épistémique
   (voir ADR-0023 proposé). Une association probabiliste n'est jamais présentée comme une identité.
2. **Pas de score opaque.** Chaque score expose ses contributions, ses sources, sa méthode et la version du moteur
   (déjà le cas : `RiskScoringEngine`, modèle `oci-risk-1`).
3. **Reproductibilité.** Une investigation enregistre ses entrées, ses sources, ses paramètres, la version du moteur
   et peut être rejouée.
4. **Déterminisme d'abord.** L'IA sert à la corrélation, à l'assistance et à la rédaction, jamais à produire seule
   une conclusion de risque.
5. **Honnêteté sur les limites des données.** Nœud non-archive, absence de transactions internes, attribution
   limitée : affiché à l'analyste, pas caché.
6. **Pas de fonctionnalité affichée qui ne marche pas.** (Voir les défauts identifiés en phase 0.)
7. **La sécurité et la vérification ne sont jamais payantes** (voir [MONETIZATION_STRATEGY.md](MONETIZATION_STRATEGY.md)).

## 5. Réponse à la question finale de la mission

> *Comment faire de ChainSentinel une plateforme Open Source d'intelligence blockchain qui ne se contente pas de
> détecter les risques, mais qui comprend les comportements, automatise les investigations, anticipe les menaces et
> transforme les données blockchain en renseignements exploitables et vérifiables ?*

**En faisant de la preuve le produit, et en commençant là où personne n'est ouvert : la surveillance temps réel des
rails stablecoin, auto-hébergée.** Concrètement :

1. **Fiabiliser avant d'étendre (phase 0-1).** Aujourd'hui l'instance réelle produit 4 065 alertes ouvertes, dont
   l'essentiel sur trois règles bruyantes, et 5 675 notifications perdues vers un connecteur par défaut inexistant ;
   tout l'état indexé est perdu au redémarrage. Une plateforme d'intelligence qu'on ne peut pas croire n'a aucune
   valeur. On mesure le bruit, on persiste l'état, on charge des listes réelles (OFAC, gels d'émetteur, TagPacks).
2. **Comprendre les comportements avec des détecteurs ouverts et mesurés (phase 1-2).** Les 10 détecteurs existants
   s'appuient sur des données brutes, sans base d'attribution : c'est compatible avec l'auto-hébergement. On publie
   leurs seuils, leurs taux de faux positifs mesurés sur données réelles, et on les rend réglables par règle.
3. **Automatiser l'investigation comme un artefact vérifiable (phase 3).** Une investigation est un objet persistant :
   entrées, sources, paramètres, version du moteur, graphe, assertions typées (observé/inféré/prédit/confirmé),
   rapport exportable, rejouable, validable par un humain. C'est notre différence principale avec les « agents » des
   concurrents : ils sont fermés ; les nôtres sont auditables ligne à ligne.
4. **Anticiper sans sur-promettre (phase 4).** Une prédiction est une hypothèse datée, avec signaux, confiance,
   limites et date d'expiration, évaluée a posteriori (taux de réalisation publié). La simulation s'appuie sur
   l'existant (`RuleTester` dry-run) : « que se passerait-il si cette règle avait tourné sur les 30 derniers jours ? ».
5. **Rendre le tout pilotable par des agents dans le périmètre du client (phase 5).** MCP et SDK générés depuis
   l'OpenAPI, avec les mêmes scopes par opération que l'API. Ce n'est pas un différenciateur (tout le monde en a un),
   c'est un prérequis — mais un MCP **sur ses propres données, chez soi** reste rare.
6. **Ouvrir l'intelligence sans la diluer (phase 6).** Format interopérable (TagPacks), provenance, réputation,
   révocation ; une information communautaire non vérifiée n'est jamais affichée comme un fait.

L'avantage tient à la combinaison **ouvert + auto-hébergé + temps réel + rails stablecoin + règles en code +
investigations prouvées**. Le risque principal — la profondeur d'attribution des leaders — se compense partiellement
par l'import de sources ouvertes et par la possibilité d'utiliser ChainSentinel **à côté** d'un fournisseur
commercial, pas nécessairement à sa place.
