-- AI summaries of investigations (ADR-0022 amendment 5): one row per request, append-only. status ACCEPTED: sentences is
-- what may be shown (checked against the assertions); REFUSED: the check failed, reasons say why, nothing is shown;
-- FAILED: no usable answer (provider error, refusal, malformed answer). draft keeps the parsed answer for audit only.
-- Same rules as assertion_reviews (V16): never updated, deleted only with the investigation, client scope, forced RLS.

create table investigation_summaries (
  id                text primary key,
  client_scope_id   text not null references client_scopes (id),
  investigation_id  text not null references trace_artifacts (id) on delete cascade,
  provider          text not null,
  model             text,
  prompt_version    text not null,
  input_hash        text not null,
  status            text not null check (status in ('ACCEPTED', 'REFUSED', 'FAILED')),
  sentences         jsonb,
  draft             jsonb,
  reasons           jsonb not null,
  created_by        text not null,
  created_at        timestamptz not null
);
create index investigation_summaries_investigation on investigation_summaries (investigation_id, created_at, id);
create index investigation_summaries_scope_time on investigation_summaries (client_scope_id, created_at);

create function investigation_summaries_append_only() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' then
    raise exception 'investigation_summaries is append-only: a summary is never updated';
  end if;
  if exists (select 1 from trace_artifacts where id = old.investigation_id) then
    raise exception 'investigation_summaries is append-only: a summary is deleted only with its investigation';
  end if;
  return old;
end $$;

create trigger investigation_summaries_append_only before update or delete on investigation_summaries
  for each row execute function investigation_summaries_append_only();

alter table investigation_summaries enable row level security;
alter table investigation_summaries force row level security;
create policy investigation_summaries_scope on investigation_summaries
  using (current_setting('oci.scope', true) = '*' or client_scope_id = current_setting('oci.scope', true))
  with check (current_setting('oci.scope', true) = '*' or client_scope_id = current_setting('oci.scope', true));
