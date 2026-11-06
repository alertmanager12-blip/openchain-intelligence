-- Freeze backfill (oci.chains.<network>.freeze-backfill): resumable progress of the historical scan of stablecoin
-- issuer blacklist events, and the events it found. Chain-derived label state is in memory: the stored events are
-- re-applied at startup (no RPC) and the scan resumes after last_scanned_block.

create table backfill_progress (
  id                  text primary key,           -- e.g. freezes:ethereum
  network             text not null,
  from_block          bigint not null check (from_block >= 0),
  last_scanned_block  bigint not null,            -- every block in [from_block, last_scanned_block] has been scanned
  freezes_found       bigint not null default 0,
  updated_at          timestamptz not null
);

create table backfill_freeze_events (
  backfill_id     text not null references backfill_progress (id),
  event_id        text not null,                  -- network:txHash:logIndex, same id as the live indexer
  network         text not null,
  tx_hash         text not null,
  log_index       int not null,
  block_number    bigint not null,
  block_hash      text,
  block_time      timestamptz not null,
  target          text not null,                  -- frozen / unfrozen address (canonical form)
  token_contract  text not null,                  -- official issuer contract that emitted the event
  issuer          text not null,
  action          text not null check (action in ('FREEZE', 'UNFREEZE')),
  event_name      text not null,
  primary key (backfill_id, event_id)
);
create index backfill_freeze_events_order on backfill_freeze_events (backfill_id, block_number, log_index);
