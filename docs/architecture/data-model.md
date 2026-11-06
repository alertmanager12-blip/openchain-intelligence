# Data model (PostgreSQL 17)

Conventions:

- Primary keys: `uuid` (UUIDv7, time-ordered) for tenant-owned entities; natural keys for chain data (`event_id`, `(network, number)`).
- Amounts: `numeric(78,0)` for raw integer units (fits `uint256`), `numeric(78,18)` only for derived human amounts. Never `float`/`double`.
- Addresses: `text` in canonical form (EVM lowercase hex, TRON base58) + `network text`.
- Time: `timestamptz`, UTC.
- Tenant-owned tables carry `tenant_id uuid not null` and have Row-Level Security (`USING (tenant_id = current_setting('app.tenant_id')::uuid)`).
- Migrations: Flyway (`oci-app/src/main/resources/db/migration`). The DDL below is the reference design; migrations are authoritative.

## ER diagram

```mermaid
erDiagram
  TENANTS ||--o{ USERS : has
  TENANTS ||--o{ API_KEYS : issues
  TENANTS ||--o{ RULES : owns
  TENANTS ||--o{ CONNECTORS : owns
  TENANTS ||--o{ ALERTS : owns
  TENANTS ||--o{ AUDIT_LOG : records
  RULES ||--o{ RULE_VERSIONS : versions
  RULE_VERSIONS ||--o{ ALERTS : raises
  ALERTS ||--o{ ALERT_EVENTS : history
  ALERTS ||--o{ ALERT_COMMENTS : discussed
  ALERTS ||--o{ DELIVERIES : notifies
  CONNECTORS ||--o{ DELIVERIES : via
  USERS ||--o{ ALERT_COMMENTS : writes
  NETWORKS ||--o{ INDEX_CHECKPOINTS : tracks
  NETWORKS ||--o{ BLOCKS : contains
  NETWORKS ||--o{ TOKEN_TRANSFERS : contains
  WALLET_PROFILES ||--o{ SIGNALS : about
  WALLET_PROFILES ||--o{ RISK_SCORES : scored
  WALLET_PROFILES ||--o{ ADDRESS_LABELS : labelled

  TENANTS {
    uuid id PK
    text slug UK
    text name
    jsonb quotas
    timestamptz created_at
  }
  USERS {
    uuid id PK
    uuid tenant_id FK
    text subject UK "OIDC sub"
    text email
    text[] roles
  }
  API_KEYS {
    uuid id PK
    uuid tenant_id FK
    text prefix UK
    bytea secret_hash
    text[] scopes
    timestamptz expires_at
    timestamptz revoked_at
  }
  NETWORKS {
    text id PK
    int confirmations
    int max_reorg_depth
    boolean enabled
  }
  INDEX_CHECKPOINTS {
    text network PK
    text stream PK
    bigint block_number
    text block_hash
  }
  BLOCKS {
    text network PK
    bigint number PK
    text hash
    text parent_hash
    timestamptz ts
    boolean orphaned
  }
  TOKEN_TRANSFERS {
    text event_id PK
    text network
    text tx_hash
    int log_index
    bigint block_number
    text from_address
    text to_address
    text token_contract
    numeric raw_amount
    timestamptz ts
    boolean removed
  }
  WALLET_PROFILES {
    text network PK
    text address PK
    timestamptz first_seen
    timestamptz last_seen
    jsonb stats
  }
  ADDRESS_LABELS {
    uuid id PK
    text network
    text address
    text category
    text source
    text evidence_level
  }
  SIGNALS {
    uuid id PK
    text network
    text address
    text type
    text severity
    text evidence_level
    jsonb evidence
  }
  RISK_SCORES {
    uuid id PK
    text network
    text address
    int score
    text level
    jsonb contributions
  }
  RULES {
    uuid id PK
    uuid tenant_id FK
    text name
    int current_version
    boolean enabled
  }
  RULE_VERSIONS {
    uuid rule_id PK
    int version PK
    text source_yaml
    text compiled_hash
  }
  ALERTS {
    uuid id PK
    uuid tenant_id FK
    uuid rule_id FK
    int rule_version
    text dedup_key
    text status
    text severity
  }
  ALERT_EVENTS {
    bigint id PK
    uuid alert_id FK
    text kind
    jsonb data
  }
  ALERT_COMMENTS {
    uuid id PK
    uuid alert_id FK
    uuid author_id FK
    text body
  }
  CONNECTORS {
    uuid id PK
    uuid tenant_id FK
    text kind
    jsonb config
    bytea secret_ciphertext
  }
  DELIVERIES {
    uuid id PK
    uuid alert_id FK
    uuid connector_id FK
    text status
    int attempts
    text idempotency_key UK
  }
  AUDIT_LOG {
    bigint id PK
    uuid tenant_id
    text actor
    text action
    jsonb details
  }
```

## Table definitions

### Tenancy and identity

```sql
create table tenants (
  id          uuid primary key,
  slug        text not null unique check (slug ~ '^[a-z0-9-]{3,40}$'),
  name        text not null,
  quotas      jsonb not null default '{}',   -- rules, connectors, api rate, dry-run budget
  created_at  timestamptz not null default now()
);

create table users (
  id          uuid primary key,
  tenant_id   uuid not null references tenants(id),
  subject     text not null,                  -- OIDC "sub"
  issuer      text not null,
  email       text,
  roles       text[] not null default '{VIEWER}',
  created_at  timestamptz not null default now(),
  last_login  timestamptz,
  unique (issuer, subject)
);

create table api_keys (
  id           uuid primary key,
  tenant_id    uuid not null references tenants(id),
  name         text not null,
  prefix       text not null unique,          -- public lookup part, e.g. "oci_live_7Hq2"
  secret_hash  bytea not null,                -- HMAC-SHA256(pepper, secret) — never the secret
  scopes       text[] not null,
  created_by   uuid references users(id),
  created_at   timestamptz not null default now(),
  last_used_at timestamptz,
  expires_at   timestamptz,
  revoked_at   timestamptz
);
```

### Chain data (shared, not tenant-scoped)

```sql
create table networks (
  id               text primary key,          -- 'tron', 'ethereum', ...
  confirmations    int  not null,
  max_reorg_depth  int  not null,
  enabled          boolean not null default false,
  config           jsonb not null default '{}'  -- tracked token contracts, provider order
);

create table index_checkpoints (
  network       text not null references networks(id),
  stream        text not null,                 -- 'transfers', 'blocks'
  block_number  bigint not null,
  block_hash    text not null,
  updated_at    timestamptz not null default now(),
  lease_owner   text,
  lease_until   timestamptz,
  primary key (network, stream)
);

-- Recent window only (>= max_reorg_depth, default 10 000 blocks), used for reorg detection.
create table blocks (
  network      text not null,
  number       bigint not null,
  hash         text not null,
  parent_hash  text not null,
  ts           timestamptz not null,
  orphaned     boolean not null default false,
  primary key (network, number)
);

create table token_transfers (
  event_id        text not null,              -- network:txHash:logIndex
  network         text not null,
  tx_hash         text not null,
  log_index       int  not null,
  block_number    bigint not null,
  block_hash      text not null,
  ts              timestamptz not null,
  from_address    text not null,
  to_address      text not null,
  token_contract  text,                       -- null = native coin
  token_symbol    text,
  token_decimals  smallint not null,
  raw_amount      numeric(78,0) not null check (raw_amount >= 0),
  removed         boolean not null default false,
  ingested_at     timestamptz not null default now(),
  primary key (event_id, ts)
) partition by range (ts);
-- monthly partitions: token_transfers_2026_11 ... created ahead by a scheduled job

create index on token_transfers (network, from_address, ts desc);
create index on token_transfers (network, to_address, ts desc);
create index on token_transfers (network, tx_hash);
create index on token_transfers using brin (block_number);

-- Materialised adjacency for graph queries (aggregated edges)
create table address_edges (
  network         text not null,
  from_address    text not null,
  to_address      text not null,
  token_contract  text not null default '',
  tx_count        bigint not null,
  total_raw       numeric(78,0) not null,
  first_ts        timestamptz not null,
  last_ts         timestamptz not null,
  primary key (network, from_address, to_address, token_contract)
);
create index on address_edges (network, to_address);
```

### Intelligence

```sql
create table wallet_profiles (
  network      text not null,
  address      text not null,
  first_seen   timestamptz,
  last_seen    timestamptz,
  tx_count     bigint not null default 0,
  stats        jsonb not null default '{}',   -- baselines: median amount, active hours, counterparties
  is_contract  boolean,
  refreshed_at timestamptz,
  primary key (network, address)
);

create table address_labels (
  id              uuid primary key,
  network         text not null,
  address         text not null,
  category        text not null,              -- EXCHANGE, SANCTIONED, MIXER, SCAM_REPORTED, ...
  name            text,
  source          text not null,              -- 'ofac-sdn', 'community-report', ...
  source_ref      text,                       -- URL / list entry id
  licence         text,
  evidence_level  text not null check (evidence_level in ('ANOMALY','RISK_SIGNAL','CONFIRMED_BY_SOURCE')),
  status          text not null default 'ACTIVE', -- ACTIVE, DISPUTED, WITHDRAWN
  tenant_id       uuid,                       -- null = global; set = private tenant label
  valid_from      timestamptz not null default now(),
  valid_to        timestamptz
);
create index on address_labels (network, address) where status = 'ACTIVE';

create table signals (
  id              uuid primary key,
  network         text not null,
  address         text not null,
  tx_hash         text,
  type            text not null,
  severity        text not null,
  confidence      numeric(4,3) not null check (confidence between 0 and 1),
  evidence_level  text not null,
  evidence        jsonb not null,             -- [{kind, description, reference, source, observedAt}]
  detector_id     text not null,              -- 'fast-forward@1'
  detected_at     timestamptz not null,
  retracted_at    timestamptz                 -- set on reorg
);
create index on signals (network, address, detected_at desc);

create table risk_scores (                   -- append-only history
  id             uuid primary key,
  network        text not null,
  address        text not null,
  score          smallint not null check (score between 0 and 100),
  level          text not null,
  contributions  jsonb not null,              -- RiskScore.Contribution[]
  method         text not null,
  model_version  text not null,
  computed_at    timestamptz not null
);
create index on risk_scores (network, address, computed_at desc);
```

### Rules and alerts (tenant-scoped)

```sql
create table rules (
  id               uuid primary key,
  tenant_id        uuid not null references tenants(id),
  name             text not null,
  description      text,
  current_version  int not null,
  enabled          boolean not null default false,
  created_by       uuid references users(id),
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  row_version      bigint not null default 0,  -- optimistic locking (ETag / If-Match)
  unique (tenant_id, name)
);

create table rule_versions (                 -- immutable
  rule_id         uuid not null references rules(id),
  version         int not null,
  schema_version  text not null,              -- 'oci.rules/v1'
  source_yaml     text not null check (length(source_yaml) <= 65536),
  compiled_hash   text not null,              -- sha256 of canonical compiled form
  created_by      uuid references users(id),
  created_at      timestamptz not null default now(),
  primary key (rule_id, version)
);

create table alerts (
  id               uuid primary key,
  tenant_id        uuid not null references tenants(id),
  rule_id          uuid not null references rules(id),
  rule_version     int not null,
  dedup_key        text not null,
  status           text not null,             -- OPEN, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, FALSE_POSITIVE, RETRACTED
  severity         text not null,
  title            text not null,
  network          text not null,
  subject_address  text,
  window_start     timestamptz,
  window_end       timestamptz,
  explanation      jsonb not null,            -- conditions evaluated, values, thresholds
  event_ids        text[] not null,           -- contributing events (capped, rest in alert_events)
  provisional      boolean not null default false,
  assignee_id      uuid references users(id),
  occurrences      int not null default 1,
  last_notified_at timestamptz,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
create unique index alerts_open_dedup on alerts (tenant_id, dedup_key)
  where status in ('OPEN','ACKNOWLEDGED','IN_PROGRESS');
create index on alerts (tenant_id, status, created_at desc);
create index on alerts (tenant_id, subject_address);

create table alert_events (                  -- append-only history
  id         bigserial primary key,
  alert_id   uuid not null references alerts(id),
  tenant_id  uuid not null,
  kind       text not null,                   -- OPENED, OCCURRENCE_ADDED, STATUS_CHANGED, ASSIGNED, RETRACTED, DELIVERY_FAILED
  actor      text,
  data       jsonb not null default '{}',
  at         timestamptz not null default now()
);
create index on alert_events (alert_id, at);

create table alert_comments (
  id         uuid primary key,
  alert_id   uuid not null references alerts(id),
  tenant_id  uuid not null,
  author_id  uuid not null references users(id),
  body       text not null check (length(body) <= 10000),
  created_at timestamptz not null default now()
);

create table connectors (
  id                 uuid primary key,
  tenant_id          uuid not null references tenants(id),
  kind               text not null,           -- WEBHOOK, EMAIL, SLACK, TELEGRAM
  name               text not null,
  config             jsonb not null,          -- url, headers allow-list, recipients (no secrets)
  secret_ciphertext  bytea,                   -- AES-256-GCM(secret), nonce prefixed
  secret_key_id      text,                    -- encryption key id for rotation
  enabled            boolean not null default true,
  created_at         timestamptz not null default now()
);

create table deliveries (                    -- transactional outbox
  id                uuid primary key,
  tenant_id         uuid not null,
  alert_id          uuid references alerts(id),
  connector_id      uuid not null references connectors(id),
  idempotency_key   text not null unique,    -- alertId:connectorId:eventSeq
  payload           jsonb not null,
  status            text not null,           -- PENDING, RETRY, DELIVERED, FAILED, DEAD
  attempts          int not null default 0,
  next_attempt_at   timestamptz not null default now(),
  last_status_code  int,
  last_error        text,
  created_at        timestamptz not null default now(),
  delivered_at      timestamptz
);
create index deliveries_due on deliveries (next_attempt_at) where status in ('PENDING','RETRY');

create table audit_log (                     -- append-only; UPDATE/DELETE revoked from app role
  id          bigserial primary key,
  tenant_id   uuid,
  actor       text not null,                  -- user id or api key prefix
  actor_type  text not null,                  -- USER, API_KEY, SYSTEM
  action      text not null,                  -- RULE_UPDATED, API_KEY_CREATED, ...
  target      text,
  ip          inet,
  user_agent  text,
  details     jsonb not null default '{}',
  prev_hash   bytea,                          -- hash chain for tamper evidence
  at          timestamptz not null default now()
);
create index on audit_log (tenant_id, at desc);
```

## Retention

| Table | Default retention | Mechanism |
|---|---|---|
| `blocks` | last `max(max_reorg_depth, 10 000)` blocks per network | scheduled delete |
| `token_transfers` | 13 months (configurable) | drop monthly partitions |
| `address_edges` | rebuilt from transfers; follows transfer retention | incremental upsert + periodic compaction |
| `signals` | 24 months | delete by `detected_at` |
| `risk_scores` | latest per address forever, history 24 months | delete older than N except latest |
| `alerts`, `alert_events`, `alert_comments` | 5 years (compliance) | tenant-configurable |
| `deliveries` | 30 days after terminal status | scheduled delete |
| `audit_log` | 5 years | partition by year, never updated |

Personal data: `users.email` and comments may contain personal data; tenant deletion cascades to all tenant-scoped rows. Chain data is public and not personal data per se, but labels attached to addresses are treated as sensitive and always carry their source.
