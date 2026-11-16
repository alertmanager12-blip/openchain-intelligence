-- Client scopes (decision D1, docs/FUND_FLOW_TRACE_TENANT_ISOLATION.md). One instance per client remains the default
-- deployment; the scope is carried from now on by every credential so that a multi-client deployment is not a rewrite.
-- Additive: every existing key and user belongs to the 'default' scope. Alerts, rules, watchlist, webhooks follow in a
-- separate migration before any hosted multi-client offer; the trace tables get client_scope_id (and row-level security)
-- when they are created.

create table client_scopes (
  id          text primary key check (id ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
  name        text not null,
  active      boolean not null default true,
  created_at  timestamptz not null default now()
);
insert into client_scopes (id, name) values ('default', 'Default client');

alter table api_keys add column client_scope_id text not null default 'default' references client_scopes (id);
alter table users add column client_scope_id text not null default 'default' references client_scopes (id);
create index api_keys_client_scope on api_keys (client_scope_id);
