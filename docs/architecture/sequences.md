# Sequence diagrams

## 1. Indexing with reorg

```mermaid
sequenceDiagram
  autonumber
  participant S as Scheduler (lease per network)
  participant I as Indexer
  participant R as Provider registry
  participant P1 as Provider A (primary)
  participant P2 as Provider B (secondary)
  participant DB as PostgreSQL
  participant B as Event bus

  S->>I: tick(network=TRON)
  I->>DB: SELECT checkpoint FOR UPDATE (last=N, hash=hN)
  I->>R: getNetworkStatus()
  R->>P1: head?
  P1-->>R: 429 Too Many Requests (Retry-After: 2)
  R->>R: retryable → circuit A half-open, fallback
  R->>P2: head?
  P2-->>R: head = N+3
  I->>R: getBlock(N+1)
  R-->>I: BlockRef(N+1, parent=h'N)
  alt parentHash == stored hash(N)
    I->>R: getTokenTransfers(N+1, N+1)
    R-->>I: [TokenTransfer…]
    I->>DB: BEGIN · upsert blocks, token_transfers ON CONFLICT(event_id) DO NOTHING ·<br/>update checkpoint N+1 · COMMIT
    I->>B: publish ChainEvent(removed=false) per transfer
  else reorg detected (h'N ≠ hN)
    loop walk back until common ancestor A (≤ maxReorgDepth)
      I->>R: getBlock(k)
      I->>DB: compare stored hash(k)
    end
    I->>DB: BEGIN · mark orphaned blocks + transfers (k > A) removed · checkpoint := A · COMMIT
    I->>B: publish ChainEvent(removed=true) for each orphaned event
    Note over B: rule engine retracts window contributions,<br/>alerts → RETRACTED if no longer satisfied
    I->>I: re-index A+1 … on canonical branch (same eventIds re-applied idempotently)
  end
  opt reorg depth > maxReorgDepth
    I->>DB: insert operational alert, pause network indexer
  end
```

## 2. Rule evaluation with sliding window, dedup and cooldown

Rule: *"≥ 5 outgoing USDT transfers totalling ≥ 50 000 within 1 h from the same address"*, dedup by `transaction.from`, cooldown 6 h.

```mermaid
sequenceDiagram
  autonumber
  participant B as Event bus
  participant E as Rule evaluator
  participant W as Window store
  participant D as Deduplicator
  participant A as Alert service
  participant DB as PostgreSQL

  B->>E: ChainEvent e (eventId, eventTime=t, removed=false)
  E->>E: seen(eventId)? → skip duplicates
  E->>E: filter (network, token=USDT, direction) — compiled predicate
  E->>W: add(rule@v3, groupKey=from, e.eventId, amount, t)
  W->>W: evict contributions with time < watermark - 1h<br/>(watermark = max eventTime - allowedLateness)
  W-->>E: aggregate {count=5, sum=52 000, first=t0, last=t}
  E->>E: having count>=5 AND sum>=50000 → match
  E->>D: RuleMatch(rule@v3, groupKey, window[t0,t], eventIds)
  D->>DB: SELECT alert WHERE dedup_key=hash(rule, groupKey) AND status open
  alt open alert exists and within cooldown (6h since last notification)
    D->>A: appendOccurrence(alert, eventIds) — no new notification
    A->>DB: insert alert_events(OCCURRENCE_ADDED)
  else no open alert or cooldown elapsed
    D->>A: open(match)
    A->>DB: BEGIN · insert alerts, alert_events(OPENED),<br/>insert deliveries (one per connector) · COMMIT
  end
  Note over E,W: Late event (t < watermark - window - lateness) → counted in<br/>oci_rule_late_events_total and dropped (never reopens closed windows)
```

## 3. Notification delivery with retry and HMAC

```mermaid
sequenceDiagram
  autonumber
  participant DW as Delivery worker
  participant DB as PostgreSQL (deliveries)
  participant G as SSRF guard
  participant T as Customer webhook

  loop every 1s
    DW->>DB: SELECT … WHERE status IN (PENDING, RETRY) AND next_attempt_at<=now()<br/>FOR UPDATE SKIP LOCKED LIMIT 50
  end
  DW->>DB: decrypt connector secret (AES-GCM, key_id)
  DW->>DW: body = canonical JSON · t = now<br/>sig = HMAC_SHA256(secret, t + "." + body)
  DW->>G: resolve(host) → IPs
  G-->>DW: reject if loopback/private/link-local/metadata · else pinned IP
  DW->>T: POST body<br/>X-OCI-Signature: t=…,v1=…<br/>X-OCI-Delivery-Id, Idempotency-Key<br/>(timeout 5s, no redirects)
  alt 2xx
    T-->>DW: 200
    DW->>DB: status=DELIVERED, attempts+1
  else 408/429/5xx/timeout
    T-->>DW: 503
    DW->>DB: status=RETRY, next_attempt_at = now + min(2^n·10s, 1h) ± jitter
  else other 4xx
    T-->>DW: 400
    DW->>DB: status=FAILED (no retry)
  end
  opt attempts > maxAttempts (default 12, ~24h)
    DW->>DB: status=DEAD (DLQ), alert_events(DELIVERY_FAILED)
  end
  Note over T: Receiver verifies HMAC with constant-time compare,<br/>rejects |now - t| > 300s, dedups on X-OCI-Delivery-Id
```

## 4. Rule test (DRY_RUN)

```mermaid
sequenceDiagram
  autonumber
  actor U as Rule author
  participant FE as Frontend
  participant API as API
  participant C as Rule compiler
  participant E as Evaluator (sandboxed instance)
  participant DB as PostgreSQL

  U->>FE: edit YAML, click "Test"
  FE->>API: POST /api/v1/rules/{id}/test {source?, from, to, network, limit}
  API->>API: authz RULE_AUTHOR, quota check
  API->>C: compile(source)
  alt invalid
    C-->>API: errors[{path, message}]
    API-->>FE: 422 problem+json (errors)
  else valid
    C-->>API: CompiledRule(hash)
    API->>DB: stream historical token_transfers in [from,to] (bounded: ≤ 7d, ≤ 100k events)
    loop replay in eventTime order
      API->>E: evaluate(event) — fresh window/dedup state, mode=DRY_RUN
    end
    E-->>API: matches[], stats{events, matched, suppressedByDedup, evaluationMs}
    Note over E: DRY_RUN never writes alerts, never creates deliveries,<br/>never touches production window state
    API-->>FE: 200 {matches, stats, truncated}
  end
```

## 5. Wallet analysis API

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant API as API
  participant Q as Analysis service
  participant DB as PostgreSQL
  participant R as Provider registry
  participant I as Intelligence (signals + scoring)

  C->>API: GET /api/v1/wallets/{address}/analysis?network=tron
  API->>API: authn (JWT or API key), scope wallets:read, rate limit
  API->>API: validate + canonicalise Address(network, value)
  API->>Q: analyse(address)
  Q->>DB: wallet_profiles, recent token_transfers, signals, latest risk_score, labels
  alt profile stale or missing
    Q->>R: getAddressTransactions(address, cursor, 200) (bounded pages)
    alt provider failure
      R-->>Q: ProviderException(retryable)
      Q-->>Q: degrade: use cached data, set dataFreshness=STALE
    else ok
      R-->>Q: Page<ChainTransaction>
      Q->>DB: upsert token_transfers (idempotent on event_id)
    end
    Q->>I: detect + score(address)
    I-->>Q: Signals + RiskScore (contributions)
    Q->>DB: insert signals, risk_scores (history), upsert wallet_profiles
  end
  Q-->>API: WalletAnalysis
  API-->>C: 200 {address, profile, riskScore{score, level, contributions}, signals[evidenceLevel], labels[source], dataFreshness}
```
