# OpenChain Intelligence: console (Angular)

Investigation, risk intelligence and monitoring UI for the OpenChain Intelligence backend (`/api/v1`).

Requires Node `^22.22.3 || ^24.15.0 || >=26` (see `.nvmrc`; run `nvm use`).

| Command | What it does |
| --- | --- |
| `npm start` | Dev server on :4200 in **mock mode** (in-memory, clearly fake data: no backend needed) |
| `npm run start:api` | Dev server with mock off, `/api` proxied to `http://localhost:8080` (`proxy.conf.json`) |
| `npm run build` | Production build (mock off by default, mock store code-split out) |
| `npm test` | Unit tests (Vitest + jsdom) |

You can switch mock mode and credentials (Bearer token or `X-API-Key`) at runtime in **Settings**. They are stored in this browser only.

Layout: `src/app/core` holds the API client, interceptors, models, network detector, rule model with its YAML serializer, parser and validator, and the mock backend. `src/app/shared` holds UI primitives, formatting and table/query state. `src/app/features/*` holds one lazy route per page.

Principles shown in the UI: a signal is not proof of wrongdoing. ANOMALY, RISK_SIGNAL and CONFIRMED_BY_SOURCE are always shown as distinct levels. Amounts are exact (BigInt). Rules are declarative data and are never executed as code.
