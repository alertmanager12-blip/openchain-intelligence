# C4 — Level 2: Containers

Source of truth: [`workspace.dsl`](workspace.dsl).

```mermaid
C4Container
  title OpenChain Intelligence — Containers

  Person(user, "Analyst / Rule author / Admin")
  System_Ext(customer, "Customer systems", "API clients")
  System_Ext(idp, "Identity Provider", "OIDC")
  System_Ext(chains, "Blockchain providers", "TronGrid / JSON-RPC")
  System_Ext(targets, "Notification targets", "Webhook / SMTP / Slack / Telegram")

  System_Boundary(oci, "OpenChain Intelligence") {
    Container(spa, "Web frontend", "Angular, Cytoscape.js, nginx", "Analysis, graph explorer, rule editor, triage")
    Container(api, "API (api role)", "Java 21, Spring Boot", "REST API v1, OIDC + API keys, RBAC, tenancy")
    Container(worker, "Workers (worker role)", "Java 21, Spring Boot", "Indexing, detection, scoring, rules, delivery")
    ContainerDb(db, "PostgreSQL 17", "Relational", "System of record, partitioned transfers, outbox, audit")
  }

  Rel(user, spa, "Uses", "HTTPS")
  Rel(spa, api, "Calls", "HTTPS/JSON")
  Rel(spa, idp, "Login", "OIDC + PKCE")
  Rel(customer, api, "Calls", "HTTPS + API key")
  Rel(api, idp, "Validates JWT", "JWKS")
  Rel(api, db, "Reads/writes", "JDBC")
  Rel(worker, db, "Reads/writes, leases, outbox", "JDBC")
  Rel(worker, chains, "Fetches blocks/logs", "HTTPS")
  Rel(worker, targets, "Delivers", "HTTPS HMAC / SMTP")
```

## Notes

- `api` and `worker` are the **same image**; the MVP runs both in one process. Role selection by `OCI_ROLES` (`api`, `worker`, `api,worker`) is planned and not implemented yet.
- Workers coordinate only through PostgreSQL (checkpoints, `FOR UPDATE SKIP LOCKED` leases, outbox). Any number of worker replicas can run; one indexer lease per network is held at a time.
- Optional containers (not shown): OpenTelemetry Collector, Prometheus, Grafana (`observability` profile); later Kafka, ClickHouse, Valkey.
