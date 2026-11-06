# C4 — Level 1: System context

Source of truth: [`workspace.dsl`](workspace.dsl) (render with Structurizr Lite: `docker run -it --rm -p 8081:8080 -v $PWD/docs/architecture/c4:/usr/local/structurizr structurizr/lite`). The Mermaid version below is kept in sync by hand.

```mermaid
C4Context
  title OpenChain Intelligence — System context

  Person(analyst, "Analyst / Investigator", "Investigates wallets, triages alerts")
  Person(author, "Rule author", "Writes and dry-runs monitoring rules")
  Person(admin, "Administrator", "Tenants, API keys, connectors")

  System(oci, "OpenChain Intelligence", "Indexes chains, detects signals, scores risk, evaluates rules, notifies")

  System_Ext(idp, "Identity Provider", "Any OIDC provider (reference: Keycloak)")
  System_Ext(chains, "Blockchain data providers", "TronGrid-compatible API, java-tron node, EVM JSON-RPC")
  System_Ext(labels, "Label sources", "Sanctions lists, community reports")
  System_Ext(targets, "Notification targets", "Webhooks, SMTP, Slack, Telegram")
  System_Ext(customer, "Customer systems", "Back-office, case management, SIEM")

  Rel(analyst, oci, "Uses", "HTTPS")
  Rel(author, oci, "Manages rules", "HTTPS")
  Rel(admin, oci, "Administers", "HTTPS")
  Rel(customer, oci, "Calls API v1", "HTTPS + API key")
  Rel(oci, idp, "Authenticates users", "OIDC")
  Rel(oci, chains, "Reads chain data", "JSON-RPC / REST")
  Rel(oci, labels, "Imports labels", "HTTPS")
  Rel(oci, targets, "Sends notifications", "HTTPS HMAC / SMTP")
```

## Responsibilities at the boundary

| External system | Trust | Notes |
|---|---|---|
| Identity Provider | Trusted for identity only | Roles mapped from token claims; tenant from a dedicated claim. |
| Blockchain providers | **Untrusted data**, unreliable availability | Responses validated (hash linkage, number parsing); failover across providers. |
| Label sources | Attributed, not authoritative unless official | Each label stores `source`, `evidence_level`, `licence`. |
| Notification targets | Untrusted network destinations | SSRF guard, timeouts, HMAC signatures. |
| Customer systems | Authenticated via API keys with scopes | Rate limited per key and tenant. |
