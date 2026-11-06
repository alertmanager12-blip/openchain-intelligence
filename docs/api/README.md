# API v1 contract

Base path: `/api/v1`. JSON over HTTPS, UTF-8. An OpenAPI 3.1 document is served by the application at `/v3/api-docs` (and Swagger UI at `/swagger-ui.html` when enabled); this page is the human summary and the stability contract.

## Conventions

| Topic | Rule |
|---|---|
| Versioning | URI major version (`/api/v1`). Additive changes (new fields, endpoints, enum values) are non-breaking; clients must ignore unknown fields. |
| Networks | `network` query parameter uses `Network.id()`: `tron`, `ethereum`, `bsc`, `polygon` (`bitcoin`, `solana` planned). |
| Addresses | Accepted in the network's usual form; returned canonical (EVM lowercase hex, TRON base58). |
| Amounts | Always **strings**: `rawAmount` (integer base units) and `amount` (exact decimal). Never JSON numbers for money. |
| Time | RFC 3339 UTC (`2026-11-06T10:00:00Z`). |
| Ids | UUID strings. |
| Concurrency | `ETag` on mutable resources; `PUT` requires `If-Match` (412 on mismatch). |

## Authentication

- **Users (SPA)**: OIDC Authorization Code + PKCE with your IdP; the SPA sends `Authorization: Bearer <access_token>` (JWT validated against the IdP JWKS; tenant from claim `oci_tenant`, roles from `oci_roles` or the IdP role mapping).
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
| `keys:admin` | API keys, users, operational (actuator) endpoints |

Roles: `VIEWER` (all `*:read`), `ANALYST` (+ `alerts:write`, `labels:report`), `RULE_AUTHOR` (+ `rules:write`), `ADMIN` (+ `webhooks:write`, `keys:admin`). API keys are never accepted in URL query parameters (400). A key can only be created with permissions its creator holds.

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

`nextCursor` is `null` on the last page (mirrors `io.openchain.core.model.Page`). Cursors are stable under inserts (keyset on `(ts, id)`), expire after 24 h.

## Idempotency

`POST` endpoints that create resources or trigger side effects accept `Idempotency-Key: <uuid>` (required for `POST /rules/import`, `/labels/reports`, `/webhooks`; recommended elsewhere). The first response is stored 24 h per `(tenant, key)`; a replay with the same body returns the stored response with `Idempotent-Replayed: true`; a replay with a different body returns `409`.

## Rate limiting

Per API key / user and per tenant (token bucket). Headers on every response (IETF `RateLimit` header fields draft):

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
| GET | `/wallets/{address}/exposure?network=&depth=1..3` | investigation:read | Direct/indirect exposure to labelled categories (amount and share, by hop) |
| GET | `/transactions/{hash}/analysis?network=` | transaction:read | Decoded transfers, counterparties' risk, signals for this tx, confirmation status |

Example — `GET /api/v1/wallets/TXYZ…/analysis?network=tron`:

```json
{
  "address": { "network": "tron", "value": "TXYZ..." },
  "profile": { "firstSeen": "2025-01-03T08:12:00Z", "lastSeen": "2026-11-06T09:58:00Z", "txCount": 1834, "isContract": false },
  "riskScore": {
    "score": 72, "level": "HIGH", "method": "saturating-sum", "modelVersion": "risk@1", "computedAt": "2026-11-06T10:00:00Z",
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

### Rules

| Method | Path | Scope | Description |
|---|---|---|---|
| POST | `/rules` | rules:write | Create (body: `{ "source": "<yaml>" }` or JSON rule; `Content-Type: application/yaml` also accepted). Created **disabled**. |
| GET | `/rules?enabled=&tag=&cursor=&limit=` | rules:read | List |
| GET | `/rules/{id}` | rules:read | Current version, metadata, `compiledHash`, `ETag` |
| PUT | `/rules/{id}` | rules:write | New immutable version (`If-Match` required) |
| POST | `/rules/{id}/test` | rules:write | DRY_RUN over historical data: `{ "from", "to", "network", "source"? }` → matches + stats; no side effects |
| POST | `/rules/{id}/enable` | rules:write | Enable current version |
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

### Webhooks, networks, graph, labels

| Method | Path | Scope | Description |
|---|---|---|---|
| POST | `/webhooks` | webhooks:write | Create webhook connector `{ name, url, events[] }` (https only); response includes the signing `secret` **once** and an `id` (`wh-…`, a slug usable as `actions[].connectorId`) |
| GET | `/webhooks` | webhooks:read | List (secrets never returned) |
| GET | `/networks` | wallet:read | Supported networks, enabled flag, head/finalized block, indexer lag, provider health |
| GET | `/graph/{address}?network=&depth=1..3&token=&from=&to=&limit=` | investigation:read | Nodes (address, labels, risk level) + aggregated edges (count, total amount); `truncated` flag when fan-out caps hit |
| GET | `/labels?network=&address=&category=` | wallet:read | Labels with source, evidence level, status |
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

Events: `alert.opened`, `alert.updated`, `alert.retracted`, `alert.status_changed`, `webhook.test`.

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
