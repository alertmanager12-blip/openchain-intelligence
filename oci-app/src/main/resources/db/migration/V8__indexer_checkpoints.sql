-- Durable indexer position of each real chain: indexing resumes after the last processed block after a restart,
-- instead of skipping every block produced while the application was down. The reorg window keeps block references only
-- (number, hash, parent hash, time): reorgs are still detected after a restart, but events of blocks indexed before the
-- restart cannot be retracted (rare: blocks are indexed after 12 to 19 confirmations).
create table indexer_checkpoints (
  indexer_id  text primary key,
  network     text not null,
  last_block  bigint not null,
  window_refs jsonb not null,
  updated_at  timestamptz not null
);
