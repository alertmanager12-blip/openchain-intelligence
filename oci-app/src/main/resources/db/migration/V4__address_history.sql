-- On-demand address history (ADR-0020): resumable progress of the per-address backfills started when an analyst opens
-- an address the live indexer has not covered, and the events they found. Chain-derived state is in memory: the stored
-- events are re-applied at startup (no RPC) and an interrupted backfill resumes from its cursor.

create table address_history_progress (
  id                  text primary key,             -- history:<network>:<address>
  network             text not null,
  address             text not null,
  source              text not null,                -- eth_getLogs, trongrid
  status              text not null check (status in ('PENDING', 'RUNNING', 'COMPLETE', 'TRUNCATED', 'FAILED')),
  from_block          bigint not null,              -- lowest block of the target range (lookback)
  to_block            bigint not null,              -- highest block of the target range (the live indexer covers later blocks)
  covered_from_block  bigint,                       -- every event in [covered_from_block, to_block] is stored
  covered_from_time   timestamptz,
  cursor              text,                         -- source cursor to resume from
  events_found        bigint not null default 0,
  pages               int not null default 0,
  truncated           boolean not null default false, -- max pages / max events reached before from_block
  last_error          text,
  created_at          timestamptz not null,
  updated_at          timestamptz not null
);

create table address_history_events (
  history_id    text not null references address_history_progress (id),
  event_id      text not null,                      -- same id as the live indexer when the source exposes it
  event_type    text not null,                      -- TOKEN_TRANSFER, NATIVE_TRANSFER, APPROVAL
  block_number  bigint not null,                    -- -1 when the source does not give it (TronGrid TRC-20)
  block_hash    text,
  event_time    timestamptz not null,
  fields        jsonb not null,                     -- TypedJson of the ChainEvent fields
  primary key (history_id, event_id)
);
create index address_history_events_order on address_history_events (history_id, event_time, event_id);
