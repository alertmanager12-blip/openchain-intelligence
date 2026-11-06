-- OpenChain Intelligence - operator state (ADR-0004). Applied by Flyway at application startup.
-- Chain-derived state (transfers, profiles, signals, risk scores, checkpoints) is NOT stored here yet: it is kept in
-- memory and rebuilt by re-indexing (see docs/architecture/data-model.md, "Implemented schema").
-- Identifiers are text (the application generates UUIDs or slugs, e.g. rule ids, "wh-..." connector ids).
-- Single tenant for now: no tenant_id / row-level security yet.

-- ---- identity ------------------------------------------------------------------------------------------------------

create table users (
  id             text primary key,
  email          text,
  name           text,
  role           text not null check (role in ('VIEWER', 'ANALYST', 'RULE_AUTHOR', 'ADMIN')),
  last_login_at  timestamptz,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create table api_keys (
  id            text primary key,
  name          text not null,
  env           text not null,
  prefix        text not null unique,          -- public lookup part (8 base62 chars of oci_<env>_<prefix>_<secret>)
  secret_hash   bytea not null,                -- HMAC-SHA256(pepper, secret): never the secret itself
  scopes        jsonb not null,                -- ["alerts:read", ...]
  created_by    text,
  created_at    timestamptz not null,
  expires_at    timestamptz,
  last_used_at  timestamptz,                   -- written at most once a minute per key
  revoked_at    timestamptz
);
create index api_keys_created_by on api_keys (created_by, name);

-- ---- notification connectors -----------------------------------------------------------------------------------------

create table connectors (
  id                    text primary key,      -- "wh-<16 hex>", referenced by rules as actions[].connectorId
  type                  text not null check (type in ('WEBHOOK', 'SLACK', 'DISCORD', 'TELEGRAM')),
  name                  text not null,
  target_masked         text not null,         -- display form only (scheme://host/... or ...last4)
  events                jsonb not null,
  enabled               boolean not null default true,
  created_by            text,
  created_at            timestamptz not null,
  updated_at            timestamptz not null default now(),
  last_delivery_at      timestamptz,
  last_delivery_status  text,
  -- AES-256-GCM (key id + 96-bit nonce + ciphertext||tag); associated data binds each value to id and column
  enc_key_id            text not null,
  target_nonce          bytea not null,
  target_ciphertext     bytea not null,        -- URL or chat id (Slack/Discord URLs are credentials)
  secret_nonce          bytea,
  secret_ciphertext     bytea,                 -- WEBHOOK signing secret (null for other types)
  check ((secret_nonce is null) = (secret_ciphertext is null))
);

-- ---- rules -------------------------------------------------------------------------------------------------------------

create table rules (
  id               text primary key,           -- rule id from the source (slug)
  origin           text not null check (origin in ('repository', 'api')),
  enabled          boolean not null default false,
  current_version  int not null check (current_version >= 1),
  created_at       timestamptz not null,
  updated_at       timestamptz not null,
  deleted_at       timestamptz,                -- soft delete (versions kept; repository files are not re-imported)
  row_version      bigint not null            -- optimistic locking
);

create table rule_versions (                   -- immutable
  rule_id        text not null references rules (id),
  version        int not null check (version >= 1),
  source         text not null,                -- exactly as submitted (YAML or JSON)
  format         text not null check (format in ('YAML', 'JSON')),
  compiled_hash  text not null,                -- sha256 of the source
  rule_semver    text,                         -- the rule's own "version" field
  created_at     timestamptz not null,
  primary key (rule_id, version)
);

-- ---- alerts ------------------------------------------------------------------------------------------------------------

create table alerts (
  id                 text primary key,
  rule_id            text not null,            -- no FK: alerts outlive deleted rules
  rule_version       text,
  title              text,
  severity           text not null,
  priority           text not null,
  status             text not null check (status in ('DETECTED', 'PENDING', 'NOTIFIED', 'ACKNOWLEDGED', 'INVESTIGATING',
                                                     'RESOLVED', 'FALSE_POSITIVE')),
  evidence_level     text,
  network            text,
  group_key          text,
  dedup_key          text,
  matched_event_ids  jsonb not null,           -- bounded (500 most recent)
  evidence           jsonb not null,           -- TypedJson (amounts as exact decimals)
  first_seen         timestamptz,
  last_seen          timestamptz,
  occurrence_count   bigint not null,
  assignee           text,
  created_at         timestamptz not null,
  updated_at         timestamptz not null,
  escalation_level   int not null default 0,
  last_escalated_at  timestamptz,
  origin_chain       jsonb not null,
  hop                int not null default 0,
  row_version        bigint not null           -- optimistic locking
);
create index alerts_open_dedup on alerts (rule_id, dedup_key, created_at desc) where status not in ('RESOLVED', 'FALSE_POSITIVE');
create index alerts_status on alerts (status, created_at);
create index alerts_created on alerts (created_at, id);
create index alerts_event_ids on alerts using gin (matched_event_ids jsonb_path_ops);

create table alert_history (                   -- append-only
  alert_id     text not null references alerts (id),
  seq          int not null,
  from_status  text,
  to_status    text not null,
  actor        text not null,
  at           timestamptz not null,
  reason       text,
  primary key (alert_id, seq)
);

create table alert_comments (                  -- append-only, plain text
  alert_id  text not null references alerts (id),
  seq       int not null,
  author    text not null,
  at        timestamptz not null,
  body      text not null check (length(body) <= 10000),
  primary key (alert_id, seq)
);

-- ---- outbox ------------------------------------------------------------------------------------------------------------

create table deliveries (
  id                text primary key,          -- also the receivers' idempotency key (X-OCI-Delivery-Id)
  alert_id          text not null references alerts (id),
  connector_id      text not null,             -- no FK: built-in connectors (demo sink, internal bus) are not rows
  action_type       text not null,
  notification      jsonb not null,            -- immutable snapshot: retries send exactly the same content
  status            text not null check (status in ('PENDING', 'DELIVERED', 'DEAD_LETTER')),
  attempts          int not null default 0,
  next_attempt_at   timestamptz,
  last_error        text,
  last_status_code  int not null default 0,
  created_at        timestamptz not null,
  updated_at        timestamptz not null,
  delivered_at      timestamptz,
  claimed_by        text,                      -- instance holding the attempt lease (FOR UPDATE SKIP LOCKED claim)
  claimed_until     timestamptz
);
create index deliveries_due on deliveries (next_attempt_at, id) where status = 'PENDING';
create index deliveries_alert on deliveries (alert_id, created_at);
create index deliveries_status on deliveries (status, created_at);

-- ---- community label reports ---------------------------------------------------------------------------------------------

create table label_reports (
  id             text primary key,
  network        text not null,
  address        text not null,
  category       text not null,
  description    text,
  evidence_urls  jsonb not null,
  status         text not null,                -- PENDING (moderation)
  reporter       text,
  created_at     timestamptz not null
);
create index label_reports_created on label_reports (created_at desc);
