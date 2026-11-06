# ADR-0011: Angular frontend with Cytoscape.js for graphs

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

The UI is a data-dense professional tool: wallet/transaction analysis, interactive counterparty graph, YAML rule editor with validation and dry-run, alert triage with comments. Long-term maintainability and strong typing matter more than minimal bundle size.

## Considered options

1. **Angular** (MIT) + **Cytoscape.js** (MIT) for graphs.
2. React (MIT) + Sigma.js / react-force-graph.
3. Vue (MIT) + vis-network.
4. Server-rendered UI (Thymeleaf/htmx).

## Decision outcome

Chosen option: **1**.

### Pros

- Batteries included (router, forms, HTTP client, DI, testing), strict TypeScript, standalone components and signals; consistent structure for contributors.
- Cytoscape.js: mature graph model and layouts (cose, breadthfirst, dagre via extension), good performance for a few thousand elements, permissive licence.
- Clear separation: the SPA uses only the public API v1, which keeps the API complete for integrators.

### Cons

- Steeper learning curve than Vue; heavier initial bundle.
- Cytoscape.js canvas rendering struggles beyond ~10 k elements (mitigated by server-side caps and aggregation).

## Consequences

- Frontend lives in `/frontend`, built with `npm ci && npm run build`, served by nginx (`deploy/frontend.Dockerfile`).
- Editor: CodeMirror 6 (MIT) with YAML mode and server-side validation feedback.
- All licences of frontend dependencies are checked against the allowlist (ADR-0012).
