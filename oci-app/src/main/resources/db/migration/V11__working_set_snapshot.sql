-- ADR-0024 step 3d: snapshot of the bounded intelligence working set (flows, wallet profiles, signals, score histories,
-- remembered balances), written at an orderly shutdown and reloaded before the indexers start. Additive only.
-- A generation is usable only once completed_at is set; its checkpoints column copies indexer_checkpoints as they were
-- when the snapshot was taken: the snapshot is restored only while indexer_checkpoints still equals that copy (the
-- indexers have not moved since), so the restored state and the indexing position always match.
create table working_set_snapshots (
  generation   bigint primary key,
  format       int not null,
  app_version  text not null,
  created_at   timestamptz not null,
  completed_at timestamptz,
  checkpoints  jsonb not null,
  size_bytes   bigint,
  counts       jsonb
);

create table working_set_snapshot_chunks (
  generation bigint not null references working_set_snapshots (generation) on delete cascade,
  seq        int not null,
  data       bytea not null,
  primary key (generation, seq)
);
