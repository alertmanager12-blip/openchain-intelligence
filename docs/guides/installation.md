# Installation

## Requirements

| Component | Version |
|---|---|
| Docker Engine + Compose v2 | 24+ |
| (build from source) JDK | 21 |
| (build from source) Maven | 3.9+ |
| (build from source) Node.js | 22 LTS |
| RAM / disk (demo) | 2 GB / 2 GB |
| RAM / disk (one live network, 13 months transfers) | 8 GB / 200 GB+ SSD (depends on tracked tokens) |

## Option A — Docker Compose (recommended)

```bash
git clone https://github.com/openchain-intelligence/openchain-intelligence.git
cd openchain-intelligence
cp deploy/.env.example .env      # then edit secrets
docker compose up -d --build
```

Services:

| Service | URL | Notes |
|---|---|---|
| frontend | http://localhost:4200 | nginx serving the Angular build, proxies `/api` to the app |
| app | http://localhost:8080 | API v1 and `/actuator/health` (other actuator endpoints require `keys:admin`) |
| postgres | localhost:5432 | PostgreSQL 17, `pgdata` volume: operator state (API keys, connectors, rules, alerts, deliveries, label reports, users); schema migrated by Flyway when the app starts |

In demo mode (`OCI_DEMO_MODE=true`, the compose default) the app indexes two simulated chains (TRON, Ethereum), loads the community rule pack from `rules/` and prints a generated admin API key **once** in its logs:

```bash
docker compose logs app | grep "demo admin API key"
```

Paste it in the console (Settings, authentication "API key"). A new demo admin key is generated at every start and the previous one is revoked (take the last one logged). `deploy/smoke-test.sh` starts the stack and checks authentication, rules, demo scenarios A–E and the frontend proxy end to end, then restarts the app and checks that a key, a connector, a rule and an acknowledged alert survived.

The compose file passes a **publicly known, dev-only** `OCI_CRYPTO_MASTER_KEY` default so that the demo runs out of the box with PostgreSQL; the application refuses that key (and the dev-only API key pepper) when `OCI_DEMO_MODE=false`.

### Persistence and the `pgdata` volume

- `docker compose restart app`, `docker compose down` + `up`: operator state is kept (it lives in the `pgdata` volume).
- `docker compose down -v` **deletes the volume** and with it every API key, connector, rule change and alert. Back up first.
- Use a fresh database for production: do not reuse a database that served a demo (when demo mode is off, keys created by a previous demo bootstrap are revoked at startup, but demo alerts and rules stay).

### Observability profile (not instrumented yet)

```bash
docker compose --profile observability up -d
```

starts Prometheus (http://localhost:9090), Grafana (http://localhost:3000, admin password from `GRAFANA_ADMIN_PASSWORD`) and an OpenTelemetry Collector. **The application does not export metrics or traces yet** (no Prometheus registry, no OpenTelemetry SDK): Prometheus finds no target and the Grafana dashboard stays empty. Instrumentation is planned; until then this profile only provisions the tooling.

## Configuration

Settings are `oci.*` properties (see `oci-app/src/main/resources/application.yml`, the authoritative list), settable as environment variables through Spring relaxed binding.

| Variable | Default | Description |
|---|---|---|
| `OCI_DEMO_MODE` | `true` in compose, `false` otherwise | Simulated chains, fictitious labels, generated admin key. **Set `false` in production.** |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | set in compose | PostgreSQL 17 (`jdbc:postgresql://host:5432/db`). **Set = operator state persisted** (Flyway migrates the schema at startup); unset = in-memory mode (startup log `Persistence mode: IN-MEMORY`, a WARN outside demo mode) |
| `OCI_SECURITY_API_KEY_PEPPER` | — | Secret pepper for API key hashing (≥ 32 bytes, `openssl rand -base64 48`). **Required with PostgreSQL outside demo mode** (startup fails otherwise); demo mode with PostgreSQL uses a documented dev-only pepper; in-memory mode uses a random one (keys invalidated at every restart). Changing it invalidates every stored key. |
| `OCI_CRYPTO_MASTER_KEY` | dev-only key in compose (demo only) | base64 of 32 random bytes (`openssl rand -base64 32`): AES-256-GCM key of connector secrets and targets at rest. **Required with PostgreSQL** (startup fails if missing, not 32 bytes, or the compose dev key outside demo mode). Never logged; losing it makes stored connector secrets unrecoverable. |
| `OCI_PERSISTENCE_DELIVERY_CLAIM_LEASE` | `2m` | How long an instance holds a claimed delivery; deliveries claimed by a crashed instance are retried after it |
| `OCI_SECURITY_OIDC_ISSUER_URI` / `OCI_SECURITY_OIDC_AUDIENCE` | — | OIDC issuer (enables JWT bearer authentication) and optional audience |
| `OCI_CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Allowed browser origins |
| `OCI_RULES_DIRS` | `rules,../rules` | Rule pack directories (first existing wins); the image ships `/app/rules` |
| `OCI_TELEGRAM_BOT_TOKEN` | — | Enables TELEGRAM connectors (the chat id is given per connector; a group id is negative, e.g. `-100…`) |
| `OCI_PUBLIC_URL` | `http://localhost:4200` (Compose) | Public URL of the console, used for the "Console" link of Telegram messages; unset = no link |
| `OCI_CHAINS_TRON_ENABLED`, `OCI_CHAINS_TRON_PROVIDER=trongrid`, `OCI_CHAINS_TRON_URL`, `OCI_CHAINS_TRON_API_KEY_HEADER`, `OCI_CHAINS_TRON_API_KEY` | off | Live TRON indexing through a TronGrid-compatible API |
| `OCI_CHAINS_ETHEREUM_ENABLED`, `OCI_CHAINS_ETHEREUM_PROVIDER=json-rpc`, `OCI_CHAINS_ETHEREUM_URL`, `OCI_CHAINS_ETHEREUM_START_BLOCK` | off | Live EVM indexing through JSON-RPC (also `bsc`, `polygon`) |
| `OCI_CHAINS_<NETWORK>_HISTORY_ENABLED` | `true` | On-demand address history (ADR-0020) for a real network: see "Address history" below |
| `OCI_CHAINS_<NETWORK>_HISTORY_MAX_BLOCKS` | `500000` | Lookback: blocks searched below the first live-indexed block (~70 days on Ethereum, ~17 days on TRON) |
| `OCI_CHAINS_<NETWORK>_HISTORY_MAX_PAGES` / `..._MAX_EVENTS` | `200` / `5000` | Caps per address; reaching one marks the history TRUNCATED (older events not loaded) |
| `OCI_CHAINS_<NETWORK>_HISTORY_CHUNK_SIZE` / `..._MIN_CHUNK_SIZE` | `10000` / `50` | EVM: blocks per `eth_getLogs` query, halved on "range too large" answers down to the minimum |
| `OCI_CHAINS_<NETWORK>_HISTORY_PAUSE` | `100ms` | Pause between two pages (on top of the transport rate limiter) |
| `OCI_CHAINS_<NETWORK>_FALLBACK_URLS` | — | Comma-separated fallback endpoints tried in order when the primary fails with a retryable error (timeout, 429, 5xx, open circuit); the API key header is sent to the primary URL only |
| `OCI_DEFAULT_CONNECTOR` | — | Connector id receiving the alerts of rules routed to the pack placeholder `default-webhook`; unset = those alerts are recorded as not notified (delivery `SKIPPED`), never dead-lettered |
| `OCI_INGESTION_AGE_WARMUP` | `30d` | Observation time a real chain needs before "first seen by the platform" fields (`wallet.age_days`, `wallet.tx_count`, `counterparty.age_days`, `approval.spender_age_days`) are emitted; before that every address would look new |
| `OCI_API_DOCS_ENABLED` | demo mode | Serve `/v3/api-docs` and Swagger UI |
| `OCI_APP_MEMORY_LIMIT` | `4g` (Compose) | Memory limit of the app container; the JVM heap is 75% of it. Without a limit the heap is sized from the whole Docker host and the kernel may kill the app under memory pressure |

Live providers are implemented but have not been validated against production nodes yet.

### Address history (on demand, ADR-0020)

The live indexer only sees blocks from its start block onwards. When an analyst opens an address of a **real** network
(wallet analysis, risk or transactions endpoint; the console's wallet page), ChainSentinel loads that address's past
activity in the background through the network's own provider, then feeds it to profiles, detection, scoring and labels
like live data. **History never raises alerts** (rules are not evaluated for it). The wallet page shows a coverage line
("Loading history…" while it runs, then for example "History: ERC-20 transfers and approvals since block 25,865,009
(eth_getLogs, 37 event(s)). Native transfers and internal transfers are not covered: configure an Erigon node
(ADR-0020).") and refreshes itself until the load is finished; the API exposes it as `history` in the wallet analysis.

| Network | Source | Covers | Not covered |
|---|---|---|---|
| Ethereum, BSC, Polygon | `eth_getLogs` by topic on the configured JSON-RPC (two queries per chunk: the address as topic 1 of `Transfer`/`Approval`, and as topic 2 of `Transfer`) | ERC-20 transfers (sent and received), approvals granted by the address | native ETH/BNB/POL transfers, internal transactions, approvals where the address is the spender, ERC-721/1155 |
| TRON | TronGrid account endpoints (`/v1/accounts/{address}/transactions/trc20` and `/transactions`, `only_confirmed`) | TRC-20 transfers, TRX transfers (`TransferContract`, `TriggerSmartContract.call_value`) | internal transactions, approvals |
| Simulated demo chains | none | the indexed demo data is the whole history (`NOT_CONFIGURED`) | |

- **Range:** `[toBlock - max-blocks + 1, toBlock]` with `toBlock` = the provider's finalized head, or the block before
  the first block indexed live (whichever is lower), so history and live indexing never overlap. Pages are fetched
  newest first, so a capped (TRUNCATED) history keeps the most recent activity.
- **Cost:** EVM: 2 `eth_getLogs` per chunk of `chunk-size` blocks (500,000 blocks = 50 chunks = ~100 calls per address
  when the provider accepts 10,000-block ranges), plus one `eth_getBlockByHash` per distinct block when the node omits
  `blockTimestamp`. Many public / free gateways limit `eth_getLogs` ranges (observed on 2026-11-07: Cloudflare 800
  blocks, 1RPC 50 blocks, dRPC free plan under 100 blocks, PublicNode requires a token for archive ranges): the adapter
  halves the chunk automatically, but a small limit makes a long lookback slow; use your own node or a provider plan
  that accepts large ranges, or lower `max-blocks`. TRON: one TronGrid call per 200 events.
- **Non-archive nodes:** a node that only serves recent blocks (PublicNode without a personal token: about the last
  10,000-20,000 blocks, i.e. ~2 days on Ethereum, measured on 2026-11-07; pruned nodes) refuses older `eth_getLogs`.
  The history then ends **TRUNCATED** at that limit, with the recent events kept and applied and the message "the node
  only serves recent blocks…"; it is not retried at every analysis. For a longer lookback, use an archive node or a
  provider plan with archive access (`OCI_CHAINS_ETHEREUM_URL`).
- **Resumable and idempotent:** progress and found events are stored (PostgreSQL `address_history_progress` /
  `address_history_events` in JDBC mode, in memory otherwise). A restart re-applies finished histories without RPC and
  resumes unfinished ones from their cursor; a provider error marks the history FAILED with its progress kept, and the
  next analysis of the address resumes it. An address already covered is not fetched again (new blocks are covered by
  the live indexer).
- **Limits:** TronGrid gives no log index for TRC-20 history: those events get their own ids (`tron:<tx>:trc20:<hash>:<n>`)
  and no block number, so coverage on TRON is reported in time (`coveredFromTime`). Native and internal EVM value needs
  an Erigon `ots_` node or an opt-in commercial adapter (ADR-0020 steps 2-3, not implemented yet).
- Disable per network with `oci.chains.<network>.history.enabled: false`.

### Current limitations

- **Operator state is persisted** in PostgreSQL (API keys, connectors, rules and their versions, alerts with history and comments, the delivery outbox with retries and dead letters, label reports, users/roles) when `SPRING_DATASOURCE_URL` is set. **Chain-derived state is not persisted yet**: flows, wallet profiles, signals, risk scores, indexer checkpoints and the event history stay in memory and are rebuilt on restart by re-indexing from the configured start block (`OCI_CHAINS_*_START_BLOCK`; demo chains are regenerated). Re-indexing does not duplicate alerts: an event that already raised an alert for a rule is recognised. Alert details (address, counterparty, transaction hash, block, matched fields) are stored with each alert (migration V3) and survive restarts; for alerts created before V3 they are filled by the migration where the grouping key is the wallet, or when the triggering event is re-indexed, and stay empty otherwise.
- **Balances on real chains:** `wallet.balance` (used by `wallet-draining` through `RATIO_OF_BALANCE`) and the rapid drain detector use the balance reported by the provider at the end of the block before the transfer: EVM `eth_getBalance` (native) or `eth_call balanceOf` (tokens) at that block, one call per (sender, token, block), cached (LRU 10 000) — size the provider rate limit accordingly (about one extra call per indexed transfer sender). Your node must still have that block's state (any full node keeps the recent ~128 blocks; the indexer works near the head). Lookups are **budgeted** so they never stall indexing: none while the indexer catches up more than `OCI_CHAINS_<NETWORK>_REAL_BALANCE_MAX_LAG` blocks (default 32) behind the head, and at most `OCI_CHAINS_<NETWORK>_REAL_BALANCE_MAX_LOOKUPS_PER_BLOCK` (default 5) per block — the lookups of a block run concurrently (virtual threads) but each is a provider request, so a high value hits public endpoints' rate limits; beyond that the field is absent. Each real chain is indexed on its own thread, `OCI_CHAINS_<NETWORK>_MAX_BLOCKS_PER_POLL` blocks per pass (default 10), and is polled again immediately while it is behind the head (after `OCI_INGESTION_POLL_INTERVAL` once caught up). TRON blocks are fetched ahead in parallel (4 confirmed blocks). With a busy chain such as Ethereum mainnet and a rate-limited endpoint, balance-based rules therefore only see part of the transfers. When the balance is unavailable (error, rate limit, pruned state, budget) the field is **absent** and balance-based rules and the drain detector do not fire for that transfer — never an estimate. **TRON:** TronGrid exposes no historical balance, so on real TRON `wallet.balance` is absent and `wallet-draining` does not fire (demo chains are unaffected). Known noise on mainnet: `fund-splitting` (more than 30 distinct recipients in 3 minutes since 1.2.0) still fires on routers, exchanges and payout wallets; exclude them with labels/lists in your copy of the rule.
- Single instance: rules and connectors are loaded in memory at startup; outbox claiming is multi-instance safe (`FOR UPDATE SKIP LOCKED` + lease), but running several app instances is not supported yet.
- No rate limiting, idempotency keys or `If-Match` concurrency control yet.
- Notification channels: WEBHOOK, SLACK, DISCORD, TELEGRAM. EMAIL needs an SMTP transport that is not available yet (the `EmailConnector` exists, the transport does not).
- **Native value: top-level transactions only.** ETH/BNB/POL value of successful transactions (receipt `status` `0x1`) and TRX of successful `TransferContract` / `TriggerSmartContract.call_value` (`ret[0].contractRet` = `SUCCESS`) are indexed. **Internal transactions** (value sent by a contract during execution, e.g. withdrawals from a contract wallet, DEX/bridge payouts, TRON internal transactions) need trace APIs (`debug_traceBlock*`, `trace_block`, TRON `internal_transactions`) and are **not indexed**: such flows are invisible to exposure and drain detection. TRC-10 assets are not indexed either.
- **EVM receipts:** `eth_getBlockReceipts` is used when the node implements it (one call per block). Nodes answering "method not found/not supported" are detected once; the adapter then reads logs with `eth_getLogs({blockHash})` and calls `eth_getTransactionReceipt` for each transaction carrying native value (more requests per block on busy chains: size the provider rate limit accordingly). Pre-Byzantium receipts (no `status`) are not considered successful.
- **Approvals:** only tokens of the token registry (USDT/USDC deployments) by default; set `oci.chains.<network>.approvals-of-unknown-tokens: true` to ingest all. `approval.spender_is_contract` comes from the provider: EVM `eth_getCode(spender, block)` (falls back to `latest` when a non-archive node no longer has that block's state), TRON `POST /wallet/getcontract` (current state only). Answers are cached per address (bounded LRU of 10 000; "no code" answers are re-checked after 300 blocks because CREATE2 addresses can receive code later), so the RPC cost is about **one request per distinct spender** (+ re-checks); a failed lookup leaves the field absent and does not stop indexing. `approval.spender_age_days` is the number of days since the **platform first saw the spender in an indexed value flow** (same "first seen by the platform" semantics as `wallet.age_days`, so it is bounded by the indexer start block, not the on-chain deployment date); it is absent when the spender was never seen. **`approval.spender_verified` is not populated**: no in-repo source attests source-code verification (labels and router allow lists are not verification), so `rules/ethereum/suspicious-approval.yaml` fires through its `spender_age_days < 7` branch only (unlimited allowance to a contract first seen less than 7 days ago, outside its exclusion list). The rule is noisier right after a fresh start, when every spender is "young": extend its exclusion list with the routers your users rely on. Simulated (demo) chains have no code lookup.
- **Contract admin changes:** `OwnershipTransferred` (OpenZeppelin `Ownable` with indexed parameters, and the non-indexed variant used by Circle FiatToken), EIP-1967 `Upgraded` and `AdminChanged` are decoded on EVM and TRON and emitted as `CONTRACT_ADMIN_CHANGE` events (`admin.*`, `contract.address`, `contract.event`, `contract.new_owner`) for the token registry's contracts and `oci.chains.<network>.watched-contracts` (empty by default); `admin-changes-of-all-contracts: true` ingests every contract. They feed `OwnershipChangeDetector` (renouncement to the zero address, EVM or TRON `T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb`, is INFO). OpenZeppelin `AccessControl` role events (`RoleGranted`/`RoleRevoked`) are not decoded, and `contract.verified`, `contract.age_days`, `contract.outflow_ratio` and `CONTRACT_CALL` events are not produced, so the second step of `rules/ethereum/contract-exploit.yaml` cannot match yet.
- **Stablecoin freezes:** decoded only from the official contracts listed in `StablecoinFreezeSources` (Tether USDT on Ethereum and TRON, Tether USDT0 on Polygon, Circle USDC on Ethereum and Polygon; BSC USDT/USDC are Binance-Peg tokens without an issuer blacklist and TRON USDC was discontinued by Circle). Live ingestion sees freezes in indexed blocks; older ones are covered by the **bounded freeze backfill** (`oci.chains.<network>.freeze-backfill.from-block`, disabled by default; `to-block` default = finalized head, `chunk-size` 2000, `min-chunk-size` 10, `pause` 250ms, `run-on-startup` true; also `POST /api/v1/admin/backfill/freezes`, `keys:admin`). EVM: `eth_getLogs` filtered on the official contracts and the freeze/unfreeze topics, chunk halved on "too many results / range too large" answers; TRON: TronGrid `GET /v1/contracts/{address}/events` per event name (`only_confirmed`, timestamp window of the chunk, 200 per page, at most 25 pages per chunk before halving). Cost: about one query per chunk per network on EVM (+ one `eth_getBlockByHash` per distinct block when the node omits `blockTimestamp`), two `getblockbynum` + at least two event queries (one per event name) per chunk on TRON; the TRON scan needs TronGrid's `/v1` event index (a bare java-tron FullNode does not serve it: the backfill then stops with an error). Only finalized blocks are scanned. Progress and found events are stored (PostgreSQL tables `backfill_progress` / `backfill_freeze_events` in JDBC mode, in memory otherwise): a restart re-applies the stored freezes without RPC and resumes after the last scanned block. Backfilled freezes go through the same label/rescore path as live ones (idempotent, older-than-last-applied ignored) but are **not evaluated by the rule engine** (no alerts for historical freezes). A label file (`oci.labels.files`, category `ISSUER_FROZEN`) remains an alternative for networks without a real provider.

## Option B — from source

```bash
mvn -B verify                                        # backend build + tests (JDK 21+); PostgreSQL tests use Docker (Testcontainers), skipped with a message without it
cd frontend && npm ci && npm test && npm run build   # Node version from frontend/.nvmrc
cd .. && mvn -pl oci-app spring-boot:run             # demo profile, in memory: simulated chains + documented UI key
# with PostgreSQL: SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/oci SPRING_DATASOURCE_USERNAME=oci \
#   SPRING_DATASOURCE_PASSWORD=oci-dev-only OCI_CRYPTO_MASTER_KEY=$(openssl rand -base64 32) mvn -pl oci-app spring-boot:run
cd frontend && npm run start:api                     # console on http://localhost:4200 against the local app
```

## Upgrades

- **Back up before upgrading**: `docker compose exec -T postgres pg_dump -U oci -Fc oci > oci-$(date +%F).dump` (restore with `pg_restore`). Keep `OCI_CRYPTO_MASTER_KEY` and `OCI_SECURITY_API_KEY_PEPPER` with the backup, in your secret store: a dump without the master key cannot decrypt connector secrets, and without the pepper no stored API key authenticates.
- **Flyway migrates the schema automatically at startup** (`oci-app/src/main/resources/db/migration`, `flyway_schema_history` table). Migrations are forward-only: to roll back an upgrade, restore the backup taken before it. A failed migration stops the application before it serves traffic.
- Chain-derived state is rebuilt by re-indexing after the restart; repository rules (`rules/`) that changed are added as new rule versions (enabled flags kept), unchanged ones are left alone, and rules deleted by an operator are not re-imported.
- Read `CHANGELOG.md` for breaking changes; API v1 is additive within the major version.

## Production checklist

See [security-hardening.md](security-hardening.md). Minimum: `OCI_DEMO_MODE=false`, TLS reverse proxy, OIDC configured, `OCI_SECURITY_API_KEY_PEPPER` and `OCI_CRYPTO_MASTER_KEY` from a secret store, a strong `POSTGRES_PASSWORD`, regular database backups, port 8080 bound to a private interface only.
