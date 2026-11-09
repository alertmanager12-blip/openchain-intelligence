# ADR-0025: MCP server as a thin adapter of the REST API, authorised per operation

- Status: proposed
- Date: 2026-11-09

## Context and problem statement

MCP became standard in 2026 (TRM, GraphSense, Arkham, Elliptic, Breadcrumbs). Agents must be able to analyse a wallet,
fetch evidence, run an investigation and configure monitoring, within their permissions. On-chain data (token names,
memos) is attacker-controlled and can carry prompt-injection payloads.

## Decision (proposed)

Expose an MCP server from `oci-app` whose tools call the same use cases as the REST endpoints, with the **same scope
per operation** (e.g. `wallet:read`, `investigation:write`, `rules:write`). No business logic in the MCP layer. Read-only
tools first; write tools opt-in and audited. Tool results return chain data as typed fields and mark them as untrusted
content. SDKs (Java, Python, TypeScript) are generated from the OpenAPI document, not hand-written.

## Consequences

- Positive: one authorisation model; small surface; parity with the market at low cost.
- Negative: an additional transport to secure and test (authorisation test per tool).
