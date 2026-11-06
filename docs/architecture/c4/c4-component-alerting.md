# C4 — Level 3: Components of the alerting pipeline

Source of truth: [`workspace.dsl`](workspace.dsl) (view `AlertingComponents`). Implemented in module `oci-alerting`, wired by `oci-app`. Detailed behaviour: [rule-engine.md](../rule-engine.md).

```mermaid
C4Component
  title Worker — alerting components (oci-alerting)

  ContainerDb(db, "PostgreSQL", "rules, rule_versions, alerts, alert_events, deliveries")
  System_Ext(targets, "Notification targets", "Webhook / SMTP / Slack / Telegram")

  Container_Boundary(worker, "Worker") {
    Component(bus, "Event bus", "oci-app", "Dispatches ChainEvent keyed by address")
    Component(registry, "Rule registry", "oci-alerting", "Enabled rule versions per tenant, hot reload")
    Component(compiler, "Rule compiler", "oci-alerting", "Schema v1 validation, closed operator tree, limits, hash")
    Component(evaluator, "Rule evaluator", "oci-alerting", "Conditions, sliding windows, watermarks")
    Component(windows, "Window state store", "oci-alerting", "Retractable contributions per (rule, groupKey)")
    Component(dedup, "Deduplicator / cooldown", "oci-alerting", "Dedup key, cooldown, grouping")
    Component(alerts, "Alert lifecycle", "oci-alerting", "Status machine, history, retraction")
    Component(connectors, "Connector registry", "oci-alerting", "Webhook HMAC, email, Slack, Telegram")
    Component(delivery, "Delivery worker", "oci-alerting", "Outbox lease, retry + backoff, SSRF guard, DLQ")
  }

  Rel(bus, evaluator, "ChainEvent")
  Rel(evaluator, registry, "Gets compiled rules")
  Rel(registry, compiler, "Compiles")
  Rel(registry, db, "Loads rule_versions")
  Rel(evaluator, windows, "Add / retract")
  Rel(evaluator, dedup, "RuleMatch")
  Rel(dedup, alerts, "Open / update / suppress")
  Rel(alerts, db, "alerts + alert_events + deliveries (one tx)")
  Rel(delivery, db, "Lease pending deliveries")
  Rel(delivery, connectors, "Resolve")
  Rel(delivery, targets, "Send")
```

## Key contracts between components

| From → To | Contract |
|---|---|
| Bus → Evaluator | `ChainEvent` (deterministic `eventId`, `eventTime`, `removed`, flat `fields`) |
| Registry → Evaluator | `CompiledRule` (immutable; `ruleId`, `version`, `sourceHash`, predicate tree, window spec, dedup spec) |
| Evaluator → Deduplicator | `RuleMatch(ruleId, version, groupKey, windowStart, windowEnd, contributingEventIds, explanation)` |
| Deduplicator → Alerts | open / append-occurrence / suppress decision with `dedupKey` |
| Alerts → Delivery | `deliveries` row (`connector_id`, `payload`, `idempotency_key = alertId:connectorId:eventSeq`) |
