-- Trace artifacts (ADR-0022 amendment 1): one stored run of the trace engine with its frozen evidence, so that it can be
-- explained later exactly as it was computed. Versions of the same trace share a lineage; a stored version is never
-- modified, except its STALE mark after a chain reorganisation.
-- Client scope on every row (decision D1), with row-level security from creation: rows are visible only when the
-- transaction sets oci.scope to their scope ('*' for system work such as marking STALE). Note: PostgreSQL superusers and
-- roles with BYPASSRLS ignore these policies; the repositories always filter by scope as well.

create table trace_artifacts (
  id               text primary key,
  client_scope_id  text not null references client_scopes (id),
  lineage_id       text not null,
  version          int not null check (version >= 1),
  previous_id      text references trace_artifacts (id),
  network          text not null,
  seed             text not null,
  direction        text not null check (direction in ('FORWARD', 'BACKWARD')),
  engine_version   text not null,
  status           text not null check (status in ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
  result_status    text check (result_status in ('COMPLETE', 'PARTIAL')),
  stale            boolean not null default false,
  stale_event_ids  jsonb,
  request          jsonb not null,
  result           jsonb,
  evidence         jsonb,          -- frozen flows, labels, coverage and opening balances read by the run
  diff             jsonb,          -- TraceDiff with the previous version
  error            text,
  created_by       text,
  created_at       timestamptz not null,
  completed_at     timestamptz,
  unique (lineage_id, version)
);
create index trace_artifacts_scope on trace_artifacts (client_scope_id, created_at desc);

-- Event ids of the frozen flows of each artifact: finds the artifacts a retracted flow makes STALE.
create table trace_artifact_flows (
  artifact_id      text not null references trace_artifacts (id) on delete cascade,
  client_scope_id  text not null references client_scopes (id),
  event_id         text not null,
  primary key (artifact_id, event_id)
);
create index trace_artifact_flows_event on trace_artifact_flows (event_id);

alter table trace_artifacts enable row level security;
alter table trace_artifacts force row level security;
create policy trace_artifacts_scope on trace_artifacts
  using (current_setting('oci.scope', true) = '*' or client_scope_id = current_setting('oci.scope', true))
  with check (current_setting('oci.scope', true) = '*' or client_scope_id = current_setting('oci.scope', true));

alter table trace_artifact_flows enable row level security;
alter table trace_artifact_flows force row level security;
create policy trace_artifact_flows_scope on trace_artifact_flows
  using (current_setting('oci.scope', true) = '*' or client_scope_id = current_setting('oci.scope', true))
  with check (current_setting('oci.scope', true) = '*' or client_scope_id = current_setting('oci.scope', true));
