-- Predictions (ADR-0023 amendment 2, level PREDICTED): hypotheses about future observable events, each with an expiry and
-- a machine-evaluable condition, and their final evaluation. Built from public chain signals, like signals themselves, so
-- not client-scoped in this first model. Append-only: a prediction is never changed; its evaluation is written once.

create table predictions (
  id              text primary key,
  type            text not null,
  network         text not null,
  subject         text not null,
  confidence      double precision,          -- null: uncalibrated (the realisation rate of the type is not established)
  signal_ids      jsonb not null,
  limits          jsonb not null,
  issued_at       timestamptz not null,
  expires_at      timestamptz not null check (expires_at > issued_at),
  min_outgoing    int not null check (min_outgoing >= 1),
  excluded_event  text,
  model           text not null
);
create index predictions_issued on predictions (issued_at desc, id);

create table prediction_evaluations (
  prediction_id   text primary key references predictions (id) on delete cascade,
  outcome         text not null check (outcome in ('REALISED', 'NOT_REALISED', 'UNDETERMINED')),
  evidence        jsonb not null,
  reason          text not null,
  evaluated_at    timestamptz not null
);

create function predictions_append_only() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' then
    raise exception '% is append-only', tg_table_name;
  end if;
  if tg_table_name = 'prediction_evaluations' and exists (select 1 from predictions where id = old.prediction_id) then
    raise exception 'prediction_evaluations is append-only: an evaluation is deleted only with its prediction';
  end if;
  return old;
end $$;

create trigger predictions_no_update before update on predictions for each row execute function predictions_append_only();
create trigger prediction_evaluations_append_only before update or delete on prediction_evaluations
  for each row execute function predictions_append_only();
