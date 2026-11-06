# Writing rules

Rules are YAML (or JSON) documents of schema `openchain.io/rule/v1` (canonical reference: [rules/README.md](../../rules/README.md)). They are **data**: OCI never executes code from a rule. Reference: [rule-engine.md](../architecture/rule-engine.md). The console's Alert Builder produces exactly this format.

## Anatomy

```yaml
apiVersion: openchain.io/rule/v1
id: large-usdt-to-new-counterparty
version: 1.0.0
name: Large USDT transfer to a new counterparty
description: Single USDT transfer >= 100k to an address first seen less than a day ago
author: your-team
enabled: true
tags: [stablecoin]
scope:
  networks: [tron, ethereum]
  eventTypes: [TOKEN_TRANSFER]
conditions:
  logic: AND
  rules:
    - { field: transaction.token.symbol, operator: EQUALS, value: USDT }
    - { field: transaction.amount, operator: GREATER_THAN_OR_EQUAL, value: "100000" }
    - { field: counterparty.age_days, operator: LESS_THAN, value: 1 }
severity: MEDIUM
cooldown: 12h
dedupKey: "{{rule.id}}:{{event.transaction.to}}"
actions:
  - { type: WEBHOOK, connectorId: compliance-webhook }
```

- `conditions` filters single events (`logic: AND | OR | NOT`, nested `groups`). Without `aggregation`, every matching event is a match.
- `aggregation` aggregates matching events per `groupBy` over a sliding window in **event time** (one function per rule).
- `dedupKey` decides which matches are grouped into one open alert; `cooldown` how long they keep being grouped (and not re-notified).
- Every rule creates an alert when it matches; `actions` add notifications through operator-configured connectors.

## Fields

Common fields of `ChainEvent.fields` (the full catalogue, with an `available` flag for fields the pipeline populates today, is served by `GET /api/v1/rules/fields`):

| Field | Type | Example |
|---|---|---|
| `transaction.hash` | string | `0xabc…` |
| `transaction.from`, `transaction.to` | address string | canonical form |
| `transaction.amount` | decimal | `"12500.000000"` |
| `transaction.amount_usd` | decimal | not populated yet (no price feed) |
| `transaction.token.symbol`, `transaction.token.contract` | string | `USDT` |
| `wallet.risk_score`, `counterparty.risk_score` | integer 0–100 | enriched transfers (`RISK_SCORE_CHANGED` events are not produced yet) |
| `wallet.labels`, `counterparty.labels` | comma-separated category ids | `sanctioned,exchange` (use `CONTAINS`) |
| `signal.type`, `signal.evidence_level` | string | on `DETECTION_SIGNAL` events |

**Numbers**: write decimals as strings (`"50000.5"`) to keep them exact. Integers may be plain.

## Recipes

### Watch specific addresses

```yaml
apiVersion: openchain.io/rule/v1
id: treasury-wallet-activity
version: 1.0.0
name: Treasury wallet activity
author: your-team
scope: { networks: [tron], eventTypes: [TOKEN_TRANSFER] }
conditions:
  logic: OR
  rules:
    - { field: transaction.from, operator: IN, value: [TJDENsfBJs4RFETt1X1W8wMDc8M5XnJhCe] }
    - { field: transaction.to, operator: IN, value: [TJDENsfBJs4RFETt1X1W8wMDc8M5XnJhCe] }
severity: INFO
```

Named, tenant-managed watchlists (`in_list`) are not part of v1: list the addresses literally (<= 1 000 values).

### Large outflow burst (balance-agnostic)

```yaml
apiVersion: openchain.io/rule/v1
id: outflow-burst-30m
version: 1.0.0
name: Outflow burst over 30 minutes
author: your-team
scope: { eventTypes: [TOKEN_TRANSFER] }
conditions:
  logic: AND
  rules:
    - { field: transaction.amount, operator: GREATER_THAN, value: "0" }
aggregation:
  window: 30m
  groupBy: [transaction.from]
  function: SUM
  field: transaction.amount
  operator: GREATER_THAN_OR_EQUAL
  threshold: "250000"
severity: MEDIUM
```

For a balance-aware version see `rules/generic/wallet-draining.yaml` (`RATIO_OF_BALANCE`).

### React to high-risk counterparties

```yaml
apiVersion: openchain.io/rule/v1
id: payment-to-high-risk-counterparty
version: 1.0.0
name: Payment to a high-risk counterparty
author: your-team
scope: { eventTypes: [TOKEN_TRANSFER] }
conditions:
  logic: AND
  rules:
    - { field: counterparty.risk_score, operator: GREATER_THAN_OR_EQUAL, value: 65 }
severity: HIGH
evidenceLevel: RISK_SIGNAL
dedupKey: "{{rule.id}}:{{event.transaction.from}}"
```

Transfers are enriched with the current risk score of both sides; dedicated `RISK_SCORE_CHANGED` events are not produced yet.

## Workflow

1. **Validate**: `POST /api/v1/rules/validate` — compile errors come with JSON pointers (`/aggregation/window`).
2. **Test**: `POST /api/v1/rules/{id}/test` with a time range — DRY_RUN replays history, shows matches and how many were suppressed by dedup. No side effects.
3. **Enable**: `POST /api/v1/rules/{id}/enable`. Rules are created disabled.
4. **Tune**: alerts marked `FALSE_POSITIVE` are counted per rule; raise thresholds or add filters.
5. **Version**: every `PUT` creates an immutable version; alerts reference the exact version and `compiledHash`.

## Confirmations

Rules see confirmed events only: the indexer emits a transfer once its block has the network's confirmation depth. A per-rule `confirmations` setting is not part of v1. Events retracted by a reorg reach the engine as `removed=true`; alerts that used them get an annotation.

## Limits

Max 256 KiB per rule, 50 conditions, depth 5, 1 000 values in `IN`, 3 `groupBy` fields, window between 1 s and 7 d, cooldown <= 7 d, 10 actions. `MATCHES` regexes are bounded (256 chars, step budget). See `RuleLimits`.

## Wording

Rules describe **observable behaviour**. Use neutral names and descriptions ("large outflow", "exposure to sanctioned list") — not accusations ("scammer wallet"). Alert titles are shown to analysts and may be forwarded to third parties.

## Contributing rules

Shareable rules live in `rules/` packs in the repository; see [CONTRIBUTING.md](../../CONTRIBUTING.md#contributing-rules).
