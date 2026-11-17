-- Investigations (ADR-0022 amendment 2) are stored as artifacts too: same client scope, row-level security, frozen
-- evidence, versions and STALE marking as trace artifacts (V14). kind tells them apart; an investigation covers both
-- directions. request holds the plan inputs, result the assertions.
alter table trace_artifacts add column kind text not null default 'TRACE' check (kind in ('TRACE', 'INVESTIGATION'));
alter table trace_artifacts drop constraint trace_artifacts_direction_check;
alter table trace_artifacts add constraint trace_artifacts_direction_check check (direction in ('FORWARD', 'BACKWARD', 'BOTH'));
create index trace_artifacts_scope_kind on trace_artifacts (client_scope_id, kind, created_at desc);
