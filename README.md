<p align="center">
  <img src="frontend/public/favicon.svg" width="64" height="64" alt="ChainSentinel logo">
</p>

<h1 align="center">ChainSentinel</h1>

<p align="center">
  <strong>Open source, self-hosted blockchain intelligence: explainable risk signals, fund flows and alerts.</strong>
</p>

<p align="center">
  <a href="https://github.com/alertmanager12-blip/openchain-intelligence/actions/workflows/ci.yml"><img src="https://github.com/alertmanager12-blip/openchain-intelligence/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/alertmanager12-blip/openchain-intelligence" alt="Licence: Apache-2.0"></a>
</p>

<p align="center"><strong>English</strong> · <a href="README.fr.md">Français</a></p>

ChainSentinel indexes TRON and Ethereum itself, profiles every wallet it sees, explains each risk signal with the
transactions behind it, scores wallets with traceable contributions, and turns your YAML rules into alerts delivered to
webhooks, Slack, Discord or Telegram. Everything runs on your own machine or server with Docker.

> **Status: 0.1.0-SNAPSHOT, no release yet.** It works end to end on mainnet data, but APIs and schemas may still
> change before 1.0. Licence: [Apache-2.0](LICENSE).
>
> **Naming:** the project was started as *OpenChain Intelligence*. Module names (`oci-*`), Java packages
> (`io.openchain`), environment variables (`OCI_*`) and the repository name keep that technical prefix.

**Contents:** [Why](#why-chainsentinel) · [What works today](#what-works-today) ·
[Supported blockchains](#supported-blockchains) · [Known limitations](#known-limitations) ·
[In development](#in-development) · [Step 1 — Fork and clone](#step-1--fork-and-clone) ·
[Step 2 — Install the tools](#step-2--install-the-tools) · [Step 3 — Try the demo](#step-3--try-the-demo-5-minutes) ·
[Step 4 — Run it for real](#step-4--run-it-for-real-production) · [Step 5 — Develop](#step-5--develop-and-test) ·
[Step 6 — Keep your fork up to date](#step-6--keep-your-fork-up-to-date) · [Troubleshooting](#troubleshooting) ·
[How it is built](#how-it-is-built) · [Documentation](#documentation) · [Contributing](#contributing) ·
[Licence](#licence)

---

## Why ChainSentinel

Every transfer on a public blockchain is visible, yet turning raw blocks into a decision still takes hours: millions of
transfers a day without context, wallets that act together through splits and relays, static blocklists that lag behind
behaviour, evidence scattered across tools, and scores nobody can explain to a customer or an auditor.

ChainSentinel's aim is an intelligence engine you can run, read and audit yourself:

- **Explainable first:** every signal names its detector, its evidence (transactions, amounts, time window) and its
  evidence level (`ANOMALY`, `RISK_SIGNAL`, `CONFIRMED_BY_SOURCE`). Every point of a risk score names its source.
- **Neutral wording:** it reports observed behaviour, never accusations. Decisions stay with your analysts.
- **Self-hosted:** your data, your nodes or providers, no external scoring service.
- **Programmable:** detection rules are declarative YAML (no user code execution), tested on recorded data before you
  enable them.

The longer-term vision and roadmap are in [docs/strategy](docs/strategy/README.md) (French).

## What works today

| Area | What it does today |
|---|---|
| Ingestion | TRON and EVM indexers with confirmations, reorg handling and gap-free resume after a restart: token transfers (USDT, USDC…), native transfers of successful transactions, ERC-20/TRC-20 approvals, stablecoin issuer freezes (Tether, Circle — official contracts only), contract ownership changes |
| Investigation | Wallet page (profile, counterparties, signals, labels), transaction page, fund-flow graph (up to 3 hops), on-demand ERC-20 history of an address you open |
| Risk | Ten detectors (structuring, fan-out, fast forwarding, rapid drain, circular flows, unusual amounts, unusual approvals, dormant reactivation, ownership changes, exposure to listed addresses) and a 0–100 score where every point names its source |
| Monitoring | Rules as YAML or with a visual builder, dry-run simulation on recorded data, versioning, watchlist of your own wallets, alert inbox (acknowledge, assign, comment, resolve, false positive) |
| Notifications | Signed webhooks (HMAC-SHA256, SSRF-guarded, retries), Slack, Discord, Telegram |
| Platform | REST API v1, API keys (hashed, scoped, expiring, revocable) and optional OIDC, PostgreSQL persistence, audit log stream, Prometheus metrics (optional Grafana profile) |

## Supported blockchains

| Network | Status |
|---|---|
| Ethereum mainnet | Indexed and used in production by the maintainer |
| TRON mainnet | Indexed and used in production by the maintainer (TronGrid or your own node). No historical balances, so the rapid-drain detector is inactive on TRON |
| BSC, Polygon | Same EVM indexer, can be enabled in `.env`, **not validated in production yet** |
| Demo simulator | Simulated chains with fictitious labels, for trying the product (`OCI_DEMO_MODE=true`, never in production) |

Bitcoin and Solana are not supported.

## Known limitations

- **Wallet profiles, signals and risk scores live in memory.** Alerts, rules, labels, keys and indexing checkpoints are
  stored in PostgreSQL, but the intelligence working set is rebuilt from new blocks after a restart (the persistence
  journal of [ADR-0024](docs/adr/0024-persist-chain-derived-state.md) is off by default because its reload does not
  scale to chain-wide volumes yet). At full Ethereum + TRON volume this working set grows by several hundred megabytes
  per hour; plan memory accordingly.
- **Indexing speed depends on your provider.** Public endpoints are rate-limited; TRON through TronGrid can fall behind
  the chain head.
- **Old native transfers and internal transactions** of an address need an archive/Erigon node
  ([ADR-0020](docs/adr/0020-address-history-sources.md)).
- **Single organisation:** no multi-tenancy, no API rate limiting yet.
- **Email notifications** are not available (Telegram, Slack, Discord and webhooks are).

## In development

Work in progress and next steps, in the order of the [roadmap](docs/strategy/FEATURE_ROADMAP.md):

- **Bounded memory and durable intelligence** (ADR-0024, steps 3a/3b): keep the in-memory working set within a fixed
  budget and reload state after a restart.
- **Graph intelligence:** shortest paths between wallets, relationships.
- **Reproducible investigations** ([ADR-0022](docs/adr/0022-investigations-as-reproducible-artifacts.md)).
- **Developer ecosystem:** a read-only MCP server on top of the API ([ADR-0025](docs/adr/0025-mcp-as-api-adapter.md))
  and SDKs. Not available yet.

---

## Step 1 — Fork and clone

1. On GitHub, click **Fork** on this repository to get your own copy.
2. Clone **your** fork (replace `YOUR-ACCOUNT` and `YOUR-FORK`):

   ```bash
   git clone https://github.com/YOUR-ACCOUNT/YOUR-FORK.git
   cd YOUR-FORK
   ```

3. Remember the original repository as `upstream`, so you can pull future updates (see [Step 6](#step-6--keep-your-fork-up-to-date)):

   ```bash
   git remote add upstream https://github.com/alertmanager12-blip/openchain-intelligence.git
   ```

---

## Step 2 — Install the tools

To **run** ChainSentinel you only need Docker. To **develop** it you also need Java, Maven and Node.js.

| Tool | Version | Needed for | Check with |
|---|---|---|---|
| Docker Desktop (or Docker Engine) with Compose v2 | recent | running everything | `docker compose version` |
| JDK | 21 or newer | building the backend | `java -version` |
| Maven | 3.9 or newer | building the backend | `mvn -v` |
| Node.js | the version in `frontend/.nvmrc` (22.23.2) for local work; CI and the Docker image build with Node 24 | building the console | `node -v` |

Tips:
- With [nvm](https://github.com/nvm-sh/nvm), `cd frontend && nvm install && nvm use` picks the right Node version.
- Give Docker at least **4 GB of RAM** (Docker Desktop → Settings → Resources).
- Make sure Docker is **running** before the next steps (`docker info` must answer).

---

## Step 3 — Try the demo (5 minutes)

The demo runs on **simulated** blockchains with **fictitious** data, so you need no account, no key and no internet
access to a blockchain.

1. Start everything (the first build takes a few minutes):

   ```bash
   docker compose up -d --build
   ```

2. Wait until the three services are healthy:

   ```bash
   docker compose ps          # app, frontend and postgres should show "healthy"
   ```

3. Get the demo administrator key. It is printed **once** in the logs and is valid 24 hours:

   ```bash
   docker compose logs app | grep "demo admin API key"
   ```

4. Open **http://localhost:4200**, go to **Settings → Connection**, choose **API key** and paste the key.

5. Explore:
   - **Overview**: what is happening right now.
   - **Alerts**: an inbox; select an alert to see why it fired. Keyboard: `J`/`K` to move, `E` to acknowledge, `Esc` to close.
   - Click an address to open its **wallet page** and the explanation of its score.
   - `⌘K` (or `Ctrl K`) searches an address, a transaction or a page; `?` lists all shortcuts.
   - Six scripted scenarios show the whole chain *event → signal → score → rule → alert → notification*:
     `curl -s -H "X-API-Key: <your key>" http://localhost:4200/api/v1/demo/scenarios`

6. Stop it when you are done:

   ```bash
   docker compose down         # keeps the data
   docker compose down -v      # also deletes the data (demo alerts, keys, rules)
   ```

A guided tour is in [docs/guides/quickstart.md](docs/guides/quickstart.md).

---

## Step 4 — Run it for real (production)

In production ChainSentinel reads **real** blockchains and contains **no demo data**. You configure everything in one file, `.env`.

### 4.1 Create your `.env`

```bash
cp deploy/.env.example .env
```

`.env` is ignored by git: it holds secrets, **never commit it**.

### 4.2 Fill in the required values

Open `.env` and set:

| Variable | What to put | How to generate |
|---|---|---|
| `OCI_DEMO_MODE` | `false` | — |
| `POSTGRES_PASSWORD` | a long random password | `openssl rand -base64 32` |
| `OCI_CRYPTO_MASTER_KEY` | encrypts connector secrets | `openssl rand -base64 32` |
| `OCI_SECURITY_API_KEY_PEPPER` | used to hash API keys | `openssl rand -base64 48` |
| `OCI_ADMIN_API_KEY` | your administrator key | see the command below |

Administrator key (format `oci_live_<8 characters>_<43 characters>`):

```bash
echo "oci_live_$(LC_ALL=C tr -dc A-Za-z0-9 </dev/urandom | head -c 8)_$(LC_ALL=C tr -dc A-Za-z0-9 </dev/urandom | head -c 43)"
```

Keep the master key and the pepper safe and stable: if you lose or change them, stored connector secrets and API keys
become unusable. The application **refuses to start** in production if one of them is missing or if you reuse the
public demo values.

### 4.3 Choose your blockchains

Enable at least one network, otherwise there is nothing to index:

```bash
# Ethereum: any JSON-RPC endpoint (your own node, Alchemy, Infura, or a public endpoint to try)
OCI_CHAINS_ETHEREUM_ENABLED=true
OCI_CHAINS_ETHEREUM_URL=https://ethereum-rpc.publicnode.com

# TRON: TronGrid; a free key from trongrid.io gives higher limits
OCI_CHAINS_TRON_ENABLED=true
OCI_CHAINS_TRON_URL=https://api.trongrid.io
OCI_CHAINS_TRON_API_KEY=
```

Public endpoints are fine to try but are **rate-limited**: indexing may lag. For real use, take a provider key or
run your own node.

### 4.4 Start

If you ran the demo before, delete its data first so no fictitious data remains:

```bash
docker compose down -v
```

Then start in production mode:

```bash
docker compose up -d --build
docker compose ps
docker compose logs app | grep -E "Persistence mode|Started|ERROR"
```

Open **http://localhost:4200**, go to **Settings → Connection → API key** and paste your `OCI_ADMIN_API_KEY`.
Then create narrower keys for your colleagues and integrations in **Settings → API keys**.

What to expect at first: ChainSentinel indexes from the current block onwards, so pages fill up as new blocks arrive. When you
open an address, its past ERC-20 transfers and approvals are fetched on demand; its older native transfers stay
invisible unless you connect an Erigon node ([ADR-0020](docs/adr/0020-address-history-sources.md)).

### 4.5 Before exposing it on a network

The Compose stack is meant for a single machine. Before giving access to others, read
[docs/guides/security-hardening.md](docs/guides/security-hardening.md): put it behind HTTPS (reverse proxy), do not
expose ports 8080 or 5432 to the internet, back up PostgreSQL **and** the master key, and use OIDC for user sign-in if
you have an identity provider. All settings are described in [docs/guides/installation.md](docs/guides/installation.md).

---

## Step 5 — Develop and test

### Build and test everything

```bash
mvn -B clean verify                           # backend: builds and runs all tests
```

Keep Docker running: the PostgreSQL integration tests start a temporary database with Testcontainers. Without Docker
they are **skipped** (the build still says SUCCESS), so check that the report shows `Skipped: 0`.

```bash
cd frontend
nvm use                                       # Node version from .nvmrc
npm ci                                        # install exact dependency versions
npm test                                      # unit tests
npm run build                                 # production build
cd ..
```

End-to-end check of the Docker stack (it builds the images, starts a separate demo stack, tests it, restarts it and
stops it; stop your own stack first because it uses the same ports):

```bash
docker compose down
bash deploy/smoke-test.sh
```

### Run the backend and the console from source

Terminal 1 — the backend in demo mode, without Docker:

```bash
mvn -q -B install -DskipTests
mvn -pl oci-app spring-boot:run               # http://localhost:8080
```

Terminal 2 — the console with live reload, talking to that backend:

```bash
cd frontend
npm run start:api                             # http://localhost:4200
```

In this setup, the console key is the documented development key
`oci_demo_devui001_DemoOnlyAngularDevServerKeyNotSecret0001` (demo profile only, never valid in production).

`npm start` (instead of `start:api`) runs the console alone on built-in mock data — handy for pure UI work.

### Where things are

```
oci-core/          domain model shared by all modules (no framework)
oci-blockchain/    TRON and EVM adapters, block indexer, reorg handling
oci-intelligence/  detectors, risk scoring, labels
oci-alerting/      rule language, rule engine, alerts, notification connectors
oci-app/           Spring Boot application: REST API, security, persistence, demo mode
frontend/          Angular console
rules/             community rule pack (YAML), loaded at startup
deploy/            Dockerfiles, nginx config, .env.example, smoke test
docs/              architecture, decisions (ADRs), API contract, guides
```

A rule looks like this (more in [docs/guides/writing-rules.md](docs/guides/writing-rules.md)):

```yaml
apiVersion: openchain.io/rule/v1
id: usdt-burst-outflow
version: 1.0.0
name: USDT burst outflow
author: your-team
scope:
  networks: [tron]
  eventTypes: [TOKEN_TRANSFER]
conditions:
  logic: AND
  rules:
    - { field: transaction.token.symbol, operator: EQUALS, value: USDT }
aggregation:                      # SUM over a sliding 1h window, per sender
  window: 1h
  groupBy: [transaction.from]
  function: SUM
  field: transaction.amount
  operator: GREATER_THAN_OR_EQUAL
  threshold: "50000"
severity: HIGH
cooldown: 6h
actions:
  - { type: WEBHOOK, connectorId: ops-webhook }
```

---

## Step 6 — Keep your fork up to date

```bash
git fetch upstream
git checkout main
git merge upstream/main          # or: git rebase upstream/main
git push origin main
```

Then rebuild: `docker compose up -d --build`. Database migrations run automatically at startup; back up PostgreSQL
first in production. Read [CHANGELOG.md](CHANGELOG.md) for changes that need your attention.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Cannot connect to the Docker daemon` | Docker is not running. Start Docker Desktop and wait until `docker info` answers. |
| `port is already allocated` (8080, 4200 or 5432) | Another program uses the port. Stop it, or stop the other stack with `docker compose down`. |
| The app container restarts in a loop | Read `docker compose logs app`. In production, a missing or demo `OCI_CRYPTO_MASTER_KEY` / `OCI_SECURITY_API_KEY_PEPPER` is refused at startup with an explicit message. |
| `401` / "missing or invalid credentials" in the console | No key or an expired one. Demo: get the newest key from the logs (a new one is generated at each start). Production: paste `OCI_ADMIN_API_KEY` again. |
| "network 'bsc' is not enabled on this instance" | You opened an address on a network this instance does not index. Open it on an enabled network, or enable that network in `.env`. |
| An address shows no transactions | In demo mode only simulated addresses exist. In production, history is fetched on demand when you open the address; older native transfers need an Erigon node (ADR-0020). |
| Indexing lags behind | Public endpoints are rate-limited. Use a provider key (`OCI_CHAINS_TRON_API_KEY`, a paid RPC URL) or your own node. |
| Backend tests show `Skipped:` above 0 for `oci-app` | Docker was not running during `mvn verify`; the PostgreSQL tests were skipped. Start Docker and run again. |
| `npm` / Angular refuses your Node version | Use the version in `frontend/.nvmrc` (`nvm use`). |
| I want to start from scratch | `docker compose down -v` deletes all data (keys, rules, alerts). |

---

## How it is built

A modular monolith: one Spring Boot application (compiled for Java 21, run on Java 25 in the Docker image) made of independent modules, an Angular console, and
PostgreSQL. Blockchain events flow through the indexer to detection and scoring, then to the rule engine, which raises
alerts and delivers notifications through an outbox.

```mermaid
flowchart LR
  TG[TronGrid / TRON node] & RPC[EVM JSON-RPC] --> IDX[Indexer]
  IDX --> DET[Detection] --> SC[Risk scoring]
  IDX --> RE[Rule engine]
  DET --> RE
  RE --> AL[Alerts] --> DLV[Delivery] --> EXT[Webhooks / Slack / Discord / Telegram]
  AL --> PG[(PostgreSQL)]
  SC --> MEM[(In memory: profiles, signals, scores)]
  UI[Angular console] --> API[REST API v1] --> PG
```

More: [architecture overview](docs/architecture/overview.md) · [rule engine](docs/architecture/rule-engine.md) ·
[architecture decisions (ADRs)](docs/adr/README.md).

---

## Documentation

Start with the [documentation index](docs/README.md). The most used pages:

| You want to… | Read |
|---|---|
| Install and operate it | [Installation guide](docs/guides/installation.md), [quickstart](docs/guides/quickstart.md), [security hardening](docs/guides/security-hardening.md) |
| Write detection rules | [Writing rules](docs/guides/writing-rules.md), [rule pack](rules/README.md) |
| Use the API | [API contract](docs/api/README.md) |
| Understand the design | [Architecture overview](docs/architecture/overview.md), [data model](docs/architecture/data-model.md), [ADRs](docs/adr/README.md) |
| Test your changes | [Testing strategy](docs/testing-strategy.md) |
| Get help | [SUPPORT.md](SUPPORT.md) |

---

## Contributing

Contributions of code, rules, documentation and reviews are welcome, including from first-time contributors.

- How to set up, branch, test and open a pull request: [CONTRIBUTING.md](CONTRIBUTING.md).
- How decisions are made: [GOVERNANCE.md](GOVERNANCE.md).
- Expected behaviour: [Code of Conduct](CODE_OF_CONDUCT.md).
- Questions and help: [SUPPORT.md](SUPPORT.md).
- **Security vulnerabilities:** never in a public issue; report them privately as described in [SECURITY.md](SECURITY.md).

## Licence

ChainSentinel is licensed under the [Apache License 2.0](LICENSE). Third-party notices are in [NOTICE](NOTICE); the
dependency licence policy (checked in CI) is [ADR-0012](docs/adr/0012-licensing-policy.md).

## Disclaimer

ChainSentinel outputs are risk indicators derived from public blockchain data and attributed third-party sources. They are not
legal determinations, do not identify any person, and must be reviewed by a qualified human before any decision
affecting someone.
