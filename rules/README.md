# OpenChain community rules

Declarative monitoring rules for the OpenChain Intelligence alerting engine (`oci-alerting`).
Format: **`openchain.io/rule/v1`** — JSON Schema in
[`oci-alerting/src/main/resources/schema/rule-v1.schema.json`](../oci-alerting/src/main/resources/schema/rule-v1.schema.json).

Rules are **pure data**. There is no expression language, no scripting and no reflection: every rule is
validated against a whitelist of fields (the *field catalog*) and compiled into an immutable predicate tree.

## Layout

```
rules/
  tron/        large-usdt-transfer.yaml, suspicious-wallet.yaml
  ethereum/    contract-exploit.yaml, suspicious-approval.yaml
  generic/     wallet-draining.yaml, fund-splitting.yaml, issuer-freeze-counterparty.yaml, ...
```

* One rule per file, file name = `<id>.yaml`.
* A network folder (`tron/`, `ethereum/`, ...) requires `scope.networks` to contain that network; `generic/`
  rules are network-agnostic.
* Every rule must ship `documentation`, at least one `example` and one `test`. CI (`RulesRepositoryTest`)
  loads every file, validates it and runs its embedded examples and tests.

## Anatomy

```yaml
apiVersion: openchain.io/rule/v1        # mandatory, exact
id: fund-splitting                      # slug, unique in the repo
version: 1.0.0                          # semver; bump on any behaviour change
name: Fund splitting
author: openchain-community
enabled: true
scope:                                  # empty / missing = any
  networks: [tron, ethereum]
  eventTypes: [TOKEN_TRANSFER]
conditions:                             # recursive group
  logic: AND                            # AND | OR | NOT (NOT = exactly one child)
  rules:                                # leaves
    - { field: transaction.amount, operator: GREATER_THAN, value: "0" }
  groups:
    - logic: NOT
      rules:
        - { field: transaction.to, operator: IN, value: ["T..."], ignoreCase: false }
aggregation:                            # optional: sliding EVENT-TIME window
  window: 3m                            # or {duration: 3, unit: MINUTES} or {preset: 3m}
  groupBy: [transaction.from]           # 0..3 catalog fields
  function: DISTINCT_COUNT              # COUNT | SUM | DISTINCT_COUNT | MAX | RATIO_OF_BALANCE
  field: transaction.to
  operator: GREATER_THAN                # GREATER_THAN | GREATER_THAN_OR_EQUAL
  threshold: 10
sequence:                               # optional (exclusive with aggregation): A then B by key within T
  key: contract.address
  within: 30m
  steps: [ { name: a, conditions: {...} }, { name: b, conditions: {...} } ]
severity: MEDIUM                        # INFO | LOW | MEDIUM | HIGH | CRITICAL
evidenceLevel: ANOMALY                  # ANOMALY | RISK_SIGNAL | CONFIRMED_BY_SOURCE
cooldown: 15m                           # repeated triggers with the same dedup key are grouped
dedupKey: "{{rule.id}}:{{group.key}}"   # placeholders only: rule.id, rule.version, group.key, network, event.<field>
actions:
  - { type: WEBHOOK, connectorId: default-webhook, params: {} }
documentation: |
  Why this pattern matters, known false positives, required enrichments.
expectedConfidence: 0.4
examples:                               # single events vs the stateless filter (scope + conditions)
  - name: ...
    expect: MATCH                       # or NO_MATCH
    event: { type: TOKEN_TRANSFER, network: tron, fields: { transaction.amount: "10" } }
tests:                                  # event streams replayed through a fresh engine
  - name: 11 recipients in 100s
    events:
      - repeat: 11                      # generates 11 events
        every: 10s                      # spaced by 10s from offset (base 2026-01-01T00:00:00Z)
        fields: { transaction.to: "TRecipient{{i}}" }   # {{i}} = repeat index
      - eventId: test-0-0-3
        removed: true                   # reorg retraction of a previous event
    expectTriggers: 1                   # raw engine triggers
    expectAlerts: 1                     # optional: alerts after cooldown grouping
```

## Operators

| Operator | Types | Value |
|---|---|---|
| `EQUALS`, `NOT_EQUALS` | all | scalar |
| `GREATER_THAN(_OR_EQUAL)`, `LESS_THAN(_OR_EQUAL)` | DECIMAL, INTEGER, TIMESTAMP | scalar |
| `BETWEEN` | DECIMAL, INTEGER, TIMESTAMP | `[low, high]` inclusive |
| `IN`, `NOT_IN` | STRING, DECIMAL, INTEGER | array (max 1000) |
| `CONTAINS`, `STARTS_WITH` | STRING | string (`ignoreCase` supported) |
| `MATCHES` | STRING | regex, whole value must match; max 256 chars; inputs > 1024 chars never match; backreferences, nested quantifiers `(a+)+` and ambiguous alternations `(a\|ab)*` are rejected; each evaluation has a step budget |
| `EXISTS` | all | optional boolean (default `true`) |

Semantics: an **absent field never matches** (except `EXISTS: false`). Numbers are compared as exact decimals
(`"1000000.00"` equals `1000000`); quote large or fractional values. EVM addresses are lowercase in events: use
lowercase literals or `ignoreCase: true`. Binary floating point is never used for amounts.

## Windows, lateness, reorgs

* Windows use **event time** (`ChainEvent.eventTime`), are sliding and cover `(t - W, t]`. Range 1s..7d;
  presets `1m`, `5m`, `15m`, `1h`, `24h`.
* Events up to the engine's *allowed lateness* (default 2 min behind the newest event of the network) are
  accounted in their correct window; older events are dropped and counted (`events.late.dropped`).
* Duplicate event ids are ignored. An event with `removed: true` (chain reorg) retracts its contribution from
  every window and sequence; alerts that referenced it get an annotation.
* State is bounded: at most 10,000 group keys per rule (LRU eviction, counted in `groupkey.evicted`).
* `RATIO_OF_BALANCE` = sum(`field`) in the window / `wallet.balance` (balance **before** the transfer) of the
  earliest event in the window. Zero/missing balance never triggers.

## Evidence levels — wording matters

Rules describe **behaviour**, never people. Never name a rule or write documentation that labels an address as
criminal. Use `ANOMALY` for statistical outliers, `RISK_SIGNAL` for patterns associated with risk, and
`CONFIRMED_BY_SOURCE` only when the rule tests a source-backed field (e.g. `wallet.sanctioned` from an official
list) — the validator enforces this. Every notification carries a disclaimer.

## Field catalog (excerpt)

`network`, `event.type`, `event.block_number`, `event.time`, `transaction.{hash,from,to,amount,amount_usd,fee,
token.symbol,token.contract,token.decimals,method,success,contract_interaction}`, `wallet.{address,balance,
risk_score,risk_level,age_days,tx_count,labels,watchlisted,sanctioned*}`, `counterparty.{address,risk_score,labels,
is_contract,age_days,sanctioned*}`, `contract.{address,event,method,new_owner,verified,age_days,outflow_ratio}`,
`approval.{owner,spender,amount,unlimited,spender_is_contract,spender_age_days,spender_verified}`,
`signal.{type,severity,confidence,evidence_level}`, `internal.{hop,origin_chain}`, `alert.{id,rule_id,severity,
group_key}` (`*` = source-backed). The authoritative list is `FieldCatalog.defaults()`; deployments may extend it.

## Watchlist scope

`fund-splitting`, `suspicious-wallet` and `suspicious-approval` describe behaviours that are common on a busy chain
(exchanges, routers, payout wallets). They carry this group:

```yaml
    - logic: OR
      rules:
        - { field: wallet.watchlisted, operator: EQUALS, value: true }
      groups:
        - logic: NOT
          rules:
            - { field: watchlist.active, operator: EQUALS, value: true }
```

While the operator watchlist (`/api/v1/watchlist`, console page *Watchlist*) is empty, `watchlist.active` is false and the
rule covers the whole chain; once the organisation's own wallets are listed, only those wallets (and, for
`suspicious-wallet`, transfers to them) are considered. `NOT(active == true)` rather than `active == false` keeps
events without the field (older tests, other sources) in scope.

## Your notification routing survives rule pack updates

Rules of this pack ship with `actions: [{type: WEBHOOK, connectorId: default-webhook}]`. When an operator changes the
**actions** of a pack rule through the API (`PUT /api/v1/rules/{id}`, e.g. to route it to a Telegram connector), the
new actions are recorded (`rules.actions_override`) and re-applied to every newer version of the pack file loaded at
start-up: thresholds and conditions come from the pack, the routing stays yours. Changing anything else than `actions`
records nothing. The merged version is recognised at the next start, so restarts do not create versions.

## Testing a rule locally

```java
RuleLoader loader = new RuleLoader();
CompiledRule rule = loader.load(Path.of("rules/generic/fund-splitting.yaml"));
RuleTester tester = new RuleTester(loader);
tester.runEmbeddedTests(rule);                                  // examples + tests
tester.dryRun(rule, historicalEvents, DryRunOptions.defaults()); // no notification is ever sent
```

The dry run reports match counts, a sample of matched event ids, estimated triggers/alerts per day and
permissiveness warnings (`PERMISSIVE_MATCH_RATIO`, `NO_NUMERIC_THRESHOLD`, `HIGH_ALERT_VOLUME`).
