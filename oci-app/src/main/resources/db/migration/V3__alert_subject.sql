-- Alert subject (what the alert is about), captured from the triggering event at creation time and refreshed from the
-- latest trigger when an occurrence is grouped. Before V3 the API derived address / transaction / matched event from
-- the in-memory event history, which is empty after a restart ("No wallet" on every older alert).

alter table alerts
  add column subject_event_id     text,         -- triggering event id (network:txHash:logIndex, signal:..., ...)
  add column subject_event_type   text,         -- TOKEN_TRANSFER, DETECTION_SIGNAL, ...
  add column subject_address      text,         -- wallet the alert is about (wallet.address, else transaction.from, ...)
  add column subject_counterparty text,         -- counterparty.address, else transaction.to
  add column subject_tx_hash      text,
  add column subject_block        bigint,
  add column subject_time         timestamptz,  -- event time of the trigger
  add column subject_fields       jsonb;        -- TypedJson snapshot of the (enriched) event fields the rule saw

create index alerts_subject_address on alerts (subject_address);

-- Best-effort backfill from data already stored, only where it is unambiguous: aggregation / sequence alerts whose
-- first grouping field is the wallet (group_key = "<network>|<wallet>|..."; e.g. wallet-draining groups by
-- [transaction.from, transaction.token.symbol]). Transaction hash, counterparty and field snapshot cannot be derived
-- reliably and stay null; the application fills the whole subject when the triggering event is replayed by re-indexing.
update alerts
   set subject_address = split_part(group_key, '|', 2)
 where subject_address is null
   and group_key like '%|%'
   and split_part(group_key, '|', 2) <> ''
   and ((evidence ->> 'kind' = 'AGGREGATION' and evidence -> 'groupBy' ->> 0 in ('transaction.from', 'wallet.address'))
     or (evidence ->> 'kind' = 'SEQUENCE' and evidence ->> 'key' in ('transaction.from', 'wallet.address')));
