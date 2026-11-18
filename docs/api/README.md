# API v1 contract

Base path: `/api/v1`. JSON over HTTPS, UTF-8. An OpenAPI 3.1 document is served by the application at `/v3/api-docs` (and Swagger UI at `/swagger-ui.html`) when `OCI_API_DOCS_ENABLED` is on (default: on in demo mode, off otherwise); this page is the human summary and the stability contract.

## Conventions

| Topic | Rule |
|---|---|
| Versioning | URI major version (`/api/v1`). Additive changes (new fields, endpoints, enum values) are non-breaking; clients must ignore unknown fields. |
| Networks | `network` query parameter uses `Network.id()`: `tron`, `ethereum`, `bsc`, `polygon` (`bitcoin`, `solana` planned). |
| Addresses | Accepted in the network's usual form; returned canonical (EVM lowercase hex, TRON base58). |
| Amounts | Always **strings**: `rawAmount` (integer base units) and `amount` (exact decimal). Never JSON numbers for money. |
| Time | RFC 3339 UTC (`2026-11-06T10:00:00Z`). |
| Ids | Opaque strings: UUIDs for alerts and deliveries, prefixed slugs for some resources (`wh-…` connectors, `sig_…` signals, rule ids). |
| Concurrency | `ETag` on rules. **Not enforced yet**: `If-Match` is accepted on `PUT /rules/{id}` but not checked (412 is planned, roadmap phase 1). |

## Authentication

- **Users (SPA)**: today the console sends a token or API key pasted in Settings (kept in the browser's `localStorage`). An OIDC Authorization Code + PKCE login is planned (roadmap phase 1). The API already validates OIDC JWTs when an issuer is configured (`Authorization: Bearer <access_token>`, JWKS, roles from `oci_roles`). Single tenant: there is no tenant claim yet.
- **Machines**: API key `Authorization: Bearer oci_<env>_<prefix>_<secret>` (or `X-API-Key`). Keys have scopes and optional expiry; only a hash is stored.

| Permission | Grants |
|---|---|
| `wallet:read` | wallet analysis, networks, labels read |
| `transaction:read` | wallet transactions, transaction analysis |
| `risk:read` | wallet risk score + history, detection signals |
| `investigation:read` | flow graph, exposure |
| `rules:read` / `rules:write` | rule read, validate, export / create, update, delete, enable, disable, import, test |
| `alerts:read` / `alerts:write` | alert read / acknowledge, status, assign, comment |
| `webhooks:read` / `webhooks:write` | connector listing / connector create, delete, test |
| `labels:report` | submit and list label reports |
| `watchlist:write` | add and remove the organisation's own wallets (reading the watchlist needs `wallet:read`) |
| `investigation:write` | run, re-run and cancel stored traces (they use provider quota and storage) |
| `investigation:review` | accept, reject or ask evidence for the assertions of an investigation (ADR-0022 amendment 3) |
| `keys:admin` | API keys, users, operational (actuator) endpoints |

Roles: `VIEWER` (all `*:read`), `ANALYST` (+ `alerts:write`, `labels:report`, `watchlist:write`, `investigation:write`, `investigation:review`), `RULE_AUTHOR` (+ `rules:write`), `ADMIN` (+ `webhooks:write`, `keys:admin`). API keys are never accepted in URL query parameters (400). A key can only be created with permissions its creator holds.

## Errors — RFC 9457 `application/problem+json`

```json
{
  "type": "https://openchain.io/problems/validation-error",
  "title": "Request validation failed",
  "status": 422,
  "detail": "Rule source is invalid",
  "instance": "/api/v1/rules/validate",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [
    { "pointer": "/spec/window/size", "code": "max_exceeded", "message": "window size must be <= 7d" }
  ]
}
```

| Status | `type` suffix | When |
|---|---|---|
| 400 | `bad-request` | malformed JSON/YAML, invalid parameter |
| 401 | `unauthenticated` | missing/invalid token or key |
| 403 | `forbidden` | missing scope/role, other tenant |
| 404 | `not-found` | unknown resource (also for other tenants' resources) |
| 409 | `conflict` | duplicate name, idempotency key reused with different body |
| 412 | `precondition-failed` | `If-Match` mismatch |
| 422 | `validation-error` | semantic validation (rule compile errors, unsupported network) |
| 429 | `rate-limited` | rate limit exceeded (`Retry-After` set) |
| 502/503 | `upstream-unavailable` | chain provider unavailable and no cached data |

## Pagination (cursor)

List endpoints accept `limit` (1–200, default 50) and `cursor` (opaque). Responses:

```json
{ "items": [ ... ], "nextCursor": "eyJ0cyI6IjIwMjYtMTEtMDZUMTA6MDA6MDBaIiwiaWQiOiIuLi4ifQ", "truncated": false }
```

`nextCursor` is `null` on the last page (mirrors `io.openchain.core.model.Page`). Cursors are opaque; today they are offsets (not stable under inserts). Keyset cursors are planned.

## Idempotency

**Not implemented yet** (roadmap phase 1): the header is accepted and ignored. Planned behaviour: `POST` endpoints that create resources or trigger side effects accept `Idempotency-Key: <uuid>` (required for `POST /rules/import`, `/labels/reports`, `/webhooks`; recommended elsewhere). The first response is stored 24 h per `(tenant, key)`; a replay with the same body returns the stored response with `Idempotent-Replayed: true`; a replay with a different body returns `409`.

## Rate limiting

**Not implemented yet** (roadmap phase 1); the headers below describe the planned contract. Per API key / user (token bucket). Headers on every response (IETF `RateLimit` header fields draft):

```
RateLimit-Limit: 600
RateLimit-Remaining: 597
RateLimit-Reset: 42
RateLimit-Policy: 600;w=60
```

On 429, `Retry-After` (seconds) is set. Expensive endpoints (`/graph`, `/rules/{id}/test`) have dedicated, lower budgets.

## Endpoints

### Wallets and transactions

| Method | Path | Scope | Description |
|---|---|---|---|
| GET | `/wallets/{address}/analysis?network=` | wallet:read | Profile, risk score with contributions, signals, labels, data freshness |
| GET | `/wallets/{address}/transactions?network=&cursor=&limit=` | transaction:read | Transactions, newest first, cursor paginated (`token`/`from`/`to`/`direction` filters: not implemented yet) |
| GET | `/wallets/{address}/risk?network=` | risk:read | Current `RiskScore` + recent history |
| GET | `/wallets/{address}/exposure?network=&depth=1..3` | investigation:read | Exposure to addresses listed in a risky category, qualified like the exposure detector: `direct[]` with exact `amounts` per token and `txHashes`, `indirect[]` with `path`, `traced` (at most this amount, in one token) and `confidence`; `ignored[]` (unverified token, dust: not exposure), `counterparties[]` (exchange, protocol: information), `coverage` (`COMPLETE` for `fromBlock..toBlock` only when history and live indexing join without a hole, else `PARTIAL` with reasons; `segments`, `knownGaps`). Amounts are never added across tokens. `share` is deprecated (sum over all tokens, no unit) |
| GET | `/wallets/{address}/relations?network=` | investigation:read | Possibly related wallets: `COMMON_FUNDER` (same sender within 24 h, not a distributor of more than 20 wallets), `COMMON_SINK` (both send at least half of a token's outflow to the same address), `SHARED_COUNTERPARTIES` (at least 3 common recipients, confidence by overlap). Each relation is `INFERRED` with a `confidence`, `via`, its `eventIds` and an explanation; services (labelled exchange, bridge, protocol, other, or more than 5,000 flows), dust and unverified tokens never link wallets. Not proof of common ownership |
| POST | `/traces` | investigation:write | `{ network, seed, direction: out\|in, depth?, maxNodes?, token?, method?: PROPORTIONAL\|FIFO, from?, to? }` → 202: a stored trace (ADR-0022), queued; poll `GET /traces/{id}`. 429 when the client's budget (3 running, 100 per day) or the queue is full |
| GET | `/traces`, `/traces/{id}`, `/traces/{id}/evidence`, `/traces/{id}/export` | investigation:read | The client's traces: status, request, result, diff with the previous version, `stale` (a frozen flow was retracted by a reorganisation), the frozen flows; export is audited. Another client's trace answers 404 like an unknown id |
| POST | `/traces/{id}/rerun`, `/traces/{id}/cancel` | investigation:write | A new version on the current data (with its diff); cancel a run still pending |
| POST | `/investigations` | investigation:write | `{ network, address }` → 202: runs the deterministic plan `plan@1` (coverage, labels, exposure, traces out and in, patterns, relations, signals) on frozen evidence; same budgets as traces |
| GET | `/investigations`, `/investigations/{id}`, `/investigations/{id}/report` | investigation:read | The client's investigations: typed `assertions` (level, exact parameters, evidence, deterministic id) and a `report` built only from them, every sentence citing its assertion; coverage and limits first. Another client's investigation answers 404 |
| POST | `/investigations/{id}/rerun` | investigation:write | A new version on the current data, with the assertions added and removed |
| POST | `/investigations/{id}/assertions/{assertionId}/reviews` | investigation:review | `{ decision: ACCEPTED\|REJECTED\|NEEDS_EVIDENCE, comment? }` (comment at most 2,000 characters) → 201: a human decision next to the assertion. It never changes the assertion, its level or the report text; append-only (a new decision is a new review). 404 for an assertion that is not in this investigation, 409 while it has not finished, 403 under the four-eyes rule (`OCI_INVESTIGATION_FOUR_EYES`) for its creator |
| GET | `/investigations/{id}/reviews` | investigation:read | `history`: every review of the investigation's versions up to this one, oldest first, each with the version it was taken on; `current`: per assertion of this version, its latest decision, `inherited` when taken on an earlier version (shown, never copied); `decided` / `total` |
| GET | `/simulations/exposure?network&address&category&depth=2` | investigation:read | **SIMULATED** (ADR-0023 amendment 2): which wallets would be exposed if `address` were listed as `category` (a risky category), by the same exposure rules as `/wallets/{a}/exposure`, on the in-memory flows. Nothing is stored or mixed with facts: the hypothetical label exists only for this computation. Carries `level`, `hypothesis`, the coverage of the address, `truncated` (500 wallets examined at most) and a `statement` that never says "nobody" without complete coverage |
| GET | `/ai-summary/providers` | investigation:read | The AI providers this instance offers for summaries (`id`, `label`, `connector`, `model`, `dataLocation`; never keys or URLs). Empty when the feature is off |
| POST | `/investigations/{id}/summaries` | investigation:write | `{ provider }` → 201: an AI summary (ADR-0022 amendment 5) written from the filtered assertions only and checked on the server. `status` ACCEPTED (with `sentences`, each citing its assertions, coverage first when partial), REFUSED or FAILED (with `reasons`; nothing written by the model is returned). 409 when the feature is off or the investigation has not finished, 400 for an unknown provider, 429 beyond the daily cap per client |
| GET | `/investigations/{id}/summaries` | investigation:read | Every summary request of the investigation, oldest first, with provider, model, prompt version, input hash and outcome |
| POST | `/investigations/{id}/rule-proposals` | investigation:read | Rule drafts (`proposal@1`, ADR-0022 amendment 4) built from the investigation's assertions by fixed templates (next hop of a trace, at most 5; common funder; dispersion by the subject; listed counterparty): YAML in the rule format, always `enabled: false`, citing its assertions, each checked (valid, its own examples and tests pass) and dry-run on the in-memory event history (`dryRun.from`/`to` give the window actually covered, which may be short). An assertion rejected by a review gives no draft (`skipped`, with the reason). Nothing is stored (a POST like `POST /rules/test` because it computes a dry run; it needs no write permission); adopting a draft is `POST /rules` (rules:write), which creates it disabled |
| GET | `/transactions/{hash}/analysis?network=` | transaction:read | Decoded transfers, counterparties' risk, signals for this tx, confirmation status |

Example — `GET /api/v1/wallets/TXYZ…/analysis?network=tron`:

```json
{
  "address": { "network": "tron", "value": "TXYZ..." },
  "profile": { "firstSeen": "2025-01-03T08:12:00Z", "lastSeen": "2026-11-06T09:58:00Z", "txCount": 1834, "isContract": false },
  "riskScore": {
    "score": 72, "level": "HIGH", "method": "noisy-or saturating aggregation: score=100*(1-prod_f(1-p_f)), ...", "modelVersion": "oci-risk-1", "computedAt": "2026-11-06T10:00:00Z",
    "contributions": [
      { "factor": "SANCTIONED_EXPOSURE", "signalId": "6f1c...", "source": "ofac-sdn", "weight": 60, "confidence": 0.9,
        "ageSeconds": 86400, "points": 48.6, "explanation": "Received 12,500.00 USDT directly from an address on the OFAC SDN list" }
    ]
  },
  "signals": [
    { "id": "6f1c...", "type": "SANCTIONED_EXPOSURE", "severity": "HIGH", "confidence": 0.9, "evidenceLevel": "CONFIRMED_BY_SOURCE",
      "evidence": [ { "kind": "transfer", "reference": "tron:0xabc...:0", "source": "ofac-sdn", "description": "Direct inbound transfer" } ],
      "detectorId": "exposure@1" }
  ],
  "labels": [],
  "dataFreshness": "FRESH",
  "disclaimer": "Signals indicate risk indicators or anomalies, not wrongdoing."
}
```

#### Plain-language fields (additive)

Every signal and every risk contribution carries a human `title` and a one-sentence `summary` built from the detector's
evidence; the technical material keeps its meaning and its fields (`factor`, `explanation`, `evidence[]`, `detectorId`,
`method`). Clients should show `title` / `summary` first and the technical fields as detail. The wording describes
observed behaviour, never the owner of an address.

| Object | New field | Example |
|---|---|---|
| Signal | `title` | `"Rapid drain"` |
| Signal | `summary` | `"Sent 95% of its USDT balance (95,000 USDT) to 2 addresses within 1 hour."` |
| Contribution | `title` | `"Rapid drain"`, `"Listed as sanctioned"`, `"Frozen by the stablecoin issuer"` |
| Contribution | `summary` | `"Received 20,000 USDT directly from an address listed as sanctioned by demo-sanctions-list."`; contributions that scored nothing say why (`"Not counted again: the same transactions are already counted for this factor."`) |
| RiskScore | `methodSummary` | plain description of the scoring (`method` keeps the formula) |

Contribution as returned by the demo (`GET /wallets/{address}/risk`):

```json
{ "factor": "signal:RAPID_DRAIN", "signalId": "sig_d7b4…", "source": "rapid-drain@1", "weight": 0.3, "confidence": 0.7,
  "ageSeconds": 363, "points": 14.7, "evidenceLevel": "RISK_SIGNAL",
  "explanation": "RAPID_DRAIN MEDIUM RISK_SIGNAL by rapid-drain@1; confidence 0.70, severity x0.70, age 6m, decay 1.000 -> q=0.490; factor weight 0.30, factor p=0.147",
  "title": "Rapid drain",
  "summary": "Sent 95% of its USDT balance (95,000 USDT) to 2 addresses within 1 hour." }
```

Titles by detector / factor: Unusual amount, Possible structuring, Fast forwarding, Dormant wallet reactivated, Rapid drain,
Fan-out to many recipients, Circular flow, Direct exposure to a sanctioned address / a mixer / a scam-reported address /
a flagged address, Indirect exposure to a listed address, Dusting from a listed address, Unlimited token approval,
Contract ownership change, and for labels "Listed as <category>" / "Frozen by the stablecoin issuer".

#### Address history coverage (`history`, additive, ADR-0020)

`GET /wallets/{address}/analysis` returns `history`, the coverage of the on-demand address history. Opening an address
(analysis, risk or transactions endpoint) on a network with a real provider starts a background backfill when the
address is not covered yet; the client polls the analysis while `status` is `PENDING` or `RUNNING`.

| Field | Meaning |
|---|---|
| `status` | `NOT_CONFIGURED` (simulated demo chain, or no history source for the network), `PENDING`, `RUNNING`, `COMPLETE`, `TRUNCATED` (caps reached: older history not loaded), `FAILED` (progress kept, resumes on the next analysis) |
| `sources` | e.g. `["eth_getLogs"]`, `["trongrid"]`, `[]` |
| `coveredFromBlock` / `coveredToBlock` | every event of the address in this block range is loaded (the live indexer covers the blocks after `coveredToBlock`); null when unknown |
| `coveredFromTime` | same lower bound in time when the source gives it (TronGrid) |
| `tokenTransfersCovered`, `approvalsCovered`, `nativeCovered`, `internalCovered` | what the source can see (eth_getLogs: ERC-20 transfers and approvals only; TronGrid: TRC-20 and TRX transfers) |
| `truncated`, `eventsFound`, `updatedAt` | caps reached, events loaded, last progress |
| `message` | plain-language coverage sentence to display |

```json
"history": { "status": "COMPLETE", "sources": ["eth_getLogs"], "coveredFromBlock": 25865009, "coveredToBlock": 26365008,
  "coveredFromTime": null, "tokenTransfersCovered": true, "approvalsCovered": true, "nativeCovered": false,
  "internalCovered": false, "truncated": false, "eventsFound": 37, "updatedAt": "2026-11-07T12:00:00Z",
  "message": "History: ERC-20 transfers and approvals since block 25,865,009 (eth_getLogs, 37 event(s)). Native transfers and internal transfers are not covered: configure an Erigon node (ADR-0020)." }
```

History events go through profiles, detection, scoring and labels like live ones, but rules are not evaluated for them:
history never raises alerts.

### Rules

| Method | Path | Scope | Description |
|---|---|---|---|
| POST | `/rules` | rules:write | Create (body: `{ "source": "<yaml>" }` or JSON rule; `Content-Type: application/yaml` also accepted). Created **disabled**. |
| GET | `/rules?enabled=&tag=&cursor=&limit=` | rules:read | List |
| GET | `/rules/{id}` | rules:read | Current version, metadata, `compiledHash`, `ETag` |
| PUT | `/rules/{id}` | rules:write | New immutable version (`If-Match` required) |
| POST | `/rules/{id}/test` | rules:write | DRY_RUN over historical data: `{ "from", "to", "network", "source"? }` → matches + stats; no side effects |
| POST | `/rules/{id}/enable` | rules:write | Enable current version; **422** when the rule can never fire with the data this instance produces (`unreachableReasons` of the rule, e.g. a field never populated) |
| GET | `/rules/noise?hours=24` | rules:read | Noise report per rule: `total`, `open`, `falsePositive`, `resolved`, `recent` (created in the last `hours`, 1..2160), `falsePositiveRate` among closed alerts (null if none) |
| POST | `/rules/{id}/disable` | rules:write | Disable |
| POST | `/rules/validate` | rules:read | Compile only; 200 `{ valid: true, compiledHash }` or 422 with `errors[]` |
| POST | `/rules/import` | rules:write | Import one or many YAML documents (`---` separated), `Content-Type: application/yaml` |
| GET | `/rules/{id}/export` | rules:read | YAML source of current version (`?version=` for older) |

### Alerts

| Method | Path | Scope | Description |
|---|---|---|---|
| GET | `/alerts?status=&severity=&ruleId=&address=&network=&from=&to=&cursor=&limit=` | alerts:read | List |
| GET | `/alerts/{id}` | alerts:read | Detail: explanation, contributing events, history, comments |
| POST | `/alerts/{id}/acknowledge` | alerts:write | `OPEN → ACKNOWLEDGED` |
| POST | `/alerts/{id}/status` | alerts:write | `{ "status": "IN_PROGRESS\|RESOLVED\|FALSE_POSITIVE\|OPEN", "reason": "..." }` (validated transitions) |
| POST | `/alerts/{id}/assign` | alerts:write | `{ "assigneeId": "uuid" \| null }` |
| POST | `/alerts/{id}/comments` | alerts:write | `{ "body": "markdown text" }` (rendered as plain text/sanitised) |

Rule field `wallet.balance` (see `GET /rules/fields`): on real chains, the provider-reported balance of the sender at
the end of the previous block; **absent** when unavailable (error, rate limit, TRON), so balance conditions do not match.
Simulated demo chains: the demo ledger balance.

Alert fields (additive): `address` (wallet the alert is about), `txHash`, `matchedEvent` (field snapshot of the
triggering event) are now **stored with the alert** (V3 migration) instead of being looked up in the in-memory event
history, so they survive a restart. New: `counterparty` (other side of the triggering transfer), `blockNumber`, and
`evidenceSummary`, one plain-language line saying why the rule fired (the technical detail stays in `evidence`), e.g.
`"TQrq…DoQM sent 92% of its USDT balance within 2 minutes (3 transfers)."` For a grouped alert, the subject is the
latest trigger. Alerts stored before the V3 migration: the migration fills `address` where the grouping key is the
wallet (aggregation / sequence rules grouped by `transaction.from` / `wallet.address`); the rest is filled when the
triggering event is re-indexed, otherwise these fields stay null.

### Webhooks, networks, graph, labels

| Method | Path | Scope | Description |
|---|---|---|---|
| POST | `/webhooks` | webhooks:write | Create webhook connector `{ name, url, events[] }` (https only); response includes the signing `secret` **once** and an `id` (`wh-…`, a slug usable as `actions[].connectorId`) |
| GET | `/webhooks` | webhooks:read | List (secrets never returned) |
| GET | `/networks` | wallet:read | Supported networks, enabled flag, head/finalized block, indexer lag (`indexerLag`, `null` when the chain head is unknown: provider failing; not "no lag"), provider health |
| GET | `/graph/{address}?network=&depth=1..3&token=&from=&to=&limit=&direction=out\|in\|both` | investigation:read | With `direction`: value followed in its direction and time order (trace engine): nodes with `side`, edges per (source, target, token) of the transfers that carried traced value with `eventIds` (at most 50; `count` is the total), `firstTimestamp`, `cycle`; `stops` (address, reason, detail), `status` (`PARTIAL` when a stop comes from missing data or a limit), `asOfBlock`. Without `direction` (deprecated, removed at the next major version): any flow either way, no time order, `truncated` only |
| GET | `/graph/{address}/path?network=&target=&token=&since=&until=&maxHops=1..8` | investigation:read | A path value could have taken (directed, each transfer after the previous one arrived, one token) with its transactions; `outcome` FOUND, NO_PATH (none in the data covered) or LIMIT_REACHED (one may exist beyond) |
| POST | `/graph/verify` `{network, eventIds[≤1000]}` | investigation:read | Which transfers of a displayed graph are no longer held (reorganisation or trimmed working set) |
| GET | `/labels?network=&address=&category=` | wallet:read | Labels with source, evidence level, status |
| GET | `/watchlist` | wallet:read | The organisation's own wallets `[{ id, network, address, name, createdBy, createdAt }]`, oldest first |
| POST | `/watchlist` | watchlist:write | `{ network, address, name? }` → 201 (address validated and canonicalised); 409 when already watched. As soon as the list is not empty, the behavioural pack rules (`fund-splitting`, `suspicious-wallet`, `suspicious-approval`) only consider watched wallets and their direct counterparties (fields `wallet.watchlisted`, `counterparty.watchlisted`, `watchlist.active`) |
| DELETE | `/watchlist/{id}` | watchlist:write | 204; 404 when unknown |
| POST | `/labels/reports` | labels:report | Submit a community report `{ network, address, category, description, evidenceUrls[] }`; enters moderation, never auto-published |

### Administration and SPA helpers

| Method | Path | Permission | Description |
|---|---|---|---|
| POST | `/api-keys` | keys:admin | `{ name, scopes[], expiresAt? \| expiresInDays? }` → key with `secret` **once** |
| GET | `/api-keys` | keys:admin | List (prefix, scopes, expiry, last use, revocation; never secrets) |
| POST | `/api-keys/{id}/revoke` | keys:admin | Revoke (`DELETE /api-keys/{id}` is an alias) |
| GET / PATCH | `/users`, `/users/{id}` | keys:admin | Console users and their role `{ role }` |
| POST | `/admin/backfill/freezes?network=` | keys:admin | Queue the bounded freeze backfill of one network (or of every configured one without `network`) → **202** `{ items: [status] }`; a run already queued/running is not started twice; 404 when the network has no backfill configured (`oci.chains.<network>.freeze-backfill.from-block` + real provider), 409 when none is |
| GET | `/admin/backfill/freezes` | keys:admin | Backfill status per network: `state` (IDLE/QUEUED/RUNNING/COMPLETED/CANCELLED/FAILED), `fromBlock`, `toBlock`, `chunkSize`, `lastScannedBlock`, `freezesFound`, `replayedAtStartup`, `lastResult` (chunks, queries, halvings, finalChunkSize…), `lastError` |
| GET | `/signals?network=&type=&cursor=&limit=` | risk:read | Recent detection signals |
| GET | `/rules/fields` | rules:read | Field catalog usable in rules; `available=false` marks fields the pipeline does not populate yet |
| GET | `/rules/export` | rules:read | All current rule sources, `---` separated |
| POST | `/rules/test` | rules:write | DRY_RUN of an unsaved rule (`source`/`yaml` in the body) |
| DELETE | `/rules/{id}` | rules:write | Delete a rule |
| DELETE | `/webhooks/{id}` | webhooks:write | Delete a connector |
| POST | `/webhooks/{id}/test` | webhooks:write | Signed test delivery → `{ delivered, statusCode, message }` |
| GET | `/labels/reports` | labels:report | Submitted label reports |
| GET | `/dashboard` | wallet:read | Totals, risk distribution, networks, 24 h activity; `alerts` (needs alerts:read) and `recentSignals` (needs risk:read) are `null` otherwise, listed in `omittedSections` |
| GET | `/rules/event-types` | rules:read | Event types with `available` (produced by the current pipeline) |
| GET | `/demo/scenarios`, `/demo/notifications` | wallet:read + alerts:read | **Demo mode only.** Fictitious scenarios A-E and the evidence produced by the real pipeline |

## Webhook payload and signature

```http
POST /your/endpoint HTTP/1.1
Content-Type: application/json
User-Agent: OpenChain-Intelligence-Webhook/0.1
X-OCI-Event: alert.opened
X-OCI-Delivery-Id: 01933c1e-7b7a-7c4e-9a0e-3f5c2c1d9e10
X-OCI-Signature: t=1762423200,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd

{"id":"...","type":"alert.opened","createdAt":"2026-11-06T10:00:00Z","data":{"alert":{...}}}
```

Events: `alert.opened`, `alert.updated`, `alert.retracted`, `alert.reinstated`, `alert.status_changed`, `webhook.test`.

- `alert.retracted`: every valid piece of evidence of the alert left the canonical chain (reorganisation), or, for an
  aggregation rule, the remaining events no longer reach the threshold. The alert is kept; re-check it before acting.
- `alert.reinstated`: an alert announced by `alert.retracted` has valid evidence again (its transactions are back on the
  canonical chain, or a new match was grouped into it). Sent once per such transition, to the same channels.

Alert notifications go to the channels of the rule's actions. The `events` list of a webhook is stored and returned, but
is **not yet used to filter** deliveries: a receiver must accept (or ignore) every event type above, and should ignore
types it does not know.

Signature: `v1 = hex(HMAC_SHA256(secret, t + "." + rawBody))`. Verify over the **raw** request body bytes, compare in constant time, reject if `|now − t| > 300 s`, and deduplicate on `X-OCI-Delivery-Id` (deliveries are at-least-once). During secret rotation the header may contain two `v1=` values; accept if any matches.

### Python

```python
import hmac, hashlib, time

def verify(secret: str, raw_body: bytes, header: str, tolerance: int = 300) -> bool:
    parts = [p.split("=", 1) for p in header.split(",")]
    t = next(v for k, v in parts if k == "t")
    sigs = [v for k, v in parts if k == "v1"]
    if abs(time.time() - int(t)) > tolerance:
        return False
    expected = hmac.new(secret.encode(), f"{t}.".encode() + raw_body, hashlib.sha256).hexdigest()
    return any(hmac.compare_digest(expected, s) for s in sigs)
```

### Node.js

```javascript
const crypto = require("node:crypto");

function verify(secret, rawBody /* Buffer */, header, toleranceSec = 300) {
  const parts = header.split(",").map((p) => p.split("=", 2));
  const t = parts.find(([k]) => k === "t")?.[1];
  const sigs = parts.filter(([k]) => k === "v1").map(([, v]) => v);
  if (!t || Math.abs(Date.now() / 1000 - Number(t)) > toleranceSec) return false;
  const expected = crypto.createHmac("sha256", secret)
    .update(Buffer.concat([Buffer.from(`${t}.`), rawBody]))
    .digest("hex");
  return sigs.some((s) => s.length === expected.length &&
    crypto.timingSafeEqual(Buffer.from(s, "hex"), Buffer.from(expected, "hex")));
}
```

### Java

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public final class OciWebhookVerifier {
    public static boolean verify(String secret, byte[] rawBody, String header, long toleranceSec) throws Exception {
        String t = null;
        java.util.List<String> sigs = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) t = kv[1];
            else if (kv[0].equals("v1")) sigs.add(kv[1]);
        }
        if (t == null || Math.abs(Instant.now().getEpochSecond() - Long.parseLong(t)) > toleranceSec) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
        byte[] expected = mac.doFinal(rawBody);
        for (String s : sigs) {
            byte[] given;
            try { given = HexFormat.of().parseHex(s); } catch (IllegalArgumentException e) { continue; }
            if (MessageDigest.isEqual(expected, given)) return true;
        }
        return false;
    }
}
```
