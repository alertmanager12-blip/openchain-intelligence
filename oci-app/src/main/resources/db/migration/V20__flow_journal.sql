-- Persisted journal of the transfers of watched addresses (ADR-0026 amendment 1, decision D5 option d). Public chain data,
-- stored once; partitioned by month of the transfer time, retention by dropping partitions. Partitions are created by the
-- application (current and next month, checked daily); this migration creates a default partition so that no insert can
-- fail, and the application moves nothing out of it (it only stays as a safety net, and is watched by a metric).

create table flow_journal (
  network        text not null,
  event_id       text not null,
  tx_hash        text not null,
  ts             timestamptz not null,
  from_address   text not null,
  to_address     text not null,
  token_contract text,                 -- null for the native token
  token_symbol   text not null,
  token_decimals int not null,
  raw_amount     numeric(78, 0) not null check (raw_amount >= 0),
  source         text not null check (source in ('LIVE', 'HISTORY')),
  primary key (network, event_id, ts)
) partition by range (ts);

create table flow_journal_default partition of flow_journal default;
create index flow_journal_from on flow_journal (network, from_address, ts);
create index flow_journal_to on flow_journal (network, to_address, ts);

-- Since when the journal is complete for each watched address (enrolment, or the start of a loaded history).
create table journal_coverage (
  network        text not null,
  address        text not null,
  since          timestamptz not null,
  enrolled_at    timestamptz not null,
  primary key (network, address)
);
