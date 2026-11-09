# Analyse concurrentielle

> Synthèse en français de [market-research-sources.md](market-research-sources.md) (rapport complet, ~60 sources
> officielles consultées le 2026-11-09, citations exactes, inférences marquées). Règle appliquée : une capacité non
> trouvée est notée « non trouvée dans la documentation publique », jamais « absente ». Aucune limite concurrente n'a
> été inventée.
>
> Deux faits ont été revérifiés directement à la source avant rédaction : l'incident de sécurité Chainalysis
> (billet officiel du 2026-10-30, mis à jour le 2026-11-07) et la liste de blocage Elliptic du piratage Binance du
> 2026-10-29 (« Most are Tron and EVM (0x) addresses »).

## 1. Contexte de marché (octobre-novembre 2026)

- **Incident Chainalysis** : « names, usernames, password hashes and live tokens for all Chainalysis users were
  exposed » ; Reactor et Data Solutions coupés à partir du 2026-11-02, KYT (UI et API) et Address Screening coupés le
  2026-11-04, rétablis le 2026-11-07.
- **Piratage Binance (2026-10-29)** : Elliptic publie une liste de blocage gratuite, sans compte, exposée aussi via
  un serveur MCP public ; la majorité des adresses sont TRON et EVM.
- **MCP devient la norme** : TRM (2026-07-06), GraphSense (26.06), Arkham (2026-10-06), Elliptic (public),
  Breadcrumbs.

## 2. Fiches concurrents (essentiel)

| Acteur | Produits | Forces documentées | Limites documentées | Couverture (déclarée) | Modèle | Open source |
|---|---|---|---|---|---|---|
| **Chainalysis** | KYT, Address Screening, Reactor, Data Solutions, Hexagate, Alterya, agents (bêta) | « hundreds of clustering heuristics », alertes en secondes, exposition indirecte multi-sauts, 1 500+ clients, agents « deterministic… auditable » | prix non publics ; agents réservés aux clients DS ; oracle de sanctions déprécié ; incident 2026-10-30 → 11-07 | KYT 400+ réseaux ; Reactor 27+ chaînes | entreprise, sur devis | aucun trouvé |
| **TRM Labs** | Wallet Screening, Transaction Monitoring, Forensics, Signatures®, Beacon Network | règles multi-conditions, ML (Signatures), réseau Beacon (niveau de base gratuit), MCP | prix non publics | 150 chaînes, 55+ en support standard | entreprise ; Beacon gratuit (VASP vérifiés) | aucun trouvé |
| **Elliptic** | Lens, Investigator, Navigator, Ecosystem Monitoring, Copilot | décisions journalisées « with the risk factors behind it », 1B+ adresses étiquetées, MCP public gratuit | prix non publics | 60+ | entreprise | SDK Apache-2.0, plateforme fermée |
| **Arkham** | Intel Platform, Tracer, Visualizer, Intel Exchange, alertes | 2,2B+ adresses attribuées, offre gratuite, MCP | — | 16 chaînes | **gratuit + Enterprise 1 500 $/mois** | aucun trouvé |
| **Blockaid** | simulation pré-transaction, Cosigner, Risk Exposure (mai 2026), Onchain Monitoring | prévention des drainers/approbations, ML de menace, TRON depuis juin 2026 | Cosigner tourne chez Blockaid | — | sur devis | SDK Node Apache-2.0 |
| **Breadcrumbs** | Investigation Tool, AutoTrace, Monitoring, Network, API | grille tarifaire publique complète, réseau communautaire, MCP | **TRON et score de risque réservés au plan à 499 $/mois** ; on-premise 380 000 $/an + 30 000 $ | — | public, par paliers | aucun trouvé |
| **GraphSense / Iknaio** | Pathfinder 2, REST API, `graphsense-lib`, CLI, TagPacks, clustering ; Iknaio : hébergé, CaseConnect, QuickLock | **MIT, auto-hébergé**, TRON « full mode » + USDT, publications académiques, sorties mensuelles, MCP intégré (26.06) | infrastructure lourde (Spark + Cassandra) — raison d'être d'Iknaio ; EVM « Lite » à fonctionnalités réduites | 14 chaînes (6 Full, 8 Lite EVM) | OSS gratuit ; Iknaio sur devis | **oui (MIT)** |

## 3. Matrice comparative

**P** = présent (documenté) · **V** = présent, profondeur à vérifier · **A** = non trouvé dans la documentation
publique · **CS** = ChainSentinel aujourd'hui (vérifié dans le code, `44c0990`).

| Capacité | Chainalysis | TRM | Elliptic | Arkham | Blockaid | Breadcrumbs | GraphSense | **CS** |
|---|---|---|---|---|---|---|---|---|
| Surveillance temps réel (KYT) | P | P | P | V | V | V | A | **P** (TRON + Ethereum, ~1 min derrière la tête) |
| Screening / score de risque | P | P | P | P | P | P | A | **P** (sans liste de sanctions réelle) |
| Explicabilité des scores | P | V | P | V | P | A | A (pas de score) | **P** (contributions, méthode, version) |
| Attribution / clustering | P | P | P | P | V | P | P | **A** (aucun clustering, aucun jeu de labels réel) |
| Traçage multi-sauts / graphe | P | P | P | P | A | P | P | **V** (BFS ≤ 3 sauts sur les flux en mémoire) |
| Dossiers et rapports | P | P | P | P | A | V | V | **A** (pas de dossier ni de rapport) |
| Analyse comportementale / prédictive | P | P | V | A | P | A | V | **V** (10 détecteurs, rien de prédictif) |
| Simulation pré-transaction | V | A | A | A | P | A | A | **A** (dry-run de règles seulement) |
| Intelligence communautaire | V | P | A | P | A | P | P | **V** (signalements stockés, jamais modérés) |
| Auto-hébergement | A | A | A | A | A | P (380 k$/an) | P | **P** |
| Open source | A | A | A (SDK) | A | A (SDK) | A | P | **P** (Apache-2.0) |
| API / SDK / webhooks | P | P | P | P | P | P | P | **V** (API REST, webhooks signés ; pas de SDK) |
| Agents IA / MCP | V | P | V | P | A | P | P | **A** |
| Focus USDT sur TRON | V | V | V | V | V | V (payant) | V | **P** (gels d'émetteur inclus) |
| Transparence tarifaire | A | A | A | P | A | P | P | **P** (gratuit) |

## 4. Lecture honnête

1. **L'explicabilité n'est pas un différenciateur** — Elliptic, Blockaid et Chainalysis l'ont. Ce qui peut l'être :
   des règles **ouvertes, portables, testables en CI**, et des investigations **rejouables**.
2. **MCP est un prérequis, pas un différenciateur** — cinq acteurs l'ont ; GraphSense l'a déjà en auto-hébergé.
3. **L'auto-hébergement temps réel est le vrai trou du marché** — GraphSense est ouvert mais ne surveille pas ;
   les SaaS surveillent mais ne s'auto-hébergent pas ; l'on-premise commercial coûte 380 k$/an.
4. **La spécialisation TRON/stablecoins est un choix de positionnement, pas un trou de capacité** — tout le monde
   couvre TRON ; personne ne se positionne dessus en premier.
5. **Le risque principal est l'attribution** — nous n'avons ni clustering ni base d'entités. Un PSP veut savoir si
   la contrepartie est un exchange, un mixeur ou une adresse sanctionnée ; sans labels, nos alertes restent
   comportementales.

## 5. Opportunités de différenciation (classées)

| # | Opportunité | Force | Contre-argument principal |
|---|---|---|---|
| O1 | **KYT temps réel auto-hébergé pour rails stablecoin** (TRON + EVM) | forte | GraphSense couvre l'analyse ouverte ; attribution faible |
| O3 | **TRON-first, événements d'émetteur (gels USDT/USDC) comme signaux** | moyenne | facile à copier ; Elliptic gère déjà le gel côté émetteur |
| O2 | **Règles déclaratives ouvertes, versionnées, testables en CI** | moyenne | l'explicabilité existe ailleurs ; angle étroit |
| O6 | **Moteur de secours indépendant / second avis** à côté d'un KYT commercial | moyenne | un seul incident documenté ; coût de double exploitation |
| O5 | **Bring-your-own-intelligence** (TagPacks, OFAC, listes partenaires, labels internes) | moyenne | qualité d'attribution inférieure ; GraphSense lit déjà les TagPacks |
| O8 | **Détecteurs comportementaux sans base d'attribution, publiés et mesurés** | moyenne | TRM Signatures, Blockaid ML existent ; ne différencie que s'ils sont publics et benchmarkés |
| O4 | **Prix transparents** (gratuit en auto-hébergé, support payant) | faible seule | plusieurs offres gratuites existent déjà |
| O7 | **MCP sur ses propres données, dans son périmètre** | faible | GraphSense le fait déjà en auto-hébergé |

**Conclusion** : on investit dans O1 + O3 + O2 + O5, on traite O6 comme argument commercial, O7 comme prérequis
technique, O8 comme exigence de qualité (benchmark publié).

## 6. Ce que cette analyse ne permet pas d'affirmer

- Les volumes réels de clients, les taux de faux positifs ou la qualité d'attribution des concurrents : non publics.
- L'absence d'une capacité chez un concurrent : seulement son absence de la documentation publique consultée.
- La disposition réelle du segment visé à payer : à valider par entretiens (voir
  [MONETIZATION_STRATEGY.md](MONETIZATION_STRATEGY.md), section « Validation »).
