# Writing rules

Rules are YAML (or JSON) documents of schema `oci.rules/v1`. They are **data**: OCI never executes code from a rule. Reference: [rule-engine.md](../architecture/rule-engine.md).

## Anatomy

```yaml
apiVersion: oci.rules/v1
kind: Rule
metadata:
  name: large-usdt-to-new-counterparty
  description: Single USDT transfer >= 100k to an address first seen less than 24h ago
  severity: MEDIUM
  tags: [stablecoin]
spec:
  networks: [tron, ethereum]
  eventTypes: [TOKEN_TRANSFER]
  where:
    all:
      - { field: transaction.token.symbol, op: eq, value: USDT }
      - { field: transaction.amount, op: gte, value: "100000" }
      - { field: counterparty.age_seconds, op: lt, value: 86400 }
  dedup:
    key: [rule, transaction.to]
    cooldown: 12h
  actions:
    - connector: compliance-webhook
```

- `where` filters single events. Without `window`, every matching event is a match.
- `window` + `having` aggregate events per `groupBy` over a sliding (or tumbling) window in **event time**.
- `dedup` controls how matches are merged into one active alert, and `cooldown` how often you are notified.

## Fields

Common fields of `ChainEvent.fields` (the full catalogue is returned by `POST /api/v1/rules/validate` errors and documented in the API):

| Field | Type | Example |
|---|---|---|
| `transaction.hash` | string | `0xabc…` |
| `transaction.from`, `transaction.to` | address string | canonical form |
| `transaction.amount` | decimal | `"12500.000000"` |
| `transaction.amount_usd` | decimal (optional) | skipped when no price |
| `transaction.token.symbol`, `transaction.token.contract` | string | `USDT` |
| `wallet.risk_score` | integer 0–100 | on `RISK_SCORE_CHANGED` events and enriched transfers |
| `wallet.labels` | set of categories | `SANCTIONED`, `EXCHANGE` |
| `signal.type`, `signal.evidence_level` | string | on `DETECTION_SIGNAL` events |

**Numbers**: write decimals as strings (`"50000.5"`) to keep them exact. Integers may be plain.

## Recipes

### Watch specific addresses (watchlist)

```yaml
where:
  any:
    - { field: transaction.from, op: in_list, value: treasury-wallets }
    - { field: transaction.to,   op: in_list, value: treasury-wallets }
```

### Rapid drain (balance-agnostic approximation)

```yaml
window: { type: sliding, size: 30m, groupBy: [transaction.from] }
having:
  all:
    - { aggregate: sum, field: transaction.amount, op: gte, value: "250000" }
    - { aggregate: distinct_count, field: transaction.to, op: gte, value: 3 }
```

### React to risk changes

```yaml
eventTypes: [RISK_SCORE_CHANGED]
where:
  all:
    - { field: wallet.risk_score, op: gte, value: 65 }
    - { field: wallet.address, op: in_list, value: customer-deposit-addresses }
```

## Workflow

1. **Validate**: `POST /api/v1/rules/validate` — compile errors come with JSON pointers (`/spec/window/size`).
2. **Test**: `POST /api/v1/rules/{id}/test` with a time range — DRY_RUN replays history, shows matches and how many were suppressed by dedup. No side effects.
3. **Enable**: `POST /api/v1/rules/{id}/enable`. Rules are created disabled.
4. **Tune**: alerts marked `FALSE_POSITIVE` are counted per rule; raise thresholds or add filters.
5. **Version**: every `PUT` creates an immutable version; alerts reference the exact version and `compiledHash`.

## Confirmations

`confirmations: default` (recommended) evaluates confirmed events only. `confirmations: 0` evaluates head events: faster, but alerts are `provisional` and may be `RETRACTED` after a reorg.

## Limits

Max 64 KiB per rule, 100 predicate nodes, depth 5, 1 000 values in `in`, 3 `groupBy` fields, window ≤ 7 d, 5 connectors. No regex in v1.

## Wording

Rules describe **observable behaviour**. Use neutral names and descriptions ("large outflow", "exposure to sanctioned list") — not accusations ("scammer wallet"). Alert titles are shown to analysts and may be forwarded to third parties.

## Contributing rules

Shareable rules live in `rules/` packs in the repository; see [CONTRIBUTING.md](../../CONTRIBUTING.md#contributing-rules).
