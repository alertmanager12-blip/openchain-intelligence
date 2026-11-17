-- Human review of investigation assertions (ADR-0022 amendment 3): a decision next to an assertion, never a change to it.
-- Append-only: a new decision is a new row and the latest one is the current decision. A row is never updated, and is
-- deleted only together with its investigation (foreign key cascade); the trigger refuses everything else, also for the
-- table owner and superusers (row-level security does not stop them, the trigger does).
-- Client scope and forced row-level security as trace_artifacts (V14).

create table assertion_reviews (
  id                text primary key,
  client_scope_id   text not null references client_scopes (id),
  investigation_id  text not null references trace_artifacts (id) on delete cascade,
  assertion_id      text not null,
  decision          text not null check (decision in ('ACCEPTED', 'REJECTED', 'NEEDS_EVIDENCE')),
  comment           text check (comment is null or char_length(comment) <= 2000),
  actor             text not null,
  created_at        timestamptz not null
);
create index assertion_reviews_investigation on assertion_reviews (investigation_id, created_at, id);

create function assertion_reviews_append_only() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' then
    raise exception 'assertion_reviews is append-only: a review is never updated';
  end if;
  -- a cascade from the deleted investigation is allowed; a direct delete is not
  if exists (select 1 from trace_artifacts where id = old.investigation_id) then
    raise exception 'assertion_reviews is append-only: a review is deleted only with its investigation';
  end if;
  return old;
end $$;

create trigger assertion_reviews_append_only before update or delete on assertion_reviews
  for each row execute function assertion_reviews_append_only();

alter table assertion_reviews enable row level security;
alter table assertion_reviews force row level security;
create policy assertion_reviews_scope on assertion_reviews
  using (current_setting('oci.scope', true) = '*' or client_scope_id = current_setting('oci.scope', true))
  with check (current_setting('oci.scope', true) = '*' or client_scope_id = current_setting('oci.scope', true));
